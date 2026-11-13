package com.vicuna.clicker.root.activity

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.Ohuang.ilivedata.MyLiveData
import com.vicuna.clicker.root.MyApp
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.R
import com.vicuna.clicker.root.bean.ScriptGroup
import com.vicuna.clicker.root.bean.toScriptGroup
import com.vicuna.clicker.root.db.RecordScriptBean
import com.vicuna.clicker.root.db.ScriptDataBean
import com.vicuna.clicker.root.db.ScriptGroupBean
import com.vicuna.clicker.root.ui.theme.ClickerTheme

class LauncherScriptActivity : ComponentActivity() {

    companion object {
        const val TYPE = "type"
        const val TYPE_SCRIPT = "type_script"  //普通脚本
        const val TYPE_RECORD_SCRIPT = "type_record_script" //录制脚本
        const val TYPE_SCRIPT_GROUP = "type_script_group" //脚本组
        const val TYPE_KEY_BINDING = "type_key_binding" //按键设置
        const val ID = "id"
        private const val TAG = "LauncherScriptActivityCompose"
    }

    private var isLoad = false
    private var scriptDataBean: ScriptDataBean? = null
    private var recordScriptBean: RecordScriptBean? = null
    private var scriptGroupBean: ScriptGroupBean? = null
    private var type: String? = null
    private var id = -1
    private var isLauncher = false
    private var showAccessibilityDialog by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        type = intent.getStringExtra(TYPE)
        id = intent.getIntExtra(ID, -1)
        Log.d(TAG, "onCreate: type=$type  id=$id")

        setContent {
            ClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LauncherScriptScreen(
                        showAccessibilityDialog = showAccessibilityDialog,
                        onDismissDialog = {
                            showAccessibilityDialog = false
                            finish()
                        },
                        onOpenAccessibility = {
                            showAccessibilityDialog = false
                            Thread {
                                val ok = MyService.ensureRoot(this)
                                runOnUiThread {
                                    if (ok && isLoad) {
                                        startScript()
                                        finish()
                                    } else if (!ok) {
                                        Toast.makeText(this, "ROOT 授权失败，请检查 ROOT 管理器", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }.start()
                        }
                    )
                }
            }
        }

        window.decorView.postDelayed({
            initConfig()
        }, 500)
    }

    private fun initConfig() {
        val appDatabase = (application as MyApp).appDatabase

        if (type == TYPE_KEY_BINDING) {
            startActivity(Intent(this, KeyBindingListActivity::class.java))
            finish()
            return
        }

        if (id >= 0) {
            when (type) {
                TYPE_RECORD_SCRIPT -> {
                    Thread {
                        recordScriptBean = appDatabase.recordScriptDao.findBeanById(id)
                        isLoad = true
                        runOnUiThread {
                            if (MyService.isStart()) {
                                startScript()
                            }
                        }
                    }.start()
                }
                TYPE_SCRIPT -> {
                    Thread {
                        scriptDataBean = appDatabase.scriptDao.findBeanById(id)
                        isLoad = true
                        runOnUiThread {
                            if (MyService.isStart()) {
                                startScript()
                            }
                        }
                    }.start()
                }
                TYPE_SCRIPT_GROUP -> {
                    Thread {
                        scriptGroupBean = appDatabase.scriptGroupDao.findBeanById(id)
                        isLoad = true
                        runOnUiThread {
                            if (MyService.isStart()) {
                                startScript()
                            }
                        }
                    }.start()
                }
            }
        } else {
            finish()
            return
        }

        if (!MyService.isStart()) {
            showAccessibilityDialog = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (MyService.isStart() && isLoad) {
            startScript()
            finish()
        }
    }

    private fun startScript() {
        if (isLauncher) return
        isLauncher = true

        when (type) {
            TYPE_RECORD_SCRIPT -> {
                if (recordScriptBean == null) {
                    Toast.makeText(this, "没有脚本数据", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                startRecordScriptPlayActivity()
            }
            TYPE_SCRIPT -> {
                if (scriptDataBean == null) {
                    Toast.makeText(this, "没有脚本数据", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                startScriptActivity()
            }
            TYPE_SCRIPT_GROUP -> {
                if (scriptGroupBean == null) {
                    Toast.makeText(this, "没有脚本组数据", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                startScriptGroupPlayActivity()
            }
        }
    }

    private fun startRecordScriptPlayActivity() {
        MyLiveData.getInstance().with("RecordScriptPlay", RecordScriptBean::class.java)
            .postValue(recordScriptBean)
        startActivity(Intent(this, RecordScriptPlayActivityCompose::class.java))
    }

    private fun startScriptActivity() {
        MyLiveData.getInstance().with<String>("json", String::class.java)
            .setValue(scriptDataBean!!.getScriptJson())
        MyLiveData.getInstance().with<String>("scriptName", String::class.java)
            .setValue(scriptDataBean!!.getName())
        startActivity(Intent(this, ScriptActivityCompose::class.java))
    }

    private fun startScriptGroupPlayActivity() {
        val scriptGroup = scriptGroupBean!!.toScriptGroup()
        MyLiveData.getInstance().with("ScriptGroup", ScriptGroup::class.java)
            .postValue(scriptGroup)
        startActivity(Intent(this, ScriptGroupPlayActivityCompose::class.java))
    }
}

@Composable
fun LauncherScriptScreen(
    showAccessibilityDialog: Boolean = false,
    onDismissDialog: () -> Unit = {},
    onOpenAccessibility: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            AndroidView(
                modifier = Modifier.size(180.dp),
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        contentDescription = "App Icon"
                        setImageDrawable(
                            context.applicationInfo.loadIcon(context.packageManager)
                        )
                    }
                },
                update = { imageView ->
                    val context = imageView.context
                    imageView.setImageDrawable(
                        context.applicationInfo.loadIcon(context.packageManager)
                    )
                }
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.app_name),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }

    if (showAccessibilityDialog) {
        AlertDialog(
            onDismissRequest = onDismissDialog,
            title = { Text("ROOT 授权") },
            text = { Text("运行快捷脚本需要 ROOT 授权，是否现在请求授权？") },
            confirmButton = {
                TextButton(onClick = onOpenAccessibility) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDialog) {
                    Text("取消")
                }
            }
        )
    }
}
