package com.vicuna.clicker.root.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.util.Log
import android.view.InputDevice
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 15+ 主后端：
 *
 * App -> su -> app_process(uid=0) -> libclicker_uinput.so -> /dev/uinput
 *
 * 为什么不直接在 App JNI 里 open("/dev/uinput")？
 * 因为 JNI 仍运行在普通应用 uid 下，不会因为设备已经 ROOT 就自动变成 root。
 * 所以这里让 app_process 在 su 创建的 uid=0 进程中加载同一个 APK 的 dex 和 native .so。
 */
class RootNativeTouch(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) : RootTouchDevice {

    companion object {
        private const val TAG = "RootNativeTouch"
        const val DEVICE_NAME = "Clicker Root Virtual Touchscreen"
        private const val DAEMON_CLASS = "com.vicuna.clicker.root.input.RootNativeDaemon"
    }

    @Volatile
    private var ready = false
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val trackingId = AtomicInteger(1)
    private val diagnostic = AtomicReference("")
    private val displayMapper = RootDisplayMapper(context, screenWidth, screenHeight)

    private var gestureTransform: RootDisplayMapper.Transform? = null
    override fun prepareGesture() { gestureTransform=displayMapper.snapshot() }
    override fun isReady(): Boolean = ready

    fun diagnosticText(): String = diagnostic.get()

    override fun start(timeoutMs: Long): Boolean {
        synchronized(this) {
            if (ready) return true
            if (screenWidth <= 0 || screenHeight <= 0) {
                diagnostic.set("invalid display size ${screenWidth}x${screenHeight}")
                return false
            }
        }

        val apkPath = context.applicationInfo.sourceDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        if (nativeLibDir.isNullOrBlank()) {
            diagnostic.set("applicationInfo.nativeLibraryDir is empty")
            return false
        }

        val nativeLib = File(nativeLibDir, "libclicker_uinput.so")
        if (!nativeLib.isFile) {
            diagnostic.set("native helper not extracted: ${nativeLib.absolutePath}")
            Log.e(TAG, diagnostic.get())
            return false
        }

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val addedLatch = CountDownLatch(1)
        val nativeReadyLatch = CountDownLatch(1)

        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                val device = InputDevice.getDevice(deviceId)
                if (device?.name == DEVICE_NAME) {
                    addedLatch.countDown()
                }
            }

            override fun onInputDeviceRemoved(deviceId: Int) = Unit
            override fun onInputDeviceChanged(deviceId: Int) = Unit
        }

        return try {
            inputManager.registerInputDeviceListener(listener, Handler(context.mainLooper))

            // 如果上一次异常退出后 InputReader 仍短暂保留同名设备，也允许轮询识别。
            if (hasInputDevice()) addedLatch.countDown()

            val command = buildString {
                append("CLASSPATH=")
                append(shellQuote(apkPath))
                append(" exec app_process /system/bin ")
                append(DAEMON_CLASS)
                append(' ')
                append(displayMapper.touchWidth)
                append(' ')
                append(displayMapper.touchHeight)
                append(' ')
                append(shellQuote(nativeLib.absolutePath))
            }

            val p = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            synchronized(this) {
                process = p
                writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            }

            Thread({
                try {
                    InputStreamReader(p.inputStream).buffered().useLines { lines ->
                        lines.forEach { line ->
                            val text = line.trim()
                            if (text.isNotEmpty()) {
                                Log.d(TAG, "helper: $text")
                                when {
                                    text == "READY" -> nativeReadyLatch.countDown()
                                    text.startsWith("ERROR") -> diagnostic.set(text)
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (ready) {
                        Log.w(TAG, "native helper output reader stopped", t)
                    }
                }
            }, "root-native-uinput-output").apply {
                isDaemon = true
                start()
            }

            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val nativeOk = nativeReadyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            val remainingNs = deadline - System.nanoTime()
            val deviceOk = nativeOk && (
                hasInputDevice() ||
                    (remainingNs > 0 && addedLatch.await(remainingNs, TimeUnit.NANOSECONDS))
                )

            if (nativeOk && deviceOk) {
                ready = true
                diagnostic.set("native /dev/uinput ready")
                Log.i(TAG, "native /dev/uinput touchscreen ready")
                true
            } else {
                if (diagnostic.get().isBlank()) {
                    diagnostic.set(
                        when {
                            !nativeOk -> "native helper READY timeout"
                            else -> "InputManager did not detect virtual touchscreen"
                        }
                    )
                }
                Log.e(TAG, "start failed: ${diagnostic.get()}")
                close()
                false
            }
        } catch (t: Throwable) {
            diagnostic.set("start exception: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            Log.e(TAG, "start native helper failed", t)
            close()
            false
        } finally {
            try {
                inputManager.unregisterInputDeviceListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    override fun close() {
        synchronized(this) {
            ready = false
            try {
                writer?.apply {
                    write("Q")
                    newLine()
                    flush()
                }
            } catch (_: Throwable) {
            }
            try {
                writer?.close()
            } catch (_: Throwable) {
            }
            writer = null

            val p = process
            process = null
            if (p != null) {
                try {
                    if (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                        p.destroy()
                    }
                } catch (_: Throwable) {
                    try {
                        p.destroy()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override fun tap(x: Int, y: Int) {
        prepareGesture()
        val id = nextTrackingId()
        down(x, y, id)
        // 极短 DOWN/UP 在部分 InputReader/应用里可能被合并；4ms 足够保持点击可靠，
        // 又不会明显拖慢普通连点。
        try {
            Thread.sleep(4)
        } catch (_: InterruptedException) {
        }
        up(id)
    }

    override fun down(x: Int, y: Int, id: Int) {
        val p = (gestureTransform ?: displayMapper.snapshot()).toTouchSpace(x, y)
        send("D ${p.x} ${p.y} $id")
    }

    override fun move(x: Int, y: Int) {
        val p = (gestureTransform ?: displayMapper.snapshot()).toTouchSpace(x, y)
        send("M ${p.x} ${p.y}")
    }

    override fun up(id: Int) {
        send("U $id")
    }

    @Synchronized
    private fun send(command: String) {
        check(ready) { "Native touch backend is not ready" }
        try {
            val w = checkNotNull(writer) { "Native touch writer is missing" }
            w.write(command)
            w.newLine()
            w.flush()
        } catch (t: Throwable) {
            diagnostic.set("write failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}")
            ready = false
            Log.e(TAG, "write helper command failed: $command", t)
            throw IllegalStateException("Native touch command ${command.take(1)} failed",t)
        }
    }

    private fun hasInputDevice(): Boolean {
        return InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.name == DEVICE_NAME
        }
    }

    private fun nextTrackingId(): Int {
        while (true) {
            val current = trackingId.getAndIncrement()
            if (current in 1..60000) return current
            trackingId.compareAndSet(current + 1, 1)
        }
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}
