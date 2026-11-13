package com.vicuna.clicker.root.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.Ohuang.ilivedata.MyLiveData
import com.vicuna.clicker.root.MainThreadSync
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.R
import com.vicuna.clicker.root.RecordScriptExecutor
import com.vicuna.clicker.root.SmallWindowView
import com.vicuna.clicker.root.bean.RecordScriptCmd
import com.vicuna.clicker.root.databinding.WindowBBinding
import com.vicuna.clicker.root.db.RecordScriptBean
import com.vicuna.clicker.root.helper.SmallWindowsHelper
import com.vicuna.clicker.root.helper.bindMovableCoordinatePicker
import com.vicuna.clicker.root.helper.bindMovableRunButton
import com.vicuna.clicker.root.helper.centerOnScreen
import com.vicuna.clicker.root.helper.setRunFloatState
import com.vicuna.clicker.root.helper.smallWindowManager
import com.vicuna.clicker.root.helper.toDate
import com.vicuna.clicker.root.input.RootTouchRecorder
import com.vicuna.clicker.root.ui.theme.ClickerTheme
import com.vicuna.clicker.root.vm.RecordScriptViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.atomic.AtomicReference

class RecordScriptActivityCompose : ComponentActivity(),
    RecordScriptExecutor.RecordScriptInterface {

    private var viewModel: RecordScriptViewModel? = null
    private var smallWindowsHelper: SmallWindowsHelper? = null
    private var playSmallWindowsHelper: SmallWindowsHelper? = null
    private var smallWindowBinding: WindowBBinding? = null
    private var windowBtnBinding: WindowBBinding? = null

    // 坐标拾取悬浮窗（用于录制脚本中插入/编辑普通点击或普通滑动指令）
    private var pickerWindowView: SmallWindowView? = null
    private var pickerBtnWindowView: SmallWindowView? = null
    private var pickerTvBtn: TextView? = null
    private var pickerWm: WindowManager? = null
    private var pickerLayoutParams: WindowManager.LayoutParams? = null
    private var pickerBtnLayoutParams: WindowManager.LayoutParams? = null
    var onCoordinatePicked: ((x: Int, y: Int) -> Unit)? = null

    @Volatile private var isRun = false
        set(value) {
            if (value && !com.vicuna.clicker.root.api.ExecutionGate.acquire(this)) {
                android.widget.Toast.makeText(this, "已有脚本正在执行", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            field = value
            if (!value) com.vicuna.clicker.root.api.ExecutionGate.release(this)
        }

    private var currentRecordScriptName = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recordPanelRect = AtomicReference(Rect())
    private val rootTouchRecorder by lazy { RootTouchRecorder(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[RecordScriptViewModel::class.java]
        viewModel?.recordScriptExecutor?.recordScriptInterface = this
        initSmallWindows()
        initPlaySmallWindows()
        initCoordinatePickerWindows()

        val isEdit = intent.getBooleanExtra("isEdit", false)
        if (isEdit) {
            MyLiveData.getInstance().with("RecordScriptEdit", RecordScriptBean::class.java)
                .observe(this) { bean ->
                    bean?.let {
                        viewModel?.recordScriptBean = it
                        currentRecordScriptName = it.name ?: ""
                        windowBtnBinding?.tvWinB?.setRunFloatState(
                            isRun,
                            startText = it.name?.takeIf { name -> name.isNotBlank() } ?: "录制脚本"
                        )
                        val data: List<RecordScriptCmd> = try {
                            Gson().fromJson(
                                it.scriptJson,
                                object : TypeToken<List<RecordScriptCmd>>() {}.type
                            ) ?: ArrayList()
                        } catch (_: Exception) {
                            ArrayList()
                        }
                        // 兼容旧 JSON：repeatCount 不存在时 Gson 会读成 0，统一按 1 修正。
                        data.forEach { cmd ->
                            if (cmd.type == RecordScriptCmd.Type.Gesture && cmd.repeatCount <= 0) {
                                cmd.repeatCount = 1
                            }
                        }
                        viewModel?.data?.clear()
                        viewModel?.data?.addAll(data)
                    }
                }
        }

        setContent {
            ClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecordScriptEditScreen(
                        isEdit = isEdit,
                        activity = this@RecordScriptActivityCompose,
                        onBack = { finish() },
                        onOpenRecordWindow = { openRecordWindow() },
                        onPlay = { togglePlayWindow() },
                        onScriptNameChange = { name ->
                            currentRecordScriptName = name
                            windowBtnBinding?.tvWinB?.setRunFloatState(
                                isRun,
                                startText = name.ifBlank { "录制脚本" }
                            )
                        },
                        onSave = { name -> saveScript(name) }
                    )
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun initCoordinatePickerWindows() {
        pickerWm = smallWindowManager()

        pickerWindowView = LayoutInflater.from(this).inflate(R.layout.window_a, null) as SmallWindowView
        pickerLayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            8,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pickerLayoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        pickerWindowView?.setWm(pickerWm)
        pickerWindowView?.setWmParams(pickerLayoutParams)
        if (pickerWindowView != null && pickerWm != null && pickerLayoutParams != null) {
            pickerWindowView?.bindMovableCoordinatePicker(pickerWm!!, pickerLayoutParams!!)
        }

        pickerBtnWindowView = LayoutInflater.from(this).inflate(R.layout.window_b, null) as SmallWindowView
        pickerBtnLayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            8,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pickerBtnLayoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        pickerBtnLayoutParams?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        pickerBtnWindowView?.enableMove = false
        pickerBtnWindowView?.setWm(pickerWm)
        pickerBtnWindowView?.setWmParams(pickerBtnLayoutParams)

        pickerTvBtn = pickerBtnWindowView?.findViewById(R.id.tv_win_b)
        pickerTvBtn?.setRunFloatState(false, startText = "完成", stopText = "完成")
        if (pickerBtnWindowView != null && pickerTvBtn != null && pickerWm != null && pickerBtnLayoutParams != null) {
            pickerBtnWindowView?.bindMovableRunButton(
                textView = pickerTvBtn!!,
                windowManager = pickerWm!!,
                layoutParams = pickerBtnLayoutParams!!,
                onClick = {
                    pickerWindowView?.centerOnScreen()?.let { point ->
                        onCoordinatePicked?.invoke(point.x, point.y)
                    }
                    dismissCoordinatePicker()
                },
                positionKey = "record_script_pick_done"
            )
        }
    }

    @SuppressLint("WrongConstant")
    fun alertCoordinatePicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                2
            )
            return
        }
        if (pickerWm != null && pickerWindowView?.windowId == null) {
            pickerWm?.addView(pickerWindowView, pickerLayoutParams)
        }
        if (pickerWm != null && pickerBtnWindowView?.windowId == null) {
            pickerWm?.addView(pickerBtnWindowView, pickerBtnLayoutParams)
        }
    }

    fun dismissCoordinatePicker() {
        try {
            if (pickerWm != null && pickerWindowView?.windowId != null) {
                pickerWm?.removeView(pickerWindowView)
            }
            if (pickerWm != null && pickerBtnWindowView?.windowId != null) {
                pickerWm?.removeView(pickerBtnWindowView)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * v3.1：录制悬浮窗直接复用默认 window_b。
     * 空闲为绿色“开始”，录制中为红色“停止”，整个按钮可拖动。
     * ROOT getevent 仍然是旁路录制，按钮自身的点击/拖动会通过 recordPanelRect 过滤掉。
     */
    private fun initSmallWindows() {
        smallWindowsHelper = SmallWindowsHelper(this)
        val helper = smallWindowsHelper ?: return
        smallWindowBinding = WindowBBinding.inflate(layoutInflater)
        val binding = smallWindowBinding ?: return

        binding.tvWinB.setRunFloatState(false, startText = "录制")
        binding.root.bindMovableRunButton(
            textView = binding.tvWinB,
            windowManager = helper.mWindowManager,
            layoutParams = helper.mLayoutParams,
            onClick = {
                if (viewModel?.isRecord == true) {
                    stopPassiveRecord()
                } else {
                    startPassiveRecord()
                }
            },
            positionKey = "record_capture",
            onPositionChanged = {
                binding.root.post { updateRecordPanelRect() }
            }
        )
    }

    private fun startPassiveRecord() {
        if (!MyService.isStart()) {
            Toast.makeText(this, "请先授权 ROOT 或 Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        val vm = viewModel ?: return
        vm.startRecord()
        val ok = rootTouchRecorder.start(
            listener = object : RootTouchRecorder.Listener {
                override fun onGestureStart(delayMs: Int) {
                    mainHandler.post {
                        if (vm.isRecord) vm.addDelayTime(delayMs)
                    }
                }

                override fun onGesture(recordScriptCmd: RecordScriptCmd) {
                    mainHandler.post {
                        if (vm.isRecord) {
                            vm.addRecordScriptCmd(recordScriptCmd)
                            vm.postLastTime()
                        }
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        vm.stopRecord()
                        smallWindowBinding?.tvWinB?.setRunFloatState(false)
                        Toast.makeText(
                            this@RecordScriptActivityCompose,
                            "ROOT 录制失败：$message",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            ignorePoint = { point -> recordPanelRect.get().contains(point.x, point.y) }
        )
        if (ok) {
            smallWindowBinding?.tvWinB?.setRunFloatState(true)
            Toast.makeText(this, "开始被动录制；点击红色“停止”结束", Toast.LENGTH_SHORT).show()
        } else {
            vm.stopRecord()
            smallWindowBinding?.tvWinB?.setRunFloatState(false)
            Toast.makeText(this, "无法启动触摸录制：${rootTouchRecorder.diagnosticText()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopPassiveRecord() {
        rootTouchRecorder.stop()
        // stop() 会等待读取线程把管道残余事件全部 post 到 mainHandler；
        // 这里把 vm.stopRecord() 也排进 mainHandler 队尾，确保先排队的最后几条点击指令不会被 if (vm.isRecord) 拦掉。
        mainHandler.post {
            viewModel?.stopRecord()
        }
        smallWindowBinding?.tvWinB?.setRunFloatState(false)
    }

    private fun updateRecordPanelRect() {
        val binding = smallWindowBinding ?: return
        if (smallWindowsHelper?.isShow != true || binding.root.width <= 0 || binding.root.height <= 0) {
            recordPanelRect.set(Rect())
            return
        }
        val location = IntArray(2)
        binding.root.getLocationOnScreen(location)
        recordPanelRect.set(
            Rect(
                location[0],
                location[1],
                location[0] + binding.root.width,
                location[1] + binding.root.height
            )
        )
    }

    private fun initPlaySmallWindows() {
        playSmallWindowsHelper = SmallWindowsHelper(this)
        val helper = playSmallWindowsHelper ?: return
        windowBtnBinding = WindowBBinding.inflate(layoutInflater)
        val binding = windowBtnBinding ?: return
        binding.tvWinB.setRunFloatState(
            false,
            startText = currentRecordScriptName.ifBlank {
                viewModel?.recordScriptBean?.name?.takeIf { !it.isNullOrBlank() } ?: "录制脚本"
            }
        )
        binding.root.bindMovableRunButton(
            textView = binding.tvWinB,
            windowManager = helper.mWindowManager,
            layoutParams = helper.mLayoutParams,
            onClick = {
                if (isRun) {
                    isRun = false
                    binding.tvWinB.setRunFloatState(false)
                } else if (viewModel?.data.isNullOrEmpty()) {
                    Toast.makeText(this, "没有脚本数据", Toast.LENGTH_SHORT).show()
                } else {
                    isRun = true
                    if (!isRun) return@bindMovableRunButton
                    binding.tvWinB.setRunFloatState(true)
                    viewModel?.singleThreadExecutor?.execute(playRunnable)
                }
            },
            positionKey = "record_editor_play"
        )
    }

    private val playRunnable = Runnable {
        viewModel?.playScript()
        isRun = false
        mainHandler.post { windowBtnBinding?.tvWinB?.setRunFloatState(false) }
    }

    private fun openRecordWindow() {
        if (!MyService.isStart()) {
            Toast.makeText(this, "请先授权 ROOT 或 Shizuku，再打开录制悬浮窗。", Toast.LENGTH_LONG).show()
            return
        }
        showSmallWindows()
        playSmallWindowsHelper?.hide()
        isRun = false
    }

    private fun togglePlayWindow() {
        if (playSmallWindowsHelper?.isShow == true) {
            playSmallWindowsHelper?.hide()
        } else {
            showPlayWindow()
            closeSmallWindow()
        }
    }

    private fun showSmallWindows() {
        if (!SmallWindowsHelper.requestPermission(this)) return
        val helper = smallWindowsHelper ?: return
        val binding = smallWindowBinding ?: return
        if (helper.root == null) helper.attach(binding.root) else helper.show()
        binding.root.post { updateRecordPanelRect() }
    }

    private fun showPlayWindow() {
        if (!SmallWindowsHelper.requestPermission(this)) return
        val helper = playSmallWindowsHelper ?: return
        val root = windowBtnBinding?.root ?: return
        if (helper.root == null) helper.attach(root) else helper.show()
    }

    private fun closeSmallWindow() {
        stopPassiveRecord()
        smallWindowsHelper?.hide()
        recordPanelRect.set(Rect())
    }

    private fun playNotTouch() {
        val helper = playSmallWindowsHelper ?: return
        helper.setTouchEnable(false)
        windowBtnBinding?.tvWinB?.alpha = 0.45f
    }

    private fun playCanTouch() {
        val helper = playSmallWindowsHelper ?: return
        helper.setTouchEnable(true)
        windowBtnBinding?.tvWinB?.alpha = 1.0f
    }

    private fun saveScript(name: String) {
        if (viewModel?.isRecord == true) stopPassiveRecord()
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            Toast.makeText(this, "请输入脚本名", Toast.LENGTH_LONG).show()
            return
        }
        viewModel?.let { vm ->
            vm.singleThreadExecutor.execute {
                if (vm.isScriptNameDuplicate(this, trimmedName)) {
                    mainHandler.post {
                        Toast.makeText(this, "脚本名称「$trimmedName」已存在，请使用唯一名称", Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }
                vm.saveScriptBlocking(this, trimmedName, 1.0f, 1.0f)
                mainHandler.post {
                    Toast.makeText(this, "保存成功", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SmallWindowsHelper.onActivityResult(this, requestCode, resultCode, data)
    }

    override fun onDestroy() {
        dismissCoordinatePicker()
        super.onDestroy()
        isRun = false
        rootTouchRecorder.stop()
        smallWindowsHelper?.hide()
        playSmallWindowsHelper?.hide()
    }

    // 播放已有录制脚本时仍复用 RecordScriptExecutor 接口。
    override fun isRun(): Boolean = isRun

    override fun preDispatchGesture(x: Int, y: Int): Boolean {
        return MainThreadSync.call {
            val tv = windowBtnBinding?.tvWinB ?: return@call false
            val location = IntArray(2)
            tv.getLocationOnScreen(location)
            if (x >= location[0] && x <= location[0] + tv.width &&
                y >= location[1] && y <= location[1] + tv.height
            ) {
                playNotTouch()
                true
            } else {
                false
            }
        }
    }

    override fun dispatchGesture(position: Int, path: Path, duration: Int) {
        if (MyService.isStart()) MyService.myService.dispatchGestureAndWait(path, duration) { isRun }
    }

    override fun endDispatchGesture() {
        windowBtnBinding?.root?.post { playCanTouch() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScriptEditScreen(
    isEdit: Boolean,
    activity: RecordScriptActivityCompose,
    onBack: () -> Unit,
    onOpenRecordWindow: () -> Unit,
    onPlay: () -> Unit,
    onScriptNameChange: (String) -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel = ViewModelProvider(activity)[RecordScriptViewModel::class.java]
    var scriptName by remember { mutableStateOf("") }
    val commands = remember { viewModel.data }
    val owner = LocalLifecycleOwner.current

    // 插入位置：-1 表示追加到末尾，>=0 表示在该下标上方插入
    var currentInsertIndex by remember { mutableIntStateOf(-1) }
    // 正在编辑的指令下标：-1 表示新建插入，>=0 表示编辑已有指令
    var editingIndex by remember { mutableIntStateOf(-1) }

    var showInsertMenu by remember { mutableStateOf(false) }
    var showDelayDialog by remember { mutableStateOf(false) }
    var showRecordedGestureDialog by remember { mutableStateOf(false) }
    var showManualClickDialog by remember { mutableStateOf(false) }
    var showManualSwipeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MyLiveData.getInstance().with("RecordScriptEdit", RecordScriptBean::class.java)
            .observe(owner) { bean ->
                bean?.let {
                    scriptName = it.name ?: ""
                    onScriptNameChange(scriptName)
                }
            }
    }

    // 1. 选择要插入的普通指令类型菜单
    if (showInsertMenu) {
        val options = listOf("普通点击", "普通滑动", "延迟指令")
        AlertDialog(
            onDismissRequest = { showInsertMenu = false },
            title = { Text("插入普通指令", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEachIndexed { idx, label ->
                        Surface(
                            onClick = {
                                showInsertMenu = false
                                editingIndex = -1
                                when (idx) {
                                    0 -> showManualClickDialog = true
                                    1 -> showManualSwipeDialog = true
                                    2 -> showDelayDialog = true
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInsertMenu = false }) { Text("取消") }
            }
        )
    }

    // 2. 延迟指令弹窗（新建或编辑）
    if (showDelayDialog) {
        val editingCmd = commands.getOrNull(editingIndex)
        var delayText by remember(editingIndex, showDelayDialog) {
            mutableStateOf((editingCmd?.delayed?.coerceAtLeast(0) ?: 500).toString())
        }
        AlertDialog(
            onDismissRequest = {
                showDelayDialog = false
                editingIndex = -1
            },
            title = { Text(if (editingCmd != null) "编辑延迟指令" else "插入延迟指令") },
            text = {
                OutlinedTextField(
                    value = delayText,
                    onValueChange = { v -> if (v.isEmpty() || v.all { it.isDigit() }) delayText = v },
                    label = { Text("延迟时长(ms)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val delayVal = delayText.toIntOrNull()?.coerceIn(0, 3_600_000) ?: 0
                    if (editingCmd != null && editingIndex in commands.indices) {
                        viewModel.updateDelay(editingIndex, delayVal)
                    } else {
                        val cmd = RecordScriptCmd.createDelayCMD(delayVal, System.currentTimeMillis().toDate())
                        viewModel.insertRecordScriptCmd(currentInsertIndex, cmd)
                    }
                    showDelayDialog = false
                    editingIndex = -1
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDelayDialog = false
                    editingIndex = -1
                }) { Text("取消") }
            }
        )
    }

    // 3. 录制手势编辑弹窗（严格只允许修改重复次数与自定义名称；坐标、路径、执行时长均不可改）
    if (showRecordedGestureDialog && editingIndex in commands.indices) {
        val cmd = commands[editingIndex]
        val defaultTypeName = cmd.defaultGestureTypeName
        var customName by remember(editingIndex) { mutableStateOf(cmd.name ?: "") }
        var repeatText by remember(editingIndex) {
            mutableStateOf(cmd.repeatCount.coerceAtLeast(1).toString())
        }
        AlertDialog(
            onDismissRequest = {
                showRecordedGestureDialog = false
                editingIndex = -1
            },
            title = { Text("编辑录制手势（默认：$defaultTypeName）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "录制的手势仅支持修改自定义名称和重复次数；坐标、轨迹和执行时长保持录制原值不可修改。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("自定义名称(选填，不填默认显示「$defaultTypeName」)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repeatText,
                        onValueChange = { v -> if (v.isEmpty() || v.all { it.isDigit() }) repeatText = v },
                        label = { Text("重复次数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = customName.trim()
                    if (trimmed.isNotEmpty() && viewModel.isCmdNameDuplicate(trimmed, editingIndex)) {
                        Toast.makeText(context, "指令名称「$trimmed」已存在，请使用唯一名称", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val repeatVal = (repeatText.toIntOrNull() ?: 1).coerceIn(1, 9999)
                    viewModel.updateRecordedGesture(editingIndex, repeatVal, trimmed)
                    showRecordedGestureDialog = false
                    editingIndex = -1
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRecordedGestureDialog = false
                    editingIndex = -1
                }) { Text("取消") }
            }
        )
    }

    // 4. 手动插入/编辑“普通点击”弹窗（可修改全部详细参数）
    if (showManualClickDialog) {
        val editingCmd = commands.getOrNull(editingIndex)?.takeIf { it.isManualClick }
        val firstBean = editingCmd?.path?.firstOrNull()
        var customName by remember(editingIndex, showManualClickDialog) {
            mutableStateOf(editingCmd?.name ?: "")
        }
        var xText by remember(editingIndex, showManualClickDialog) {
            mutableStateOf((firstBean?.x ?: 0).toString())
        }
        var yText by remember(editingIndex, showManualClickDialog) {
            mutableStateOf((firstBean?.y ?: 0).toString())
        }
        var durationText by remember(editingIndex, showManualClickDialog) {
            mutableStateOf((editingCmd?.duration ?: 50).toString())
        }
        var delayText by remember(editingIndex, showManualClickDialog) {
            mutableStateOf((editingCmd?.delayed ?: 0).toString())
        }
        var repeatText by remember(editingIndex, showManualClickDialog) {
            mutableStateOf((editingCmd?.repeatCount?.coerceAtLeast(1) ?: 1).toString())
        }

        LaunchedEffect(showManualClickDialog) {
            activity.onCoordinatePicked = { px, py ->
                xText = px.toString()
                yText = py.toString()
            }
        }

        AlertDialog(
            onDismissRequest = {
                activity.dismissCoordinatePicker()
                showManualClickDialog = false
                editingIndex = -1
            },
            title = { Text(if (editingCmd != null) "编辑普通点击" else "插入普通点击") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("自定义名称(选填，不填默认显示「点击」)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = { activity.alertCoordinatePicker() }) {
                        Text("坐标获取")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = xText,
                            onValueChange = { xText = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("X") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = yText,
                            onValueChange = { yText = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("Y") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { c -> c.isDigit() } },
                        label = { Text("执行时长(ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                        label = { Text("前置延时(ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repeatText,
                        onValueChange = { repeatText = it.filter { c -> c.isDigit() } },
                        label = { Text("重复次数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = customName.trim()
                    if (trimmed.isNotEmpty() && viewModel.isCmdNameDuplicate(trimmed, editingIndex)) {
                        Toast.makeText(context, "指令名称「$trimmed」已存在，请使用唯一名称", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val cmd = RecordScriptCmd.createManualClickCMD(
                        xText.toIntOrNull() ?: 0,
                        yText.toIntOrNull() ?: 0,
                        durationText.toIntOrNull() ?: 50,
                        delayText.toIntOrNull() ?: 0,
                        (repeatText.toIntOrNull() ?: 1).coerceIn(1, 9999),
                        trimmed,
                        editingCmd?.time?.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toDate()
                    )
                    if (editingCmd != null && editingIndex in commands.indices) {
                        viewModel.replaceRecordScriptCmd(editingIndex, cmd)
                    } else {
                        viewModel.insertRecordScriptCmd(currentInsertIndex, cmd)
                    }
                    activity.dismissCoordinatePicker()
                    showManualClickDialog = false
                    editingIndex = -1
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    activity.dismissCoordinatePicker()
                    showManualClickDialog = false
                    editingIndex = -1
                }) { Text("取消") }
            }
        )
    }

    // 5. 手动插入/编辑“普通滑动”弹窗（可修改全部详细参数）
    if (showManualSwipeDialog) {
        val editingCmd = commands.getOrNull(editingIndex)?.takeIf { it.isManualSwipe }
        val startBean = editingCmd?.path?.getOrNull(0)
        val endBean = editingCmd?.path?.getOrNull(1)
        var customName by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf(editingCmd?.name ?: "")
        }
        var x0Text by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((startBean?.x ?: 0).toString())
        }
        var y0Text by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((startBean?.y ?: 0).toString())
        }
        var x1Text by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((endBean?.x ?: 0).toString())
        }
        var y1Text by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((endBean?.y ?: 0).toString())
        }
        var durationText by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((editingCmd?.duration ?: 300).toString())
        }
        var delayText by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((editingCmd?.delayed ?: 0).toString())
        }
        var repeatText by remember(editingIndex, showManualSwipeDialog) {
            mutableStateOf((editingCmd?.repeatCount?.coerceAtLeast(1) ?: 1).toString())
        }
        var pickTarget by remember { mutableIntStateOf(0) }

        LaunchedEffect(showManualSwipeDialog) {
            activity.onCoordinatePicked = { px, py ->
                when (pickTarget) {
                    1 -> {
                        x0Text = px.toString()
                        y0Text = py.toString()
                    }
                    2 -> {
                        x1Text = px.toString()
                        y1Text = py.toString()
                    }
                }
                pickTarget = 0
            }
        }

        AlertDialog(
            onDismissRequest = {
                activity.dismissCoordinatePicker()
                showManualSwipeDialog = false
                editingIndex = -1
            },
            title = { Text(if (editingCmd != null) "编辑普通滑动" else "插入普通滑动") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("自定义名称(选填，不填默认显示「滑动」)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text("起点坐标", fontWeight = FontWeight.Bold)
                    Button(onClick = { pickTarget = 1; activity.alertCoordinatePicker() }) {
                        Text("坐标获取(起点)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = x0Text,
                            onValueChange = { x0Text = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("X0") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = y0Text,
                            onValueChange = { y0Text = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("Y0") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    Text("终点坐标", fontWeight = FontWeight.Bold)
                    Button(onClick = { pickTarget = 2; activity.alertCoordinatePicker() }) {
                        Text("坐标获取(终点)")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = x1Text,
                            onValueChange = { x1Text = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("X1") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = y1Text,
                            onValueChange = { y1Text = it.filter { c -> c.isDigit() || c == '-' } },
                            label = { Text("Y1") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { durationText = it.filter { c -> c.isDigit() } },
                        label = { Text("执行时长(ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = delayText,
                        onValueChange = { delayText = it.filter { c -> c.isDigit() } },
                        label = { Text("前置延时(ms)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = repeatText,
                        onValueChange = { repeatText = it.filter { c -> c.isDigit() } },
                        label = { Text("重复次数") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmed = customName.trim()
                    if (trimmed.isNotEmpty() && viewModel.isCmdNameDuplicate(trimmed, editingIndex)) {
                        Toast.makeText(context, "指令名称「$trimmed」已存在，请使用唯一名称", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
                    val cmd = RecordScriptCmd.createManualSwipeCMD(
                        x0Text.toIntOrNull() ?: 0,
                        y0Text.toIntOrNull() ?: 0,
                        x1Text.toIntOrNull() ?: 0,
                        y1Text.toIntOrNull() ?: 0,
                        durationText.toIntOrNull() ?: 300,
                        delayText.toIntOrNull() ?: 0,
                        (repeatText.toIntOrNull() ?: 1).coerceIn(1, 9999),
                        trimmed,
                        editingCmd?.time?.takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toDate()
                    )
                    if (editingCmd != null && editingIndex in commands.indices) {
                        viewModel.replaceRecordScriptCmd(editingIndex, cmd)
                    } else {
                        viewModel.insertRecordScriptCmd(currentInsertIndex, cmd)
                    }
                    activity.dismissCoordinatePicker()
                    showManualSwipeDialog = false
                    editingIndex = -1
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    activity.dismissCoordinatePicker()
                    showManualSwipeDialog = false
                    editingIndex = -1
                }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEdit) "编辑录制脚本" else "新建录制脚本") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(scriptName) }) {
                        Text("保存")
                    }
                }
            )
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = scriptName,
                onValueChange = { value ->
                    scriptName = value
                    onScriptNameChange(value)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("脚本名称（需唯一）") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenRecordWindow,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("打开录制窗口")
                }
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("播放")
                }
            }

            FilledTonalButton(
                onClick = {
                    currentInsertIndex = -1
                    editingIndex = -1
                    showInsertMenu = true
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("末尾插入普通指令（点击 / 滑动 / 延迟）")
            }

            Divider()

            Text(
                "命令列表 (${commands.size}条)",
                style = MaterialTheme.typography.titleMedium
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(commands) { index, cmd ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = getCmdDescribe(index, cmd),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        currentInsertIndex = index
                                        editingIndex = -1
                                        showInsertMenu = true
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("插入 ↑", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        editingIndex = index
                                        when {
                                            cmd.type == RecordScriptCmd.Type.Delay -> {
                                                showDelayDialog = true
                                            }
                                            cmd.isManualClick -> {
                                                showManualClickDialog = true
                                            }
                                            cmd.isManualSwipe -> {
                                                showManualSwipeDialog = true
                                            }
                                            else -> {
                                                showRecordedGestureDialog = true
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("编辑", fontSize = 12.sp)
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                IconButton(
                                    onClick = {
                                        if (index > 0) {
                                            viewModel.moveRecordScriptCmd(index, index - 1)
                                        }
                                    },
                                    enabled = index > 0
                                ) {
                                    Icon(Icons.Default.Upload, contentDescription = "上移")
                                }

                                IconButton(
                                    onClick = {
                                        if (index < commands.size - 1) {
                                            viewModel.moveRecordScriptCmd(index, index + 1)
                                        }
                                    },
                                    enabled = index < commands.size - 1
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "下移")
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.removeRecordScriptCmdAt(index)
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getCmdDescribe(index: Int, cmd: RecordScriptCmd): String {
    val prefix = "${index + 1}.  "
    return when (cmd.type) {
        RecordScriptCmd.Type.Gesture -> {
            val repeat = cmd.repeatCount.coerceAtLeast(1)
            when {
                // 填写了自定义名称时直接显示自定义名称（方案 A）
                cmd.hasCustomName() -> {
                    if (repeat > 1) "$prefix${cmd.name} · 重复 $repeat 次"
                    else "$prefix${cmd.name}"
                }
                // 手动插入的普通点击
                cmd.isManualClick -> {
                    val p = cmd.path?.firstOrNull()
                    val coord = if (p != null) "(${p.x}, ${p.y})" else "(0, 0)"
                    "${prefix}点击 $coord · 时长 ${cmd.duration}ms · 延时 ${cmd.delayed}ms · 重复 $repeat 次"
                }
                // 手动插入的普通滑动
                cmd.isManualSwipe -> {
                    val p0 = cmd.path?.getOrNull(0)
                    val p1 = cmd.path?.getOrNull(1)
                    val c0 = if (p0 != null) "(${p0.x},${p0.y})" else "(0,0)"
                    val c1 = if (p1 != null) "(${p1.x},${p1.y})" else "(0,0)"
                    "${prefix}滑动 $c0→$c1 · 时长 ${cmd.duration}ms · 延时 ${cmd.delayed}ms · 重复 $repeat 次"
                }
                // 录制手势：默认名称根据轨迹自动显示为 点击 / 滑动 / 轨迹手势
                else -> {
                    "$prefix${cmd.defaultGestureTypeName} · 重复 $repeat 次 · ${cmd.time}"
                }
            }
        }
        RecordScriptCmd.Type.Delay ->
            prefix + "延迟 ${cmd.delayed.coerceAtLeast(0)} ms · ${cmd.time}"
        else -> prefix + "未知命令 · ${cmd.time}"
    }
}
