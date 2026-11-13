package com.vicuna.clicker.root.activity

import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.Ohuang.ilivedata.MyLiveData
import com.vicuna.clicker.root.MainThreadSync
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.PowerKeyObserver
import com.vicuna.clicker.root.RecordScriptExecutor
import com.vicuna.clicker.root.bean.RecordScriptCmd
import com.vicuna.clicker.root.databinding.WindowBBinding
import com.vicuna.clicker.root.db.RecordScriptBean
import com.vicuna.clicker.root.helper.SmallWindowsHelper
import com.vicuna.clicker.root.helper.setOnTouchClick
import com.vicuna.clicker.root.helper.bindMovableRunButton
import com.vicuna.clicker.root.helper.setRunFloatState
import com.vicuna.clicker.root.ui.theme.ClickerTheme
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class RecordScriptPlayActivityCompose : ComponentActivity(), RecordScriptExecutor.RecordScriptInterface {

    @Volatile private var isRun = false
        set(value) {
            if (value && !com.vicuna.clicker.root.api.ExecutionGate.acquire(this)) {
                android.widget.Toast.makeText(this, "已有脚本正在执行", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            field = value
            if (!value) com.vicuna.clicker.root.api.ExecutionGate.release(this)
        }

    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recordScriptExecutor = RecordScriptExecutor()
    private var data = ArrayList<RecordScriptCmd>()
    private var powerKeyObserver: PowerKeyObserver? = null
    private lateinit var playSmallWindowsHelper: SmallWindowsHelper
    private var windowBBinding: WindowBBinding? = null
    private var scriptName by mutableStateOf("")
    private var scriptJson by mutableStateOf("")
    private var time by mutableStateOf(1000L)
    private var count by mutableStateOf(0)
    private var speed by mutableStateOf(1.0f)
    private var checkAppChange by mutableStateOf(false)
    private var thisPkgName = ""
    private var pkgNameNow = ""
    private val observer = Observer<String> { s ->
        pkgNameNow = s
        if (checkAppChange && isRun) {
            if (pkgNameNow != thisPkgName) {
                isRun = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordScriptExecutor.recordScriptInterface = this
        setContent {
            ClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecordScriptPlayScreen(
                        scriptName = scriptName,
                        scriptJson = scriptJson,
                        time = time,
                        count = count,
                        speed = speed,
                        checkAppChange = checkAppChange,
                        onTimeChange = { time = it },
                        onCountChange = { count = it },
                        onSpeedChange = { speed = it },
                        onCheckAppChangeChange = { checkAppChange = it },
                        onBack = { finish() },
                        onOpenFloatWindow = { togglePlayWindow() }
                    )
                }
            }
        }

        initPlaySmallWindows()

        powerKeyObserver = PowerKeyObserver(this).apply {
            startListen()
            setHomeKeyListener { stopPlay() }
        }

        MyService.myService.pkgNameMutableLiveData.observeForever(observer)

        MyLiveData.getInstance().with("RecordScriptPlay", RecordScriptBean::class.java)
            .observe(this) { bean ->
                bean?.let {
                    scriptName = it.name ?: ""
                    windowBBinding?.tvWinB?.setRunFloatState(
                        isRun,
                        startText = scriptName.ifBlank { "录制脚本" }
                    )
                    scriptJson = it.scriptJson ?: ""
                    data = try {
                        Gson().fromJson(it.scriptJson, object : TypeToken<List<RecordScriptCmd>>() {}.type)
                    } catch (e: Exception) { ArrayList() }
                }
            }
    }

    private fun initPlaySmallWindows() {
        playSmallWindowsHelper = SmallWindowsHelper(this)
        val mLayoutParams = playSmallWindowsHelper.mLayoutParams
        mLayoutParams?.gravity = Gravity.TOP
        windowBBinding = WindowBBinding.inflate(layoutInflater)
        windowBBinding?.tvWinB?.setRunFloatState(false, startText = scriptName.ifBlank { "录制脚本" })
        val root = windowBBinding?.root
        val button = windowBBinding?.tvWinB
        if (root != null && button != null) {
            root.bindMovableRunButton(
                textView = button,
                windowManager = playSmallWindowsHelper.mWindowManager,
                layoutParams = mLayoutParams,
                onClick = {
                    if (isRun) {
                        isRun = false
                        button.setRunFloatState(false)
                        Toast.makeText(this, "停止", Toast.LENGTH_SHORT).show()
                    } else if (!MyService.isStart()) {
                        Toast.makeText(this, "请先授权 ROOT 或 Shizuku", Toast.LENGTH_LONG).show()
                    } else if (data.isEmpty()) {
                        Toast.makeText(this, "没有脚本数据", Toast.LENGTH_LONG).show()
                    } else {
                        val speedValue = speed
                        recordScriptExecutor.delayCoefficient = if (speedValue in 0.25f..5.0f) (1.0 / speedValue) else 1.0
                        isRun = true
                        if (!isRun) return@bindMovableRunButton
                        thisPkgName = pkgNameNow
                        singleThreadExecutor.execute(playRunnable)
                        button.setRunFloatState(true)
                        Toast.makeText(this, "开始", Toast.LENGTH_SHORT).show()
                    }
                },
                positionKey = "record_script_play"
            )
        }
    }

    private val playRunnable = Runnable {
        var i = 0
        while (isRun && (count <= 0 || i < count)) {
            recordScriptExecutor.run(data)
            if (isRun && (count <= 0 || i < count - 1)) {
                recordScriptExecutor.delay(time)
            }
            i++
        }
        isRun = false
        lifecycleScope.launch {
            windowBBinding?.tvWinB?.setRunFloatState(false)
        }
    }

    private fun togglePlayWindow() {
        if (playSmallWindowsHelper.isShow) {
            playSmallWindowsHelper.hide()
        } else {
            showPlayWindow()
        }
    }

    private fun showPlayWindow() {
        if (SmallWindowsHelper.requestPermission(this)) {
            if (playSmallWindowsHelper.root == null) {
                playSmallWindowsHelper.attach(windowBBinding?.root!!)
            } else {
                playSmallWindowsHelper.show()
            }
        }
    }

    private fun stopPlay() {
        isRun = false
        windowBBinding?.root?.post {
            windowBBinding?.tvWinB?.setRunFloatState(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRun = false
        powerKeyObserver?.stopListen()
        playSmallWindowsHelper.hide()
        MyService.myService.pkgNameMutableLiveData.removeObserver(observer)
        singleThreadExecutor.shutdownNow()
    }

    override fun isRun() = isRun

    override fun preDispatchGesture(x: Int, y: Int): Boolean {
        return MainThreadSync.call {
            val tv = windowBBinding?.tvWinB ?: return@call false
            if (calcPointRange(tv, x, y)) {
                playNotTouch()
                true
            } else {
                false
            }
        }
    }

    override fun dispatchGesture(position: Int, path: Path, duration: Int) {
        if (MyService.isStart()) {
            MyService.myService.dispatchGestureAndWait(path, duration) { isRun }
        }
    }

    override fun endDispatchGesture() {
        windowBBinding?.root?.post {
            playCanTouch()
        }
    }

    private fun playNotTouch() {
        val mLayoutParams = playSmallWindowsHelper.mLayoutParams
        mLayoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        playSmallWindowsHelper.mLayoutParams = mLayoutParams!!
        windowBBinding?.tvWinB?.alpha = 0.45f
    }

    private fun playCanTouch() {
        val mLayoutParams = playSmallWindowsHelper.mLayoutParams
        mLayoutParams?.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        playSmallWindowsHelper.mLayoutParams = mLayoutParams!!
        windowBBinding?.tvWinB?.alpha = 1.0f
    }

    private fun calcPointRange(view: View, x: Int, y: Int): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width && y >= location[1] && y <= location[1] + view.height
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScriptPlayScreen(
    scriptName: String,
    scriptJson: String,
    time: Long,
    count: Int,
    speed: Float = 1.0f,
    checkAppChange: Boolean,
    onTimeChange: (Long) -> Unit,
    onCountChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit = {},
    onCheckAppChangeChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenFloatWindow: () -> Unit
) {
    var timeText by remember { mutableStateOf(time.toString()) }
    var countText by remember { mutableStateOf(count.toString()) }
    var speedText by remember { mutableStateOf(if (speed == 1.0f) "1" else speed.toString()) }



    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("录制脚本播放") },
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
            

            if (scriptName.isNotEmpty()) {
                Text("脚本名称: $scriptName", style = MaterialTheme.typography.bodyLarge)
            }

        

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("间隔(ms):", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = timeText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
                            timeText = value
                            val newTime = value.toLongOrNull() ?: 1000L
                            if (newTime >= 100) onTimeChange(newTime)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Text("次数:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = countText,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.matches(Regex("^\\d*$"))) {
                            countText = value
                            val newCount = value.toIntOrNull() ?: 1
                            if (newCount >= 1) onCountChange(newCount)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            OutlinedTextField(
                value = speedText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d*$"))) {
                        speedText = value
                        val parsed = value.toFloatOrNull()
                        if (parsed != null) onSpeedChange(parsed)
                        else if (value.isEmpty()) onSpeedChange(1.0f)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("执行速度倍数(0.25~5)") },
                placeholder = { Text("1为原速，2为2倍速，0.5为半速") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checkAppChange,
                    onCheckedChange = onCheckAppChangeChange
                )
                Text("应用切换停止运行")
            }

            Button(
                onClick = onOpenFloatWindow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("打开脚本悬浮窗")
            }
           
        }
    }
}
