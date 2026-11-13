package com.vicuna.clicker.root.helper

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.vicuna.clicker.root.bean.ScriptCmdBean

// The insert menu orders Click before Delay, unlike the serialized action numbers.
private val menuActions = listOf(
    ScriptCmdBean.ACTION_CLICK, ScriptCmdBean.ACTION_DELAYED,
    ScriptCmdBean.ACTION_GESTURE, ScriptCmdBean.ACTION_FOR,
    ScriptCmdBean.ACTION_FOR_END, ScriptCmdBean.ACTION_RANDOM_CLICK
)

fun commandMenuAction(index: Int): Int? = menuActions.getOrNull(index)

/** Resolve the named drawable from the installed resource table, not a stored resource ID. */
@SuppressLint("DiscouragedApi")
@Composable
fun CommandIcon(action: Int?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val iconName = when (action) {
        ScriptCmdBean.ACTION_CLICK -> "icon_click"
        ScriptCmdBean.ACTION_DELAYED -> "icon_delay"
        ScriptCmdBean.ACTION_GESTURE -> "icon_gesture"
        ScriptCmdBean.ACTION_FOR, ScriptCmdBean.ACTION_FOR_END -> "icon_for"
        ScriptCmdBean.ACTION_RANDOM_CLICK -> "icon_random"
        else -> "ic_launcher_foreground"
    }
    // Explicit shrinker retention for these dynamically named resources is in raw/keep_command_icons.xml.
    val resourceId = remember(context, iconName) {
        context.resources.getIdentifier(iconName, "drawable", context.packageName)
    }
    if (resourceId != 0) {
        Image(painter = painterResource(resourceId), contentDescription = null, modifier = modifier)
    }
}
