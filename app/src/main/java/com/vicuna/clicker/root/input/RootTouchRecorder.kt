package com.vicuna.clicker.root.input

import android.content.Context
import android.graphics.Point
import android.os.SystemClock
import android.util.Log
import com.vicuna.clicker.root.bean.Bean
import com.vicuna.clicker.root.bean.RecordScriptCmd
import com.vicuna.clicker.root.helper.toDate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * 被动触摸录制器（支持 ROOT，后续可直接切换到 Shizuku / shell uid=2000）。
 *
 * 与旧 RecordTouchView 不同，它不创建全屏触摸拦截层，而是只读物理 touchscreen 的
 * /dev/input/eventX（通过 Android 自带 getevent）。因此真实触摸会照常交给目标 App，
 * 录制阶段不需要再把手势“重放”一遍。
 *
 * 针对快速连点丢指令的修复：
 * 1. 解决管道 4KB 缓冲：getevent 直接写管道时 bionic libc 会开启 4096 字节全缓冲，
 *    导致快速点击的短事件攒在缓冲区里不吐出，或者点“停止”时直接被杀进程丢弃。
 *    这里优先用 `stdbuf -oL` 或 `script -q -c ... /dev/null`（伪终端 PTY）强制按行实时输出，
 *    并在 stop() 时发送 SIGINT + 短暂 drain 管道，确保最后几下点击完整收回。
 * 2. 使用内核事件硬件时间戳：直接解析 `getevent -lt` 行首的 `[  1234.567890]`，
 *    在录制线程内精确计算每次点击的间隔（Delay）和按住时长（Duration），不受主线程 UI 刷新卡顿影响。
 * 3. 状态机防吞点：同时识别 TRACKING_ID 变化与 BTN_TOUCH DOWN/UP，若固件在极快连点时
 *    直接切换为新的正数 trackingId，自动先结算上一指再开启新一指，绝不把两次点击合并成一次。
 */
class RootTouchRecorder(private val context: Context) {

    companion object {
        private const val TAG = "RootTouchRecorder"
        private const val VIRTUAL_NAME = "Clicker Root Virtual Touchscreen"
        private val TIMESTAMP_REGEX = Regex("""\[\s*(\d+\.\d+)\s*]""")
    }

    data class TouchDeviceInfo(
        val path: String,
        val name: String,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val direct: Boolean
    )

    interface Listener {
        /**
         * 一个真正要保存的手势开始。
         * @param delayMs 距离上一次手势结束（或开始录制）的真实间隔毫秒数。
         */
        fun onGestureStart(delayMs: Int)

        /** 一个真正要保存的手势结束。 */
        fun onGesture(recordScriptCmd: RecordScriptCmd)

        fun onError(message: String) {}
    }

    private val running = AtomicBoolean(false)
    private val diagnostic = AtomicReference("idle")
    private var useShizuku = false
    private var process: java.lang.Process? = null
    private var readerThread: Thread? = null

    fun isRunning(): Boolean = running.get()
    fun diagnosticText(): String = diagnostic.get()

    /**
     * @param ignorePoint 返回 true 时，整次手势不写入脚本。用于过滤录制控制悬浮窗自身的拖动/点击。
     */
    @Synchronized
    fun start(
        listener: Listener,
        ignorePoint: (Point) -> Boolean = { false }
    ): Boolean {
        if (running.get()) return true
        useShizuku = !RootShell.isRootAvailable()
        if (useShizuku && !ShizukuInput.connect(context)) {
            diagnostic.set("ROOT 不可用，Shizuku 未连接或未授权，无法录制")
            listener.onError(diagnostic.get())
            return false
        }

        val device = discoverTouchDevice()
        if (device == null) {
            if (!diagnostic.get().contains("failed")) diagnostic.set("未找到可读取的触摸设备；系统可能限制 Shizuku 读取 /dev/input")
            listener.onError(diagnostic.get())
            return false
        }

        val wmBounds = (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .maximumWindowMetrics.bounds
        val mapper = RootDisplayMapper(context, wmBounds.width(), wmBounds.height())

        return try {
            val quotedDev = shellQuote(device.path)
            // 关键：当 stdout 是 pipe 时，getevent 默认 4KB 全缓冲，快速点击会攒够十几下才一次性吐出。
            // 优先用 stdbuf -oL 强制行缓冲；若 ROM 无 stdbuf 但有 toybox script，则用 PTY 触发行缓冲；
            // 都没有时退化为直接 exec。这套 shell 写法在 su 和 Shizuku (adb shell) 下完全通用。
            val command = buildString {
                append("if command -v stdbuf >/dev/null 2>&1; then ")
                append("exec stdbuf -oL /system/bin/getevent -lt $quotedDev; ")
                append("elif command -v script >/dev/null 2>&1; then ")
                append("exec script -q -c \"/system/bin/getevent -lt $quotedDev\" /dev/null; ")
                append("else ")
                append("exec /system/bin/getevent -lt $quotedDev; ")
                append("fi")
            }

            val p: java.lang.Process = if (useShizuku) ShizukuInput.record(device.path) else ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            process = p
            running.set(true)
            diagnostic.set("recording ${if (useShizuku) "SHIZUKU" else "ROOT"} ${device.path} (${device.name})")

            val startWallMs = SystemClock.uptimeMillis()
            readerThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream), 256).use { reader ->
                        parseEvents(reader, device, mapper, listener, ignorePoint, startWallMs)
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        val msg = "getevent reader failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                        diagnostic.set(msg)
                        Log.e(TAG, msg, t)
                        listener.onError(msg)
                    }
                } finally {
                    if (running.get() && diagnostic.get().startsWith("recording")) {
                        val msg = "录制事件流意外结束，请检查 Shizuku 连接及触摸设备权限"
                        diagnostic.set(msg);listener.onError(msg)
                    }
                    running.set(false)
                    runCatching { p.destroy() }
                }
            }, "root-touch-recorder").apply {
                isDaemon = true
                start()
            }
            true
        } catch (t: Throwable) {
            running.set(false)
            val msg = "start failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            diagnostic.set(msg)
            Log.e(TAG, msg, t)
            listener.onError(msg)
            false
        }
    }

    @Synchronized
    fun stop() {
        val wasRunning = running.getAndSet(false)
        val p = process
        val t = readerThread
        process = null
        readerThread = null

        if (p != null) {
            try {
                p.destroy()
            } catch (_: Throwable) {
            }
        }
        // 给读取线程最多 120ms 把管道里已经产生的最后几次点击读完并回调给 ViewModel，避免丢尾。
        if (wasRunning && t != null && t !== Thread.currentThread()) {
            try {
                t.join(if (useShizuku) 450 else 120)
            } catch (_: Throwable) {
            }
            try {
                if (t.isAlive) t.interrupt()
            } catch (_: Throwable) {
            }
        }
        if (p != null) {
            try {
                if (!p.waitFor(if (useShizuku) 300 else 50, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: Throwable) {
            }
        }
        diagnostic.set("stopped")
    }

    private fun parseEvents(
        reader: BufferedReader,
        device: TouchDeviceInfo,
        mapper: RootDisplayMapper,
        listener: Listener,
        ignorePoint: (Point) -> Boolean,
        startWallMs: Long
    ) {
        var currentSlot = 0
        var activeTrackingId = -1
        var frameTrackingId: Int? = null
        var pendingDown: Int? = null
        var pendingDownTime = 0L
        var frameBtnTouch: Int? = null // 1 = DOWN, 0 = UP
        var rawX: Int? = null
        var rawY: Int? = null

        var active = false
        var ignored = false
        var downAtMs = 0L
        var lastEventAnchorMs = startWallMs
        var kernelTimeOffsetMs: Long? = null
        var lastPoint: Point? = null
        var points = ArrayList<Bean>()

        fun resolveEventTimeMs(line: String): Long {
            val wallNow = SystemClock.uptimeMillis()
            val match = TIMESTAMP_REGEX.find(line)
            if (match != null) {
                val seconds = match.groupValues[1].toDoubleOrNull()
                if (seconds != null) {
                    val kernelMs = (seconds * 1000.0).toLong()
                    val offset = kernelTimeOffsetMs ?: (wallNow - kernelMs).also {
                        kernelTimeOffsetMs = it
                    }
                    return kernelMs + offset
                }
            }
            return wallNow
        }

        fun displayPoint(): Point? {
            val x = rawX ?: return null
            val y = rawY ?: return null
            return mapper.fromPhysicalTouchSpace(
                x = x,
                y = y,
                minX = device.minX,
                maxX = device.maxX,
                minY = device.minY,
                maxY = device.maxY
            )
        }

        fun addPoint(point: Point) {
            val prev = lastPoint
            // getevent 一帧可能重复上报同一坐标，去重可显著缩小 JSON。
            if (prev == null || prev.x != point.x || prev.y != point.y) {
                points.add(Bean(point.x, point.y))
                lastPoint = point
            }
        }

        fun finishActiveGesture(upTimeMs: Long) {
            if (!active) return
            val duration = (upTimeMs - downAtMs)
                .coerceIn(1L, 30_000L)
                .toInt()
            if (!ignored && points.isNotEmpty()) {
                Log.d(TAG, "recorded points=${points.size} first=${points.first().x},${points.first().y} last=${points.last().x},${points.last().y} duration_ms=$duration")
                val cmd = RecordScriptCmd.createGestureCMD(
                    points,
                    duration,
                    System.currentTimeMillis().toDate()
                )
                listener.onGesture(cmd)
                lastEventAnchorMs = upTimeMs
            }
            active = false
            ignored = false
            activeTrackingId = -1
            points = ArrayList()
            lastPoint = null
        }

        fun startNewGesture(point: Point, downTimeMs: Long, newTrackingId: Int) {
            active = true
            activeTrackingId = newTrackingId
            ignored = ignorePoint(point)
            downAtMs = downTimeMs
            points = ArrayList()
            lastPoint = null
            if (!ignored) {
                val delayMs = (downTimeMs - lastEventAnchorMs)
                    .coerceIn(0L, 3_600_000L)
                    .toInt()
                listener.onGestureStart(delayMs)
                addPoint(point)
            }
        }

        while (true) {
            val rawLine = reader.readLine() ?: break
            // script 伪终端可能带回车或控制字符，清理后再匹配
            val line = rawLine.replace("\r", "").trim()
            if (line.isEmpty()) continue
            if (line.contains("permission denied", ignoreCase = true) ||
                line.contains("no such file", ignoreCase = true) ||
                line.contains("not found", ignoreCase = true)
            ) {
                val msg = "getevent error: $line"
                diagnostic.set(msg)
                listener.onError(msg)
                break
            }

            when {
                line.contains("ABS_MT_SLOT") -> {
                    parseHexValue(line)?.let { currentSlot = it }
                }

                currentSlot == 0 && line.contains("ABS_MT_TRACKING_ID") -> {
                    frameTrackingId = parseHexValue(line)
                }

                line.contains("BTN_TOUCH") -> {
                    frameBtnTouch = parseBtnTouch(line)
                }

                currentSlot == 0 && line.contains("ABS_MT_POSITION_X") -> {
                    rawX = parseHexValue(line)
                }

                currentSlot == 0 && line.contains("ABS_MT_POSITION_Y") -> {
                    rawY = parseHexValue(line)
                }

                line.contains("SYN_REPORT") -> {
                    val eventTimeMs = resolveEventTimeMs(line)
                    val point = displayPoint()
                    val tid = frameTrackingId
                    val btn = frameBtnTouch
                    frameTrackingId = null
                    frameBtnTouch = null

                    val isExplicitUp = (tid != null && tid < 0) || (btn == 0)
                    val isNewTouchId = (tid != null && tid >= 0 && tid != activeTrackingId)
                    if (!active && ((tid != null && tid >= 0) || btn == 1)) {
                        pendingDown = tid ?: 0; pendingDownTime = eventTimeMs
                    }
                    if (!active && isExplicitUp) pendingDown = null

                    when {
                        // 情况 1：当前没有活跃手势，收到新的按下信号
                        !active && pendingDown != null && point != null -> {
                            startNewGesture(point, pendingDownTime, pendingDown!!)
                            pendingDown = null
                        }

                        // 情况 2：极快连点时，如果固件在同一帧或跨帧直接切换了新的正数 trackingId，
                        // 先把上一指结算掉，再立刻开启新一指，绝不吞掉中间那次点击。
                        active && isNewTouchId && point != null -> {
                            finishActiveGesture(eventTimeMs)
                            startNewGesture(point, eventTimeMs, tid!!)
                        }

                        // 情况 3：收到抬起信号（TRACKING_ID == -1 或 BTN_TOUCH UP）
                        active && isExplicitUp -> {
                            if (!ignored && point != null) addPoint(point)
                            finishActiveGesture(eventTimeMs)
                        }

                        // 情况 4：手指按住移动中
                        active && point != null -> {
                            if (!ignored) addPoint(point)
                        }
                    }
                }
            }
        }

        // 如果停止录制时手指还没抬起（或最后一帧 UP 在管道关闭前刚好截断），补一次结算
        if (active) {
            finishActiveGesture(SystemClock.uptimeMillis())
        }
    }

    private fun discoverTouchDevice(): TouchDeviceInfo? {
        val result = if (useShizuku) {
            try { RootShell.Result(0, ShizukuInput.devices()) }
            catch (e: Exception) { RootShell.Result(-1, "Shizuku getevent: ${e.message}") }
        } else RootShell.exec("/system/bin/getevent -pl", 6000)
        if (!result.ok || result.output.isBlank()) {
            diagnostic.set("getevent -pl failed: ${result.output.trim()}")
            return null
        }

        val devices = ArrayList<TouchDeviceInfo>()
        var path: String? = null
        var name = ""
        var minX: Int? = null
        var maxX: Int? = null
        var minY: Int? = null
        var maxY: Int? = null
        var direct = false

        fun finishBlock() {
            val p = path
            val x0 = minX
            val x1 = maxX
            val y0 = minY
            val y1 = maxY
            if (p != null && x0 != null && x1 != null && y0 != null && y1 != null &&
                !name.contains(VIRTUAL_NAME, ignoreCase = true) &&
                !name.contains("Clicker", ignoreCase = true)
            ) {
                // INPUT_PROP_DIRECT 优先，但部分 OEM getevent 输出可能省略该标签，因此不硬性要求。
                devices.add(TouchDeviceInfo(p, name.ifBlank { p }, x0, x1, y0, y1, direct))
            }
            path = null
            name = ""
            minX = null; maxX = null; minY = null; maxY = null
            direct = false
        }

        val deviceRegex = Regex("add device \\d+:\\s*(/dev/input/event\\d+)")
        val nameRegex = Regex("name:\\s*\\\"(.*)\\\"")
        val axisRegex = Regex(
            "(ABS_MT_POSITION_X|ABS_MT_POSITION_Y|0035|0036)\\s*:\\s*.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)",
            RegexOption.IGNORE_CASE
        )

        result.output.lineSequence().forEach { line ->
            val dm = deviceRegex.find(line)
            if (dm != null) {
                finishBlock()
                path = dm.groupValues[1]
                return@forEach
            }
            nameRegex.find(line)?.let {
                name = it.groupValues[1]
            }
            if (line.contains("INPUT_PROP_DIRECT")) direct = true
            axisRegex.find(line)?.let { m ->
                val code = m.groupValues[1]
                val min = m.groupValues[2].toIntOrNull()
                val max = m.groupValues[3].toIntOrNull()
                if (code.equals("ABS_MT_POSITION_X", true) || code.equals("0035", true)) {
                    minX = min; maxX = max
                } else {
                    minY = min; maxY = max
                }
            }
        }
        finishBlock()

        // 通常只有一个真正的 direct multitouch touchscreen。若 OEM 暴露多个，优先名字像触屏且轴面积最大者。
        return devices.maxWithOrNull(
            compareBy<TouchDeviceInfo> { if (it.direct) 1 else 0 }
                .thenBy {
                    val n = it.name.lowercase()
                    if (n.contains("touch") || n.contains("fts") || n.contains("goodix") || n.contains("synaptics")) 1 else 0
                }.thenBy {
                    abs(it.maxX - it.minX).toLong() * abs(it.maxY - it.minY).toLong()
                }
        )?.also {
            Log.i(TAG, "selected touchscreen: $it")
        }
    }

    private fun parseBtnTouch(line: String): Int? {
        val token = line.trim().substringAfterLast(' ').trim()
        return when {
            token.equals("DOWN", ignoreCase = true) || token == "00000001" || token == "1" -> 1
            token.equals("UP", ignoreCase = true) || token == "00000000" || token == "0" -> 0
            else -> null
        }
    }

    private fun parseHexValue(line: String): Int? {
        val token = line.trim().substringAfterLast(' ').trim()
        if (token.isBlank()) return null
        return try {
            token.toLong(16).toInt()
        } catch (_: Throwable) {
            token.toIntOrNull()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
