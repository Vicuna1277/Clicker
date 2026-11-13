package com.vicuna.clicker.root.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.util.Log
import android.view.InputDevice
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 长驻 /system/bin/uinput - 进程。
 *
 * 配置采用 Android 12 AOSP uinput 已支持的“数字 pseudo-JSON”格式，
 * 避免依赖后续 Android 才增加的字符串事件名解析。
 */
class RootUInputTouch(
    private val context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int
) : RootTouchDevice {
    companion object {
        private const val TAG = "RootUInputTouch"
        const val DEVICE_NAME = "Clicker Virtual Touchscreen"
        private const val DEVICE_ID = 1

        // linux/input-event-codes.h
        private const val EV_SYN = 0
        private const val EV_KEY = 1
        private const val EV_ABS = 3
        private const val SYN_REPORT = 0
        private const val BTN_TOUCH = 330
        private const val ABS_MT_SLOT = 47
        private const val ABS_MT_TOUCH_MAJOR = 48
        private const val ABS_MT_POSITION_X = 53
        private const val ABS_MT_POSITION_Y = 54
        private const val ABS_MT_TOOL_TYPE = 55
        private const val ABS_MT_TRACKING_ID = 57

        // linux/uinput.h ioctl numbers used by Android's uinput command format
        private const val UI_SET_EVBIT = 100
        private const val UI_SET_KEYBIT = 101
        private const val UI_SET_ABSBIT = 103
        private const val UI_SET_PROPBIT = 110
        private const val INPUT_PROP_DIRECT = 1
    }

    @Volatile
    private var ready = false
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val trackingId = AtomicInteger(1)
    private val displayMapper = RootDisplayMapper(context, screenWidth, screenHeight)

    private var gestureTransform: RootDisplayMapper.Transform? = null
    override fun prepareGesture() { gestureTransform=displayMapper.snapshot() }
    override fun isReady(): Boolean = ready

    @Synchronized
    override fun start(timeoutMs: Long): Boolean {
        if (ready) return true
        if (screenWidth <= 0 || screenHeight <= 0) return false

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
        val addedLatch = CountDownLatch(1)
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

            val p = ProcessBuilder("su", "-c", "exec /system/bin/uinput -")
                .redirectErrorStream(true)
                .start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream))

            Thread({
                try {
                    InputStreamReader(p.inputStream).buffered().use { reader ->
                        val buf = CharArray(1024)
                        while (true) {
                            val n = reader.read(buf)
                            if (n <= 0) break
                            Log.d(TAG, String(buf, 0, n).trim())
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "uinput output reader stopped", t)
                }
            }, "uinput-output-reader").apply {
                isDaemon = true
                start()
            }

            writeRaw(registerCommand())
            val ok = addedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            ready = ok
            if (!ok) {
                Log.e(TAG, "virtual touchscreen registration timeout")
                close()
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "start uinput failed", t)
            close()
            false
        } finally {
            try { inputManager.unregisterInputDeviceListener(listener) } catch (_: Throwable) {}
        }
    }

    @Synchronized
    override fun close() {
        ready = false
        try { writer?.flush() } catch (_: Throwable) {}
        try { writer?.close() } catch (_: Throwable) {}
        writer = null
        try { process?.destroy() } catch (_: Throwable) {}
        process = null
    }

    override fun tap(x: Int, y: Int) {
        prepareGesture()
        val id = nextTrackingId()
        down(x, y, id)
        up(id)
    }

    override fun down(x: Int, y: Int, id: Int) {
        val p = (gestureTransform ?: displayMapper.snapshot()).toTouchSpace(x, y)
        inject(
            intArrayOf(
                EV_ABS, ABS_MT_SLOT, 0,
                EV_KEY, BTN_TOUCH, 1,
                EV_ABS, ABS_MT_TRACKING_ID, id,
                EV_ABS, ABS_MT_TOOL_TYPE, 0,
                EV_ABS, ABS_MT_TOUCH_MAJOR, 5,
                EV_ABS, ABS_MT_POSITION_X, p.x,
                EV_ABS, ABS_MT_POSITION_Y, p.y,
                EV_SYN, SYN_REPORT, 0
            )
        )
    }

    override fun move(x: Int, y: Int) {
        val p = (gestureTransform ?: displayMapper.snapshot()).toTouchSpace(x, y)
        inject(
            intArrayOf(
                EV_ABS, ABS_MT_SLOT, 0,
                EV_ABS, ABS_MT_POSITION_X, p.x,
                EV_ABS, ABS_MT_POSITION_Y, p.y,
                EV_SYN, SYN_REPORT, 0
            )
        )
    }

    override fun up(id: Int) {
        inject(
            intArrayOf(
                EV_ABS, ABS_MT_SLOT, 0,
                EV_ABS, ABS_MT_TRACKING_ID, -1,
                EV_KEY, BTN_TOUCH, 0,
                EV_SYN, SYN_REPORT, 0
            )
        )
    }

    @Synchronized
    private fun inject(events: IntArray) {
        check(ready) { "System uinput backend is not ready" }
        val eventText = events.joinToString(",")
        writeRaw("{\"id\":$DEVICE_ID,\"command\":\"inject\",\"events\":[$eventText]}")
    }

    @Synchronized
    private fun writeRaw(command: String) {
        val w = checkNotNull(writer) { "System uinput writer is missing" }
        w.write(command)
        w.newLine()
        w.flush()
    }

    private fun nextTrackingId(): Int {
        while (true) {
            val current = trackingId.getAndIncrement()
            if (current in 1..60000) return current
            trackingId.compareAndSet(current + 1, 1)
        }
    }

    private fun registerCommand(): String {
        val w = (displayMapper.touchWidth - 1).coerceAtLeast(1)
        val h = (displayMapper.touchHeight - 1).coerceAtLeast(1)
        return """
            {
              "id":$DEVICE_ID,
              "command":"register",
              "name":"$DEVICE_NAME",
              "vid":6353,
              "pid":4660,
              "bus":"usb",
              "configuration":[
                {"type":$UI_SET_EVBIT,"data":[$EV_KEY,$EV_ABS]},
                {"type":$UI_SET_ABSBIT,"data":[$ABS_MT_SLOT,$ABS_MT_TOUCH_MAJOR,$ABS_MT_POSITION_X,$ABS_MT_POSITION_Y,$ABS_MT_TRACKING_ID,$ABS_MT_TOOL_TYPE]},
                {"type":$UI_SET_PROPBIT,"data":[$INPUT_PROP_DIRECT]},
                {"type":$UI_SET_KEYBIT,"data":[$BTN_TOUCH]}
              ],
              "abs_info":[
                {"code":$ABS_MT_SLOT,"info":{"value":0,"minimum":0,"maximum":9,"fuzz":0,"flat":0,"resolution":0}},
                {"code":$ABS_MT_TOUCH_MAJOR,"info":{"value":0,"minimum":0,"maximum":31,"fuzz":0,"flat":0,"resolution":0}},
                {"code":$ABS_MT_POSITION_X,"info":{"value":0,"minimum":0,"maximum":$w,"fuzz":0,"flat":0,"resolution":0}},
                {"code":$ABS_MT_POSITION_Y,"info":{"value":0,"minimum":0,"maximum":$h,"fuzz":0,"flat":0,"resolution":0}},
                {"code":$ABS_MT_TRACKING_ID,"info":{"value":0,"minimum":0,"maximum":65535,"fuzz":0,"flat":0,"resolution":0}},
                {"code":$ABS_MT_TOOL_TYPE,"info":{"value":0,"minimum":0,"maximum":2,"fuzz":0,"flat":0,"resolution":0}}
              ]
            }
        """.trimIndent().replace("\n", "")
    }
}
