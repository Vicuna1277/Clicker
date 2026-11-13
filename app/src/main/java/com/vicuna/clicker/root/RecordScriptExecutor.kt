package com.vicuna.clicker.root

import android.graphics.Path
import android.util.Log
import com.vicuna.clicker.root.bean.Bean
import com.vicuna.clicker.root.bean.RecordScriptCmd

/**
 * 录制脚本回放。
 *
 * 录制时记录的时间语义（见 RootTouchRecorder / RecordScriptViewModel）：
 * - Delay 命令：上一个手势 UP -> 下一个手势 DOWN 的间隔；
 * - Gesture.duration：DOWN -> UP 的按住时长。
 *
 * 所以回放严格按“间隔 -> 手势 -> 间隔 -> 手势”执行，不再额外插入固定等待。
 * 手势完整性由 RecordScriptInterface.dispatchGesture 的阻塞语义保证：
 * 它在 UP 真正发出后才返回。
 */
class RecordScriptExecutor {

    companion object {
        private const val TAG = "RecordScriptExecutor"
        private const val MIN_GESTURE_DURATION_MS = 10
        private const val MIN_RECORDED_TAP_DURATION_MS = 32

        /** 手势起点落在悬浮按钮上时，等待“不可触摸”标志生效的时间。只有这种情况才等待。 */
        const val OVERLAY_SETTLE_MS = 100L

        /** 同一手势重复执行时，两次之间的最小间隔，避免连续 DOWN/UP 被识别为双击。 */
        const val REPEAT_GAP_MS = 50L
    }

    var delayCoefficient = 1.0
    // API callers need a terminal failure instead of a misleading completion.
    var propagateFailures = false
    var onProgress: ((String) -> Unit)? = null
    var recordScriptInterface: RecordScriptInterface? = null

    private fun isRunning(): Boolean = recordScriptInterface?.isRun() == true

    fun run(data: List<RecordScriptCmd>) {
        try {
            for (index in data.indices) {
                if (!isRunning()) return
                val cmd = data[index]
                onProgress?.invoke("record command=${index + 1} type=${cmd.type} begin delay_ms=${cmd.delayed} duration_ms=${cmd.duration} repeats=${cmd.repeatCount}")
                when (cmd.type) {
                    RecordScriptCmd.Type.Delay -> {
                        delay(cmd)
                    }

                    RecordScriptCmd.Type.Gesture -> {
                        val repeatCount = cmd.repeatCount.coerceAtLeast(1)
                        for (repeatIndex in 0 until repeatCount) {
                            if (!isRunning()) return
                            if (repeatIndex > 0 && sleep(REPEAT_GAP_MS)) return
                            onProgress?.invoke("record command=${index + 1} repeat=${repeatIndex + 1}/$repeatCount begin")
                            gesture(index, cmd)
                            onProgress?.invoke("record command=${index + 1} repeat=${repeatIndex + 1}/$repeatCount end")
                        }
                    }

                    else -> {
                    }
                }
                onProgress?.invoke("record command=${index + 1} end")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "record script run aborted", e)
            if (propagateFailures) throw e
        }
    }

    private fun delay(recordScriptCmd: RecordScriptCmd) {
        delay(recordScriptCmd.delayed.toLong())
    }

    /** @return true 表示等待期间脚本被停止。 */
    fun sleep(time: Long): Boolean {
        if (time <= 0) {
            return !isRunning()
        }
        val count = time / 10
        val t = time % 10
        Thread.sleep(t)
        for (i in 0 until count) {
            if (!isRunning()) {
                return true
            }
            Thread.sleep(10)
        }
        return !isRunning()
    }

    fun delay(time: Long): Boolean {
        if (time <= 0) {
            return false
        }
        val adjustedTime = (time * delayCoefficient).toLong()
        return sleep(adjustedTime)
    }

    private fun gesture(position: Int, recordScriptCmd: RecordScriptCmd) {
        if (delay(recordScriptCmd.delayed.toLong())) {
            return
        }

        val iface = recordScriptInterface ?: return
        val path = recordScriptCmd.path
        if (!iface.isRun() || path == null || path.isEmpty()) {
            return
        }

        val first = path[0]
        // 只有起点落在悬浮按钮上时才需要等悬浮窗变为“不可触摸”，否则直接执行。
        val coveredByOverlay = iface.preDispatchGesture(first.x, first.y)
        try {
            if (coveredByOverlay && sleep(OVERLAY_SETTLE_MS)) {
                return
            }

            val stationary = isStationary(path)
            val createPath = createPath(path)
            var duration = (recordScriptCmd.duration * delayCoefficient).toInt()
            val minDuration = if (stationary) {
                MIN_RECORDED_TAP_DURATION_MS
            } else {
                MIN_GESTURE_DURATION_MS
            }
            if (duration < minDuration) {
                duration = minDuration
            }

            // 阻塞到手势完整结束（UP 已发出）。
            iface.dispatchGesture(position, createPath, duration)
        } catch (e: Throwable) {
            Log.w(TAG, "dispatch gesture #$position failed", e)
            if (propagateFailures) throw e
        } finally {
            if (coveredByOverlay) {
                iface.endDispatchGesture()
            }
        }
    }


    private fun isStationary(data: List<Bean>): Boolean {
        if (data.isEmpty()) return false
        val first = data[0]
        return data.all { it.x == first.x && it.y == first.y }
    }

    private fun createPath(data: MutableList<Bean>): Path {
        val path = Path()
        val bean = data[0]
        path.moveTo(bean.x.toFloat(), bean.y.toFloat())

        var hasMovement = false
        for (i in 1 until data.size) {
            val bean2 = data[i]
            if (bean2.x != bean.x || bean2.y != bean.y) {
                hasMovement = true
            }
            path.lineTo(bean2.x.toFloat(), bean2.y.toFloat())
        }

        // ROOT 被动录制会去重重复坐标，因此普通点击通常只有一个 Bean。
        // 只有 moveTo 的 Path 对 PathMeasure/部分后端来说可能是空路径。
        // 补一个远小于 1px 的线段：PathMeasure 能得到非零长度，但后续 toInt()
        // 注入时仍落在同一个像素，不会把点击变成可见滑动。也兼容旧脚本中
        // “多个完全相同坐标”的静止点击/长按。
        if (!hasMovement) {
            path.lineTo(bean.x.toFloat() + 0.01f, bean.y.toFloat())
        }
        return path
    }


    interface RecordScriptInterface {

        fun isRun(): Boolean

        /**
         * 手势开始前调用。若 (x, y) 落在悬浮按钮上，需把悬浮窗设为不可触摸。
         * 必须同步返回结果（可借助 MainThreadSync）。
         * @return true 表示修改了悬浮窗状态，调用方会等待 OVERLAY_SETTLE_MS 让其生效。
         */
        fun preDispatchGesture(x: Int, y: Int): Boolean

        /** 执行手势。必须阻塞到手势完整结束（UP 已发出）后才返回。 */
        fun dispatchGesture(position: Int, path: Path, duration: Int)

        fun endDispatchGesture()


    }
}
