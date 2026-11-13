package com.vicuna.clicker.root.api

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Main-thread only. The window belongs to Clicker, not the calling application. */
internal class ExecutionIndicator(private val context: Context) {
    private val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var window: View? = null

    fun show() {
        if (window != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w("ClickerTrace", "execution overlay skipped: overlay permission missing")
            return
        }
        val density = context.resources.displayMetrics.density
        val size = (8 * density).toInt().coerceAtLeast(8)
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 136, 229))
                setStroke(density.toInt().coerceAtLeast(1), Color.WHITE)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            x = (2 * density).toInt()
            setTitle("Clicker execution indicator")
        }
        try {
            manager.addView(dot, params)
            window = dot
            Log.i("ClickerTrace", "execution overlay added size_dp=8")
        } catch (e: RuntimeException) {
            Log.w("ClickerTrace", "execution overlay unavailable; foreground notification remains active", e)
        }
    }

    fun hide() {
        val dot = window ?: return
        try {
            manager.removeViewImmediate(dot)
            Log.i("ClickerTrace", "execution overlay removed")
        } catch (e: RuntimeException) {
            Log.w("ClickerTrace", "Cannot remove execution overlay", e)
        } finally {
            window = null
        }
    }
}
