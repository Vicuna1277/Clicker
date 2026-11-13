package com.vicuna.clicker.root.input

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 把录制层使用的“设备显示坐标(rawX/rawY)”转换成 uinput 触摸设备使用的
 * “自然方向物理坐标”。
 *
 * 注意：rawX/rawY 是整个 display 的坐标，包含状态栏/导航栏区域。
 * 因此这里不能使用 Display.width/height（可能扣除 system decor），
 * 必须使用包含 system bars 的 WindowMetrics bounds。
 */
@Suppress("DEPRECATION")
class RootDisplayMapper(
    context: Context,
    fallbackLogicalWidth: Int,
    fallbackLogicalHeight: Int
) {
    private val appContext = context.applicationContext

    private val display: Display? =
        (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val touchWidth: Int = display?.mode?.physicalWidth
        ?.takeIf { it > 0 }
        ?: fallbackLogicalWidth.coerceAtLeast(1)

    val touchHeight: Int = display?.mode?.physicalHeight
        ?.takeIf { it > 0 }
        ?: fallbackLogicalHeight.coerceAtLeast(1)

    fun snapshot(): Transform {
        val d=display
        if(d==null)return Transform(touchWidth,touchHeight,Surface.ROTATION_0,touchWidth,touchHeight)
        val bounds=windowManager.maximumWindowMetrics.bounds
        return Transform(bounds.width().coerceAtLeast(1),bounds.height().coerceAtLeast(1),d.rotation,touchWidth,touchHeight)
    }

    fun toTouchSpace(x: Int,y: Int): Point = snapshot().toTouchSpace(x,y)

    class Transform(private val logicalWidth:Int,private val logicalHeight:Int,
                    private val rotation:Int,private val touchWidth:Int,private val touchHeight:Int) {
        fun toTouchSpace(x:Int,y:Int):Point {
        val safeX = x.coerceIn(0, logicalWidth - 1)
        val safeY = y.coerceIn(0, logicalHeight - 1)

        // uinput 设备按自然方向创建，因此旋转屏幕时先还原到自然方向。
        val unrotated = when (rotation) {
            Surface.ROTATION_0 -> Point(safeX, safeY)
            Surface.ROTATION_90 -> Point(logicalHeight - 1 - safeY, safeX)
            Surface.ROTATION_180 -> Point(
                logicalWidth - 1 - safeX,
                logicalHeight - 1 - safeY
            )
            Surface.ROTATION_270 -> Point(safeY, logicalWidth - 1 - safeX)
            else -> Point(safeX, safeY)
        }

        val unrotatedLogicalWidth = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> logicalWidth
            else -> logicalHeight
        }.coerceAtLeast(1)

        val unrotatedLogicalHeight = when (rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> logicalHeight
            else -> logicalWidth
        }.coerceAtLeast(1)

        val tx = (
            unrotated.x.toDouble() * (touchWidth - 1).toDouble() /
                (unrotatedLogicalWidth - 1).coerceAtLeast(1).toDouble()
            ).roundToInt().coerceIn(0, touchWidth - 1)

        val ty = (
            unrotated.y.toDouble() * (touchHeight - 1).toDouble() /
                (unrotatedLogicalHeight - 1).coerceAtLeast(1).toDouble()
            ).roundToInt().coerceIn(0, touchHeight - 1)

        return Point(tx, ty)
    }
    }

    /**
     * 把物理 touchscreen 的 ABS_MT 坐标映射回当前旋转下的 display/raw 坐标。
     * 这是 [toTouchSpace] 的逆方向，用于 ROOT 被动录制。
     */
    fun fromPhysicalTouchSpace(
        x: Int,
        y: Int,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): Point {
        val tx = if (maxX > minX) {
            (((x - minX).coerceIn(0, maxX - minX)).toDouble() *
                (touchWidth - 1).coerceAtLeast(1).toDouble() /
                (maxX - minX).toDouble()).roundToInt()
        } else 0
        val ty = if (maxY > minY) {
            (((y - minY).coerceIn(0, maxY - minY)).toDouble() *
                (touchHeight - 1).coerceAtLeast(1).toDouble() /
                (maxY - minY).toDouble()).roundToInt()
        } else 0
        return fromTouchSpace(tx, ty)
    }

    fun fromTouchSpace(x: Int, y: Int): Point {
        val d = display ?: return Point(
            x.coerceIn(0, touchWidth - 1),
            y.coerceIn(0, touchHeight - 1)
        )
        val bounds = windowManager.maximumWindowMetrics.bounds
        val logicalWidth = bounds.width().coerceAtLeast(1)
        val logicalHeight = bounds.height().coerceAtLeast(1)
        val unrotatedLogicalWidth = when (d.rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> logicalWidth
            else -> logicalHeight
        }.coerceAtLeast(1)
        val unrotatedLogicalHeight = when (d.rotation) {
            Surface.ROTATION_0, Surface.ROTATION_180 -> logicalHeight
            else -> logicalWidth
        }.coerceAtLeast(1)

        val ux = (x.coerceIn(0, touchWidth - 1).toDouble() *
            (unrotatedLogicalWidth - 1).coerceAtLeast(1).toDouble() /
            (touchWidth - 1).coerceAtLeast(1).toDouble()).roundToInt()
        val uy = (y.coerceIn(0, touchHeight - 1).toDouble() *
            (unrotatedLogicalHeight - 1).coerceAtLeast(1).toDouble() /
            (touchHeight - 1).coerceAtLeast(1).toDouble()).roundToInt()

        val rotated = when (d.rotation) {
            Surface.ROTATION_0 -> Point(ux, uy)
            Surface.ROTATION_90 -> Point(uy, logicalHeight - 1 - ux)
            Surface.ROTATION_180 -> Point(logicalWidth - 1 - ux, logicalHeight - 1 - uy)
            Surface.ROTATION_270 -> Point(logicalWidth - 1 - uy, ux)
            else -> Point(ux, uy)
        }
        rotated.x = rotated.x.coerceIn(0, logicalWidth - 1)
        rotated.y = rotated.y.coerceIn(0, logicalHeight - 1)
        return rotated
    }

}
