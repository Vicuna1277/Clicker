package com.vicuna.clicker.root.input

import android.content.Context
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ROOT 输入总入口。
 *
 * Android 15+ 优先级：
 * 1. NATIVE_UINPUT：APK 内置 .so，在 uid=0 的 app_process 中直接驱动 /dev/uinput。
 * 2. SYSTEM_UINPUT：ROM 自带 /system/bin/uinput。
 * 3. SHELL_INPUT：input tap/swipe 兼容兜底；曲线只能退化成起点-终点直线。
 */
object RootInput {
    private const val TAG = "RootInput"
    private const val FRAME_MS = 8L
    private const val MIN_TAP_HOLD_MS = 32L
    private const val START_TIMEOUT_MS = 4000L
    private const val WAIT_POLL_MS = 10L
    private const val WAIT_MARGIN_MS = 1000L

    enum class Backend {
        NONE,
        NATIVE_UINPUT,
        SYSTEM_UINPUT,
        SHELL_INPUT,
        SHIZUKU
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var rootGranted = false

    @Volatile
    private var backend = Backend.NONE

    @Volatile
    private var touchDevice: RootTouchDevice? = null

    @Volatile
    private var backendDiagnostic: String = "not initialized"

    private var touchWidth = 0
    private var touchHeight = 0

    // 保持原 AccessibilityService.dispatchGesture 的“提交后立即返回”语义。
    private val injectionExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "root-input-injector").apply { isDaemon = true }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun currentBackend(): Backend = backend
    fun isReady(): Boolean = if (backend == Backend.SHIZUKU) ShizukuInput.ready() else rootGranted && backend != Backend.NONE
    fun diagnosticText(): String = backendDiagnostic

    @Synchronized
    fun ensureReady(context: Context? = null): Boolean {
        context?.let { init(it) }
        val ctx = appContext ?: return false
        if (isReady()) return true

        rootGranted = RootShell.isRootAvailable()
        if (!rootGranted) {
            if (ShizukuInput.connect(ctx)) {
                backend = Backend.SHIZUKU
                backendDiagnostic = "Shizuku MotionEvent input ready; ROOT unavailable"
                return true
            }
            backend = Backend.NONE
            backendDiagnostic = "ROOT / Shizuku unavailable"
            return false
        }

        val dm = ctx.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        // Android 15+ 首选：自己的 root app_process + JNI + /dev/uinput。
        val nativeTouch = RootNativeTouch(ctx, width, height)
        if (nativeTouch.start(START_TIMEOUT_MS)) {
            installTouchBackend(nativeTouch, Backend.NATIVE_UINPUT, width, height)
            backendDiagnostic = nativeTouch.diagnosticText()
            ForegroundAppMonitor.start(ctx)
            Log.i(TAG, "ROOT input backend = NATIVE_UINPUT")
            return true
        }
        val nativeError = nativeTouch.diagnosticText()
        Log.w(TAG, "NATIVE_UINPUT unavailable: $nativeError")

        // 第二优先级：部分 AOSP/ROM 仍带 /system/bin/uinput。
        if (RootShell.hasUInputCommand()) {
            val systemTouch = RootUInputTouch(ctx, width, height)
            if (systemTouch.start(START_TIMEOUT_MS)) {
                installTouchBackend(systemTouch, Backend.SYSTEM_UINPUT, width, height)
                backendDiagnostic = "system /system/bin/uinput ready; native failed: $nativeError"
                ForegroundAppMonitor.start(ctx)
                Log.i(TAG, "ROOT input backend = SYSTEM_UINPUT")
                return true
            }
        }

        // 最后才用 shell input。点击/直线 swipe 仍可用，但连续 Path 无法保持单一触摸流。
        touchDevice = null
        touchWidth = 0
        touchHeight = 0
        backend = Backend.SHELL_INPUT
        backendDiagnostic = "fallback to shell input; native failed: $nativeError"
        ForegroundAppMonitor.start(ctx)
        Log.w(TAG, "ROOT input backend = SHELL_INPUT fallback; $backendDiagnostic")
        return true
    }

    // ---------------------------------------------------------------------
    // 异步版本：提交到注入队列后立即返回（保持旧 AccessibilityService 语义）。
    // ---------------------------------------------------------------------

    fun click(x: Int, y: Int, durationMs: Int = 0): Boolean {
        if (!ensureReady()) return false
        injectionExecutor.execute { doClick(x, y, durationMs) }
        return true
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        if (!ensureReady()) return false
        val d = durationMs.coerceIn(1, 30000)
        injectionExecutor.execute { doSwipe(x1, y1, x2, y2, d) }
        return true
    }

    fun gesture(path: Path, durationMs: Int): Boolean {
        if (!ensureReady()) return false
        val d = durationMs.coerceIn(1, 30000)
        injectionExecutor.execute { doGesture(path, d) }
        return true
    }

    // ---------------------------------------------------------------------
    // 阻塞版本：等到 UP 事件真正发出（手势完整结束）才返回。
    //
    // 脚本执行器用它代替“提交后 sleep(duration) + 额外固定等待”的猜测式写法：
    // 返回时 DOWN/MOVE/UP 已全部写入设备，不会出现手势不完整、也不会多等。
    //
    // keepWaiting 返回 false（例如用户停止脚本）时提前返回，不再等待；
    // 已经开始的注入仍会在注入线程里正常走完并发出 UP，不会留下“按住”状态。
    // ---------------------------------------------------------------------

    fun clickAndWait(
        x: Int,
        y: Int,
        durationMs: Int = 0,
        keepWaiting: () -> Boolean = { true }
    ): Boolean {
        if (!ensureReady()) return false
        val hold = durationMs.toLong().coerceIn(MIN_TAP_HOLD_MS, 30000L)
        return awaitInjection(
            injectionExecutor.submit(Runnable { if (keepWaiting()) doClick(x, y, durationMs) }),
            hold,
            keepWaiting
        )
    }

    fun swipeAndWait(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
        keepWaiting: () -> Boolean = { true }
    ): Boolean {
        if (!ensureReady()) return false
        val d = durationMs.coerceIn(1, 30000)
        return awaitInjection(
            injectionExecutor.submit(Runnable { if (keepWaiting()) doSwipe(x1, y1, x2, y2, d) }),
            d.toLong(),
            keepWaiting
        )
    }

    fun gestureAndWait(
        path: Path,
        durationMs: Int,
        keepWaiting: () -> Boolean = { true }
    ): Boolean {
        if (!ensureReady()) return false
        val d = durationMs.coerceIn(1, 30000)
        return awaitInjection(
            injectionExecutor.submit(Runnable { if (keepWaiting()) doGesture(path, d) }),
            d.toLong().coerceAtLeast(MIN_TAP_HOLD_MS),
            keepWaiting
        )
    }

    /**
     * 等待注入任务完成。
     * 超时上限 = 手势时长 + 设备重建时间（横竖屏切换时可能需要重建虚拟触摸屏）+ 余量。
     * @return true 表示手势已完整执行完毕。
     */
    private fun awaitInjection(
        future: Future<*>,
        expectedMs: Long,
        keepWaiting: () -> Boolean
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + expectedMs + START_TIMEOUT_MS + WAIT_MARGIN_MS
        while (true) {
            try {
                future.get(WAIT_POLL_MS, TimeUnit.MILLISECONDS)
                return true
            } catch (_: TimeoutException) {
                if (!keepWaiting()) return false
                if (SystemClock.uptimeMillis() >= deadline) {
                    Log.w(TAG, "injection wait timeout (${expectedMs}ms expected)")
                    Thread.getAllStackTraces().forEach { (thread,stack) ->
                        if(thread.name=="root-input-injector")
                            Log.w("ClickerTrace","TOUCH_TIMEOUT thread=${thread.name} state=${thread.state}\n${stack.joinToString("\n")}")
                    }
                    return false
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (t: Throwable) {
                Log.w(TAG, "injection task failed", t)
                return false
            }
        }
    }

    // ---------------------------------------------------------------------
    // 实际注入逻辑（只在 injectionExecutor 线程中运行）
    // ---------------------------------------------------------------------

    private fun doClick(x: Int, y: Int, durationMs: Int) {
        when (backend) {
            Backend.NATIVE_UINPUT,
            Backend.SYSTEM_UINPUT -> {
                val touch = ensureTouchForCurrentDisplay() ?: return
                // 统一至少按住 MIN_TAP_HOLD_MS：原先 <=30ms 走 touch.tap() 只按住约 4ms，
                // 部分 InputReader/目标 App 会吞掉这种过短的点击。
                val hold = durationMs.toLong().coerceIn(MIN_TAP_HOLD_MS, 30000L)
                val id = nextTrackingId()
                Log.i("ClickerTrace", "touch=$id PREPARE_BEGIN")
                touch.prepareGesture()
                Log.i("ClickerTrace", "touch=$id PREPARE_END")
                try {
                    Log.i("ClickerTrace", "touch=$id DOWN_BEGIN")
                    touch.down(x, y, id)
                    Log.i("ClickerTrace", "touch=$id DOWN_COMMAND_SENT")
                    sleepUntil(SystemClock.uptimeMillis() + hold)
                } finally {
                    Log.i("ClickerTrace", "touch=$id UP_BEGIN")
                    touch.up(id)
                    Log.i("ClickerTrace", "touch=$id UP_COMMAND_SENT")
                }
            }

            Backend.SHELL_INPUT -> {
                if (durationMs <= 30) {
                    RootShell.exec("input touchscreen tap $x $y", 5000)
                } else {
                    val d = durationMs.coerceIn(31, 30000)
                    RootShell.exec(
                        "input touchscreen swipe $x $y $x $y $d",
                        d + 5000L
                    )
                }
            }

            Backend.SHIZUKU -> ShizukuInput.line(x,y,x,y,durationMs.coerceIn(32,30000))
            Backend.NONE -> Unit
        }
    }

    private fun doSwipe(x1: Int, y1: Int, x2: Int, y2: Int, d: Int) {
        when (backend) {
            Backend.NATIVE_UINPUT,
            Backend.SYSTEM_UINPUT -> injectLineTouch(x1, y1, x2, y2, d)

            Backend.SHELL_INPUT -> RootShell.exec(
                "input touchscreen swipe $x1 $y1 $x2 $y2 $d",
                d + 5000L
            )

            Backend.SHIZUKU -> ShizukuInput.line(x1,y1,x2,y2,d)
            Backend.NONE -> Unit
        }
    }

    private fun doGesture(path: Path, d: Int) {
        when (backend) {
            Backend.NATIVE_UINPUT,
            Backend.SYSTEM_UINPUT -> injectPathTouch(path, d)

            Backend.SHELL_INPUT -> injectPathShellFallback(path, d)
            Backend.SHIZUKU -> ShizukuInput.gesture(path,d)
            Backend.NONE -> Unit
        }
    }

    @Synchronized
    fun close() {
        ShizukuInput.close()
        try {
            touchDevice?.close()
        } catch (_: Throwable) {
        }
        touchDevice = null
        touchWidth = 0
        touchHeight = 0
        backend = Backend.NONE
        rootGranted = false
        backendDiagnostic = "closed"
        ForegroundAppMonitor.stop()
    }

    private fun injectLineTouch(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        injectPathTouch(path, durationMs)
    }

    /**
     * 关键：整个 Path 只发送一次 DOWN 和一次 UP，中间全部是 MOVE。
     * 所以曲线不会像多条 shell swipe 那样被拆成很多独立手势。
     */
    private fun injectPathTouch(path: Path, durationMs: Int) {
        val touch = ensureTouchForCurrentDisplay() ?: return
        val measure = PathMeasure(path, false)
        val length = measure.length
        val pos = FloatArray(2)
        if (!measure.getPosTan(0f, pos, null)) return

        val id = nextTrackingId()
        Log.i("ClickerTrace", "touch=$id PREPARE_BEGIN duration_ms=$durationMs backend=$backend")
        touch.prepareGesture()
        Log.i("ClickerTrace", "touch=$id PREPARE_END")
        try {
            Log.i("ClickerTrace", "touch=$id DOWN_BEGIN")
            touch.down(pos[0].toInt(), pos[1].toInt(), id)
            Log.i("ClickerTrace", "touch=$id DOWN_COMMAND_SENT")
            val startedAt = SystemClock.uptimeMillis()
            // 录制的普通点击通常只有一个点，PathMeasure.length == 0。
            // 旧实现会在 DOWN 后立即 return，并在 finally 立刻 UP，导致 ROOT/uinput
            // 看到一个几乎 0ms 的触摸；部分 InputReader/目标 App 会直接吞掉。
            // AccessibilityService 的 StrokeDescription 会保留 duration 语义，因此 ROOT
            // 后端也必须对“零长度手势”显式保持按下时长。
            if (length <= 0f) {
                val holdMs = durationMs.toLong().coerceAtLeast(MIN_TAP_HOLD_MS)
                sleepUntil(startedAt + holdMs)
                return
            }

            val frames = (durationMs.toLong() / FRAME_MS).coerceAtLeast(1L).toInt()

            for (i in 1..frames) {
                val targetTime = startedAt + (durationMs.toLong() * i / frames)
                sleepUntil(targetTime)
                val fraction = i.toFloat() / frames.toFloat()
                if (measure.getPosTan(length * fraction, pos, null)) {
                    if(i==1 || i==frames)Log.i("ClickerTrace", "touch=$id MOVE_BEGIN frame=$i/$frames")
                    touch.move(pos[0].toInt(), pos[1].toInt())
                    if(i==1 || i==frames)Log.i("ClickerTrace", "touch=$id MOVE_COMMAND_SENT frame=$i/$frames")
                }
            }
        } finally {
            Log.i("ClickerTrace", "touch=$id UP_BEGIN")
            touch.up(id)
            Log.i("ClickerTrace", "touch=$id UP_COMMAND_SENT")
        }
    }

    private fun injectPathShellFallback(path: Path, durationMs: Int) {
        val measure = PathMeasure(path, false)
        val length = measure.length
        val start = FloatArray(2)
        val end = FloatArray(2)
        if (!measure.getPosTan(0f, start, null)) return
        if (!measure.getPosTan(length, end, null)) {
            end[0] = start[0]
            end[1] = start[1]
        }
        RootShell.exec(
            "input touchscreen swipe ${start[0].toInt()} ${start[1].toInt()} ${end[0].toInt()} ${end[1].toInt()} $durationMs",
            durationMs + 5000L
        )
    }

    @Synchronized
    private fun ensureTouchForCurrentDisplay(): RootTouchDevice? {
        val ctx = appContext ?: return null
        val activeBackend = backend
        if (activeBackend != Backend.NATIVE_UINPUT && activeBackend != Backend.SYSTEM_UINPUT) {
            return null
        }

        val dm = ctx.resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val current = touchDevice

        if (current != null && current.isReady() && width == touchWidth && height == touchHeight) {
            return current
        }

        try {
            current?.close()
        } catch (_: Throwable) {
        }

        val replacement: RootTouchDevice = when (activeBackend) {
            Backend.NATIVE_UINPUT -> RootNativeTouch(ctx, width, height)
            Backend.SYSTEM_UINPUT -> RootUInputTouch(ctx, width, height)
            else -> return null
        }

        if (replacement.start(START_TIMEOUT_MS)) {
            touchDevice = replacement
            touchWidth = width
            touchHeight = height
            return replacement
        }

        Log.e(TAG, "$activeBackend failed after display change; trying fallback")

        // 横竖屏重建设备失败时，按优先级继续降级，而不是让脚本静默失效。
        if (activeBackend == Backend.NATIVE_UINPUT && RootShell.hasUInputCommand()) {
            val systemTouch = RootUInputTouch(ctx, width, height)
            if (systemTouch.start(START_TIMEOUT_MS)) {
                installTouchBackend(systemTouch, Backend.SYSTEM_UINPUT, width, height)
                backendDiagnostic = "native recreate failed; switched to system uinput"
                return systemTouch
            }
        }

        touchDevice = null
        touchWidth = 0
        touchHeight = 0
        backend = Backend.SHELL_INPUT
        backendDiagnostic = "$activeBackend recreate failed; switched to shell input"
        return null
    }

    private fun installTouchBackend(
        touch: RootTouchDevice,
        newBackend: Backend,
        width: Int,
        height: Int
    ) {
        try {
            touchDevice?.takeIf { it !== touch }?.close()
        } catch (_: Throwable) {
        }
        touchDevice = touch
        touchWidth = width
        touchHeight = height
        backend = newBackend
    }

    private fun nextTrackingId(): Int {
        return (SystemClock.uptimeMillis() and 0x7fff).toInt().coerceAtLeast(1)
    }

    private fun sleepUntil(targetUptime: Long) {
        while (true) {
            val remaining = targetUptime - SystemClock.uptimeMillis()
            if (remaining <= 0) return
            try {
                Thread.sleep(remaining.coerceAtMost(20L))
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}
