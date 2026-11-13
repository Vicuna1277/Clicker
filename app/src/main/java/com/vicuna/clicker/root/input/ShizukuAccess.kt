package com.vicuna.clicker.root.input

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Permission status; ROOT is preferred, Shizuku is the execution fallback. */
object ShizukuAccess {
    fun state(): String = try {
        when {
            !Shizuku.pingBinder() -> "未连接，请先启动 Shizuku"
            Shizuku.isPreV11() -> "Shizuku 版本过旧，请更新"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "已授权 · ROOT 不可用时使用"
            else -> "已连接，等待授权"
        }
    } catch (_: Exception) { "未连接，请先启动 Shizuku" }

    fun request(): String = try {
        when {
            !Shizuku.pingBinder() -> "请先安装并启动 Shizuku"
            Shizuku.isPreV11() -> "请更新 Shizuku"
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> "Shizuku 已授权，默认优先 ROOT"
            Shizuku.shouldShowRequestPermissionRationale() -> "请在 Shizuku 的应用管理中允许 Clicker"
            else -> { Shizuku.requestPermission(271); "已请求 Shizuku 授权" }
        }
    } catch (_: Exception) { "Shizuku 连接不可用，请启动后重试" }
}
