package com.vicuna.clicker.root

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在主线程执行一段代码，并在当前（脚本）线程等待它的结果。
 *
 * 用于脚本执行前判断“手势起点是否落在悬浮按钮上”：
 * 判断和修改悬浮窗触摸标志必须在主线程做，但脚本线程需要立刻知道结果，
 * 才能决定是否要等待悬浮窗状态生效——不需要时就完全不等。
 */
object MainThreadSync {
    private const val TAG = "MainThreadSync"
    private const val DEFAULT_TIMEOUT_MS = 300L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * @return block 的返回值；超时或异常时返回 false。
     */
    fun call(timeoutMs: Long = DEFAULT_TIMEOUT_MS, block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runSafely(block)
        }
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result.set(runSafely(block))
            } finally {
                latch.countDown()
            }
        }
        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "main thread call timeout (${timeoutMs}ms)")
            }
            result.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun runSafely(block: () -> Boolean): Boolean {
        return try {
            block()
        } catch (t: Throwable) {
            Log.w(TAG, "main thread block failed", t)
            false
        }
    }
}
