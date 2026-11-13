package com.vicuna.clicker.root.input

/**
 * 长驻 ROOT 触摸设备的最小接口。
 *
 * RootInput 只关心连续的 DOWN/MOVE/UP，不关心底层究竟是：
 * 1. APK 内置 native /dev/uinput helper；
 * 2. Android 系统 /system/bin/uinput 命令。
 */
interface RootTouchDevice {
    fun isReady(): Boolean
    fun start(timeoutMs: Long): Boolean
    fun close()
    fun prepareGesture() {}
    fun tap(x: Int, y: Int)
    fun down(x: Int, y: Int, id: Int)
    fun move(x: Int, y: Int)
    fun up(id: Int)
}
