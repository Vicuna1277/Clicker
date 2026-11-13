package com.vicuna.clicker.root.api

import android.content.Context

object AllowedCallers {
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences("script_api",Context.MODE_PRIVATE)
    fun packages(context: Context): Set<String> = preferences(context).getStringSet("allowed_packages",emptySet())!!.toSet()
    fun save(context: Context, packages: Set<String>) {
        preferences(context).edit().putStringSet("allowed_packages",packages.toSet()).apply()
    }
    fun valid(packageName: String) = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)
    fun permits(context: Context, uid: Int): Boolean {
        if (uid < 0) return false
        val names=context.packageManager.getPackagesForUid(uid) ?: return false
        val allowed=packages(context)
        // Shared-UID apps cannot be distinguished by Binder. Require every package.
        return names.isNotEmpty() && names.all { it in allowed }
    }
}
