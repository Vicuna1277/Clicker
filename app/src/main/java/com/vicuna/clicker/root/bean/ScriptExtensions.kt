package com.vicuna.clicker.root.bean

import com.vicuna.clicker.root.db.RecordScriptBean
import com.vicuna.clicker.root.db.ScriptDataBean
import com.google.gson.reflect.TypeToken

fun ScriptDataBean.getScriptCmd(): List<ScriptCmdBean> {
    return try {
        GsonUtil.gson.fromJson(
            scriptJson,
            object : TypeToken<List<ScriptCmdBean>>() {}.type
        )
    } catch (_: Exception) {
        emptyList()
    }
}

fun RecordScriptBean.getScript(): List<RecordScriptCmd> {
    return try {
        GsonUtil.gson.fromJson(
            scriptJson,
            object : TypeToken<List<RecordScriptCmd>>() {}.type
        )
    } catch (_: Exception) {
        emptyList()
    }
}
