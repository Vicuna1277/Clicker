package com.vicuna.clicker.root.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.Ohuang.ilivedata.MyLiveData
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.PowerKeyObserver
import com.vicuna.clicker.root.R
import com.vicuna.clicker.root.ScriptExecutor
import com.vicuna.clicker.root.ScriptInterfaceImpl
import com.vicuna.clicker.root.SmallWindowView
import com.vicuna.clicker.root.Util
import com.vicuna.clicker.root.helper.setOnTouchClick
import com.vicuna.clicker.root.helper.FLOAT_RESTART_DEBOUNCE_MS
import com.vicuna.clicker.root.helper.bindMovableRunButton
import com.vicuna.clicker.root.helper.setRunFloatState
import com.vicuna.clicker.root.bean.ScriptCmdBean
import com.vicuna.clicker.root.helper.smallWindowManager
import com.vicuna.clicker.root.ui.theme.ClickerTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScriptActivityCompose : ComponentActivity() {

    @Volatile private var isRun = false
        set(value) {
            if (value && !com.vicuna.clicker.root.api.ExecutionGate.acquire(this)) {
                android.widget.Toast.makeText(this, "已有脚本正在执行", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            field = value
            if (!value) com.vicuna.clicker.root.api.ExecutionGate.release(this)
        }

    private var isShow = false
    private var stopTime = 0L
    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var scriptExecutor: ScriptExecutor? = null
    private var myService: MyService? = null
    private var delayCoefficient = 1.0
    private var powerKeyObserver: PowerKeyObserver? = null
    private var observer: Observer<String>? = null
    private var thisPkgName = ""
    private var pkgNameNow = ""
    private var currentScriptName = ""

    private var btnWindowView: SmallWindowView? = null
    private var tvBtnWv: TextView? = null
    private var wm: WindowManager? = null
    private var btnLayoutParams: WindowManager.LayoutParams? = null

   private var scriptInterface: ScriptInterfaceImpl=ScriptInterfaceImpl({
       return@ScriptInterfaceImpl  isRun
   })

    var isRunning by mutableStateOf(false)
        private set

    var scriptConfig by mutableStateOf(ScriptRunConfig())
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scriptExecutor = ScriptExecutor(scriptInterface)
        initSmallViewLayout()
        initBtnWindow()
        initObserver()
        MyLiveData.getInstance().with("scriptName", String::class.java)
            .observe(this) { name ->
                currentScriptName = name ?: ""
                tvBtnWv?.setRunFloatState(
                    isRun,
                    startText = currentScriptName.ifBlank { "脚本" }
                )
            }

        setContent {
            ClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScriptScreen(
                        scriptConfig = scriptConfig,

                        onBack = { finish() },
                        onOpenAccessibility = { openAccessibility() },
                        onStartScriptWindow = { startScriptWindow() },
                        onOpenScriptList = { openScriptList() },
                        onRunScript = { runScript() },
                        onStopScript = { stopScript() },
                        onConfigChange = { scriptConfig = it },
                        isRunning = isRunning
                    )
                }
            }
        }

        powerKeyObserver = PowerKeyObserver(this).apply {
            startListen()
            setHomeKeyListener {
                stopScript()
            }
        }
    }

    private fun initObserver() {
        observer = Observer<String> { s ->
            pkgNameNow = s ?: ""
            if (scriptConfig.checkAppChange) {
                if (pkgNameNow != thisPkgName) {
                    if (isRun) {
                        stopScript()
                    }
                }
            }
        }
        MyService.myService.pkgNameMutableLiveData.observeForever(observer!!)
    }

    private fun openAccessibility() {
        Thread {
            val ok = MyService.ensureRoot(this)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "ROOT 授权成功：${MyService.backendName()}" else "ROOT 授权失败，请检查 ROOT 管理器",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun openScriptList() {
        if (isRun) {
            stopScript()
        }
        hideFloatWindows()
        startActivity(Intent(this, ScriptListActivityCompose::class.java))
    }

    private fun startScriptWindow() {
        if (!isShow) {
            showFloatWindows()
        } else {
            if (isRun) {
                stopScript()
            }
            hideFloatWindows()
        }
    }

    private fun runScript(data: List<ScriptCmdBean>? = null) {
        val config = scriptConfig
        val scriptData = data ?: MyLiveData.getInstance().with("json", String::class.java).value?.let { json ->
            try { Gson().fromJson<List<ScriptCmdBean>>(json, object : TypeToken<List<ScriptCmdBean>>() {}.type) } catch (e: Exception) { null }
        }
        if (!MyService.isStart()) {
            Toast.makeText(this, "请先授权 ROOT 或 Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        if (scriptData.isNullOrEmpty()) {
            Toast.makeText(this, "请选择要执行的脚本", Toast.LENGTH_SHORT).show()
            return
        }
        val speed = config.speed
        delayCoefficient = if (speed < 0.25) 1.0 else if (speed > 5.0) 0.2 else 1.0 / speed
        thisPkgName = pkgNameNow
        isRun = true
        if (!isRun) return
        isRunning = true
        tvBtnWv?.setRunFloatState(true)
        singleThreadExecutor.execute {
            scriptInterface.delayCoefficient=delayCoefficient.toFloat()


            var j = 0
            while (isRun && (config.count <= 0 || j < config.count)) {
                scriptExecutor?.run(scriptData)
                var i2 = 0
                while (i2 < config.interval / 100 && isRun) {
                    try { Thread.sleep(100) } catch (e: InterruptedException) { break }
                    i2++
                }
                if (isRun) {
                    try { Thread.sleep((config.interval % 100).toLong()) } catch (e: InterruptedException) { break }
                }
                j++
            }
            isRun = false
            isRunning = false
            lifecycleScope.launch {
                tvBtnWv?.setRunFloatState(false)
            }
        }
    }

    private fun stopScript() {
        stopTime = System.currentTimeMillis()
        isRun = false
        isRunning = false
        tvBtnWv?.setRunFloatState(false)
    }

    // region 悬浮窗

    @SuppressLint("WrongConstant")
    private fun initSmallViewLayout() {
        btnWindowView = LayoutInflater.from(this).inflate(R.layout.window_b, null) as SmallWindowView
        wm = smallWindowManager()
        btnLayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, 8, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            btnLayoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        btnLayoutParams?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
    }

    private fun initBtnWindow() {
        tvBtnWv = btnWindowView?.findViewById(R.id.tv_win_b)
        tvBtnWv?.setRunFloatState(false, startText = currentScriptName.ifBlank { "脚本" })
        if (btnWindowView != null && tvBtnWv != null && wm != null && btnLayoutParams != null) {
            btnWindowView?.bindMovableRunButton(
                textView = tvBtnWv!!,
                windowManager = wm!!,
                layoutParams = btnLayoutParams!!,
                onClick = {
                    if (isRun) {
                        stopScript()
                    } else if (System.currentTimeMillis() - stopTime >= FLOAT_RESTART_DEBOUNCE_MS) {
                        runScript()
                    }
                },
                positionKey = "script_run"
            )
        }
    }

    @SuppressLint("WrongConstant")
    private fun alertWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
            } else {
                showWindow()
            }
        } else {
            showWindow()
        }
    }

    private fun showWindow() {
        if (wm != null && btnWindowView?.windowId == null) {
            wm?.addView(btnWindowView, btnLayoutParams)
        }
    }

    fun dismissWindow() {
        try {
            if (wm != null && btnWindowView?.windowId != null) {
                wm?.removeView(btnWindowView)
            }
        } catch (_: Exception) {
        }
    }

    private fun showFloatWindows() {
        alertWindow()
        isShow = true
    }

    private fun hideFloatWindows() {
        isShow = false
        dismissWindow()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (Settings.canDrawOverlays(this)) {
                showWindow()
            } else {
                Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            }
        }
    }

 

    // endregion

    override fun onDestroy() {
        super.onDestroy()
        isRun = false
        hideFloatWindows()
        powerKeyObserver?.stopListen()
        observer?.let { MyService.myService.pkgNameMutableLiveData.removeObserver(it) }
        singleThreadExecutor.shutdownNow()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 2
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptScreen(
    scriptConfig: ScriptRunConfig,
    isFloatingWindowShow: Boolean=false,
    onBack: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onStartScriptWindow: () -> Unit,
    onOpenScriptList: () -> Unit,
    onRunScript: () -> Unit,
    onStopScript: () -> Unit,
    onConfigChange: (ScriptRunConfig) -> Unit,
    isRunning: Boolean
) {
    val context = LocalContext.current
    var scriptJson by remember { mutableStateOf("") }
    var scriptName by remember { mutableStateOf("") }
    var scriptData by remember { mutableStateOf<List<ScriptCmdBean>?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var intervalText by remember { mutableStateOf(scriptConfig.interval.toString()) }
    var countText by remember { mutableStateOf(scriptConfig.count.toString()) }
    var speedText by remember { mutableStateOf(if (scriptConfig.speed == 1.0) "1" else scriptConfig.speed.toString()) }



    LaunchedEffect(isRunning) {
        if (!isRunning) isExecuting = false
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        MyLiveData.getInstance().with("json", String::class.java)
            .observe(lifecycleOwner) { json ->
                scriptJson = json ?: ""
                scriptData = try {
                    Gson().fromJson(json, object : TypeToken<List<ScriptCmdBean>>() {}.type)
                } catch (e: Exception) { null }
            }
        MyLiveData.getInstance().with("scriptName", String::class.java)
            .observe(lifecycleOwner) { name ->
                scriptName = name ?: ""
            }


    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("脚本执行") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("脚本执行", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
                            intervalText = value
                            onConfigChange(scriptConfig.copy(interval = value.toIntOrNull() ?: 0))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("时间间隔(ms)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
                            countText = value
                            onConfigChange(scriptConfig.copy(count = value.toIntOrNull() ?: 0))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("次数(0为无限)") },
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = speedText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d*$"))) {
                        speedText = value
                        val parsed = value.toDoubleOrNull()
                        if (parsed != null) {
                            onConfigChange(scriptConfig.copy(speed = parsed))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("执行速度倍速(0.25~5)") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = scriptConfig.checkAppChange,
                    onCheckedChange = { onConfigChange(scriptConfig.copy(checkAppChange = it)) }
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("检测到应用切换时停止脚本", style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = onStartScriptWindow,
                modifier = Modifier.fillMaxWidth(),
                colors = if (isFloatingWindowShow) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ) else ButtonDefaults.buttonColors()
            ) {
                Text(if (isFloatingWindowShow) "关闭悬浮窗" else "打开脚本悬浮窗")
            }

            if (scriptName.isNotEmpty()) {
                Text("脚本名称: $scriptName", style = MaterialTheme.typography.bodyMedium)
            }

            if (scriptJson.isNotEmpty()) {
                Text(
                    text = scriptJson,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Util.copyText(scriptJson, context)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                )
            }




        }
    }
}

data class ScriptRunConfig(
    val interval: Int = 1000,
    val count: Int = 0,
    val speed: Double = 1.0,
    val checkAppChange: Boolean = false,
    val xCoefficient: Float = 1.0f,
    val yCoefficient: Float = 1.0f
)
