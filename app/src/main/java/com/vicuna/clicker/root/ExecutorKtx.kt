package com.vicuna.clicker.root

import android.util.Log

class ScriptInterfaceImpl(var isRunning: () -> Boolean) : ScriptExecutor.ScriptInterFace {
    var delayCoefficient: Float = 1F


    override fun isRun(): Boolean {
        return isRunning()
    }

    // endregion

    // region ScriptInterFace

    fun sleep(time: Long): Boolean {
        if (time <= 0) {
            return !isRun
        }
        if (!isRun) {
            return true
        }

        val count = time / 10
        val t = time % 10
        Thread.sleep(t)
        for (i in 0 until count) {
            if (!isRun) {
                return true
            }
            Thread.sleep(10)
        }
        return !isRun
    }


    override fun delayedCmd(time: Int) {
        if (time <= 0) {
            return
        }
        if (!isRun) {
            return
        }
        val adjustedTime = (time * delayCoefficient).toLong()
        sleep(adjustedTime)
    }

    override fun clickCMD(x0: Int, y0: Int, mDuration: Int) {
        if (!isRun) return
        val service = MyService.myService

        val d = (mDuration * delayCoefficient).toInt().coerceIn(32, 30000)
        try {
            service.dispatchGestureClickAndWait(x0.toFloat(), y0.toFloat(), d) { isRun }
        } catch (e: Exception) {
            Log.w("ScriptInterfaceImpl", "clickCMD failed", e)
        }
    }

    override fun gestureCMD(x0: Int, y0: Int, x1: Int, y1: Int, mDuration: Int) {
        if (!isRun) return
        val service = MyService.myService

        val d = (mDuration * delayCoefficient).toInt().coerceIn(30, 30000)
        try {
            service.dispatchGestureAndWait(
                x0.toFloat(),
                y0.toFloat(),
                x1.toFloat(),
                y1.toFloat(),
                d
            ) { isRun }
        } catch (e: Exception) {
            Log.w("ScriptInterfaceImpl", "gestureCMD failed", e)
        }
    }
}
