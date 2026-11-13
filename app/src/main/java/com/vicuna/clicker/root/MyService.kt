package com.vicuna.clicker.root

import android.content.Context
import android.graphics.Path
import androidx.lifecycle.MutableLiveData
import com.vicuna.clicker.root.input.ForegroundAppMonitor
import com.vicuna.clicker.root.input.RootInput

/**
 * 兼容旧代码的门面。
 *
 * 注意：它已经不是 AccessibilityService。
 * 等 ROOT 版本稳定后，建议把这个类整体重命名为 InputRuntime / InputController。
 */
class MyService private constructor() {

    val pkgNameMutableLiveData: MutableLiveData<String>
        get() = ForegroundAppMonitor.packageNameLiveData

    fun dispatchGestureClick(x: Float, y: Float) {
        RootInput.click(x.toInt(), y.toInt(), 0)
    }

    fun dispatchGestureClick(x: Float, y: Float, duration: Int) {
        RootInput.click(x.toInt(), y.toInt(), duration)
    }

    fun dispatchGesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Int) {
        RootInput.swipe(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), duration)
    }

    fun dispatchGesture(path: Path, duration: Int) {
        RootInput.gesture(path, duration)
    }

    // 阻塞版本：手势完整执行（UP 已发出）后才返回，供脚本执行器使用。
    // keepWaiting 返回 false 时（脚本被停止）提前返回。

    fun dispatchGestureClickAndWait(
        x: Float,
        y: Float,
        duration: Int,
        keepWaiting: () -> Boolean = { true }
    ): Boolean = RootInput.clickAndWait(x.toInt(), y.toInt(), duration, keepWaiting)

    fun dispatchGestureAndWait(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        duration: Int,
        keepWaiting: () -> Boolean = { true }
    ): Boolean = RootInput.swipeAndWait(
        x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt(), duration, keepWaiting
    )

    fun dispatchGestureAndWait(
        path: Path,
        duration: Int,
        keepWaiting: () -> Boolean = { true }
    ): Boolean = RootInput.gestureAndWait(path, duration, keepWaiting)

    companion object {
        @JvmField
        val myService: MyService = MyService()

        @JvmStatic
        fun init(context: Context) {
            RootInput.init(context.applicationContext)
        }

        @JvmStatic
        fun isStart(): Boolean = RootInput.isReady()

        @JvmStatic
        fun ensureRoot(context: Context): Boolean {
            RootInput.init(context.applicationContext)
            return RootInput.ensureReady()
        }

        @JvmStatic
        fun backendName(): String = RootInput.currentBackend().name

        @JvmStatic
        fun backendDiagnostic(): String = RootInput.diagnosticText()
    }
}
