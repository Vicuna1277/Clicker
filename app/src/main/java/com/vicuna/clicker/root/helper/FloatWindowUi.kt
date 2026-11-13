package com.vicuna.clicker.root.helper

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.vicuna.clicker.root.R
import com.vicuna.clicker.root.SmallWindowView
import kotlin.math.abs
import kotlin.math.roundToInt

/** 停止后只做一个很短的防抖，避免同一次抬手/边缘触摸马上又启动。 */
const val FLOAT_RESTART_DEBOUNCE_MS = 250L

private const val FLOAT_POSITION_PREFS = "float_window_positions_v32"

/** 统一“开始/停止”默认悬浮按钮的文字、边框。 */
fun TextView.setRunFloatState(
    running: Boolean,
    startText: String? = null,
    stopText: String = "停止"
) {
    if (!startText.isNullOrBlank()) {
        setTag(R.id.tag_float_start_text, startText)
    }
    val idleText = (getTag(R.id.tag_float_start_text) as? String)
        ?.takeIf { it.isNotBlank() }
        ?: "开始"
    text = if (running) stopText else idleText
    setTextColor(Color.WHITE)
    setBackgroundResource(if (running) R.drawable.float_run_stop_bg else R.drawable.float_run_start_bg)
}

private fun View.restoreFloatPosition(
    windowManager: WindowManager,
    layoutParams: WindowManager.LayoutParams,
    positionKey: String?,
    defaultWidth: Int,
    defaultHeight: Int,
    initialTopPx: Int
) {
    val bounds = windowManager.maximumWindowMetrics.bounds
    val availableX = (bounds.width() - defaultWidth).coerceAtLeast(0)
    val availableY = (bounds.height() - defaultHeight).coerceAtLeast(0)

    if (positionKey.isNullOrBlank()) {
        if (layoutParams.x == 0 && layoutParams.y == 0) {
            layoutParams.x = availableX / 2
            layoutParams.y = initialTopPx.coerceIn(0, availableY)
        }
        return
    }

    val prefs = context.applicationContext.getSharedPreferences(FLOAT_POSITION_PREFS, Context.MODE_PRIVATE)
    val hasPosition = prefs.getBoolean("${positionKey}_saved", false)
    if (hasPosition) {
        val xRatio = prefs.getFloat("${positionKey}_x", 0.5f).coerceIn(0f, 1f)
        val yRatio = prefs.getFloat("${positionKey}_y", 0f).coerceIn(0f, 1f)
        layoutParams.x = (availableX * xRatio).roundToInt().coerceIn(0, availableX)
        layoutParams.y = (availableY * yRatio).roundToInt().coerceIn(0, availableY)
    } else {
        layoutParams.x = availableX / 2
        layoutParams.y = initialTopPx.coerceIn(0, availableY)
    }
}

private fun View.saveFloatPosition(
    windowManager: WindowManager,
    layoutParams: WindowManager.LayoutParams,
    positionKey: String?
) {
    if (positionKey.isNullOrBlank()) return

    val bounds = windowManager.maximumWindowMetrics.bounds
    val viewWidth = width.coerceAtLeast(1)
    val viewHeight = height.coerceAtLeast(1)
    val availableX = (bounds.width() - viewWidth).coerceAtLeast(0)
    val availableY = (bounds.height() - viewHeight).coerceAtLeast(0)
    val xRatio = if (availableX == 0) 0f else layoutParams.x.toFloat() / availableX.toFloat()
    val yRatio = if (availableY == 0) 0f else layoutParams.y.toFloat() / availableY.toFloat()

    context.applicationContext.getSharedPreferences(FLOAT_POSITION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean("${positionKey}_saved", true)
        .putFloat("${positionKey}_x", xRatio.coerceIn(0f, 1f))
        .putFloat("${positionKey}_y", yRatio.coerceIn(0f, 1f))
        .apply()
}

/**
 * 给默认开始/停止按钮绑定“点击执行 + 拖动移动”。
 *
 * 关键规则：点击只在 ACTION_UP 且没有发生拖动时触发。因此运行中的红色“停止”按钮也可以安全拖动，
 * 不再在 ACTION_DOWN 时误停止。
 *
 * positionKey 不为空时保存相对位置；横竖屏变化后按可用屏幕比例恢复。
 */
fun SmallWindowView.bindMovableRunButton(
    textView: TextView,
    windowManager: WindowManager,
    layoutParams: WindowManager.LayoutParams,
    onClick: () -> Unit,
    initialTopDp: Int = 24,
    positionKey: String? = null,
    onPositionChanged: () -> Unit = {}
) {
    enableMove = false
    val density = resources.displayMetrics.density
    val defaultWidth = (80f * density).toInt().coerceAtLeast(1)
    val defaultHeight = (40f * density).toInt().coerceAtLeast(1)
    layoutParams.gravity = Gravity.TOP or Gravity.START
    restoreFloatPosition(
        windowManager = windowManager,
        layoutParams = layoutParams,
        positionKey = positionKey,
        defaultWidth = defaultWidth,
        defaultHeight = defaultHeight,
        initialTopPx = (initialTopDp * density).toInt().coerceAtLeast(0)
    )

    val dragThreshold = (8f * density).coerceAtLeast(8f)
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var moved = false

    textView.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                moved = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!moved && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                    moved = true
                }
                if (moved) {
                    val screen = windowManager.maximumWindowMetrics.bounds
                    val viewWidth = width.takeIf { it > 0 } ?: defaultWidth
                    val viewHeight = height.takeIf { it > 0 } ?: defaultHeight
                    layoutParams.x = (startX + dx.toInt())
                        .coerceIn(0, (screen.width() - viewWidth).coerceAtLeast(0))
                    layoutParams.y = (startY + dy.toInt())
                        .coerceIn(0, (screen.height() - viewHeight).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(this, layoutParams)
                        onPositionChanged()
                    } catch (_: Throwable) {
                    }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (moved) {
                    saveFloatPosition(windowManager, layoutParams, positionKey)
                } else {
                    onClick()
                }
                onPositionChanged()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (moved) saveFloatPosition(windowManager, layoutParams, positionKey)
                onPositionChanged()
                true
            }

            else -> true
        }
    }
}

/**
 * window_a 专用的新坐标选择拖动逻辑。
 * 使用 TOP|START + WindowMetrics，不再使用 SmallWindowView 旧的中心原点/DisplayMetrics 算法。
 */
fun SmallWindowView.bindMovableCoordinatePicker(
    windowManager: WindowManager,
    layoutParams: WindowManager.LayoutParams,
    initialXRatio: Float = 0.5f,
    initialYRatio: Float = 0.5f
) {
    enableMove = false
    layoutParams.gravity = Gravity.TOP or Gravity.START

    val density = resources.displayMetrics.density
    val defaultSize = (50f * density).toInt().coerceAtLeast(1)
    val bounds = windowManager.maximumWindowMetrics.bounds
    val availableX = (bounds.width() - defaultSize).coerceAtLeast(0)
    val availableY = (bounds.height() - defaultSize).coerceAtLeast(0)
    layoutParams.x = (availableX * initialXRatio.coerceIn(0f, 1f)).roundToInt()
    layoutParams.y = (availableY * initialYRatio.coerceIn(0f, 1f)).roundToInt()

    val dragThreshold = (4f * density).coerceAtLeast(4f)
    var downX = 0f
    var downY = 0f
    var startX = 0
    var startY = 0
    var moved = false

    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                moved = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                if (!moved && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) moved = true
                if (moved) {
                    val screen = windowManager.maximumWindowMetrics.bounds
                    val viewWidth = width.takeIf { it > 0 } ?: defaultSize
                    val viewHeight = height.takeIf { it > 0 } ?: defaultSize
                    layoutParams.x = (startX + dx.toInt())
                        .coerceIn(0, (screen.width() - viewWidth).coerceAtLeast(0))
                    layoutParams.y = (startY + dy.toInt())
                        .coerceIn(0, (screen.height() - viewHeight).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(this, layoutParams)
                    } catch (_: Throwable) {
                    }
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
            else -> true
        }
    }
}

/** 获取悬浮窗当前在完整 display 坐标中的中心点。 */
fun View.centerOnScreen(): Point {
    val location = IntArray(2)
    getLocationOnScreen(location)
    return Point(location[0] + width / 2, location[1] + height / 2)
}
