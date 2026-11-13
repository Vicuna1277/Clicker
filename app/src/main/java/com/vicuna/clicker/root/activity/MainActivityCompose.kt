package com.vicuna.clicker.root.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vicuna.clicker.root.MyService
import com.vicuna.clicker.root.PowerKeyObserver
import com.vicuna.clicker.root.R
import com.vicuna.clicker.root.SmallWindowView
import com.vicuna.clicker.root.Util
import com.vicuna.clicker.root.helper.KeyFloatWindowManager
import com.vicuna.clicker.root.helper.onClick
import com.vicuna.clicker.root.helper.setOnTouchClick
import com.vicuna.clicker.root.helper.FLOAT_RESTART_DEBOUNCE_MS
import com.vicuna.clicker.root.helper.bindMovableCoordinatePicker
import com.vicuna.clicker.root.helper.bindMovableRunButton
import com.vicuna.clicker.root.helper.centerOnScreen
import com.vicuna.clicker.root.helper.setRunFloatState
import com.vicuna.clicker.root.helper.smallWindowManager
import com.vicuna.clicker.root.ui.theme.ClickerTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivityCompose : ComponentActivity() {

    @Volatile private var isRun = false
        set(value) {
            if (value && !com.vicuna.clicker.root.api.ExecutionGate.acquire(this)) {
                android.widget.Toast.makeText(this, "已有脚本正在执行", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            field = value
            if (!value) com.vicuna.clicker.root.api.ExecutionGate.release(this)
        }

    private val clickGeneration = java.util.concurrent.atomic.AtomicLong()
    private var stopTime = 0L
    private val singleThreadExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var powerKeyObserver: PowerKeyObserver? = null
    private var windowView: SmallWindowView? = null
    private var btnWindowView: SmallWindowView? = null
    private var tvWinB: TextView? = null
    private var wm: WindowManager? = null
    private var mLayoutParams: WindowManager.LayoutParams? = null
    private var btnLayoutParams: WindowManager.LayoutParams? = null
    private var isShow by mutableStateOf(false)


    // 主线程 Handler，用于更新 UI
    private val mainHandler = Handler(Looper.getMainLooper())

    // Compose 状态：让悬浮窗按钮文字可以实时更新
    var isRunning by mutableStateOf(false)
        private set

    var clickCount by mutableStateOf("0")
        private set

    var clickInterval by mutableStateOf("1000")
        private set

    var showAccessibilityDialog by mutableStateOf(false)
    var rootStateVersion by mutableStateOf(0)
    private var rootRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClickerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                isFloatingWindowShow = isShow,
                clickCount = clickCount,
                clickInterval = clickInterval,
                rootStateVersion = rootStateVersion,
                showAccessibilityDialog = showAccessibilityDialog,
                onDismissAccessibilityDialog = { showAccessibilityDialog = false },
                onOpenAccessibility = {
                    showAccessibilityDialog = false
                    openAccessibility()
                },
                onStartClicker = { startClicker() },
                onOpenScriptList = { startScriptList() },
                onOpenRecordScript = { startRecordScript() },
                onOpenScriptGroup = { startScriptGroup() },
                onOpenKeyBinding = { startKeyBinding() },
                onCountChange = { clickCount = it },
                onIntervalChange = { clickInterval = it }
            )
                }
            }
        }

        openAccessibility(automatic = true)
        powerKeyObserver = PowerKeyObserver(this).apply {
            startListen()
            setHomeKeyListener {
                isRun = false
                isRunning = false
                resetBtnText()
            }
        }
    }

    private fun openAccessibility(automatic: Boolean = false) {
        if (rootRequestInFlight) return
        if (MyService.isStart()) { rootStateVersion++; return }
        rootRequestInFlight = true
        Thread {
            val ok = runCatching { MyService.ensureRoot(applicationContext) }.getOrDefault(false)
            runOnUiThread {
                rootRequestInFlight = false
                if (!isFinishing && !isDestroyed) {
                    rootStateVersion++
                    showAccessibilityDialog = false
                    if (!automatic || !ok) Toast.makeText(
                        this,
                        if (ok) "输入后端已就绪：${MyService.backendName()}" else "输入未就绪，请检查 ROOT 或 Shizuku 授权",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun startClicker() {
        if (!isShow) {
            showFloatWindows()
        } else {
            if (isRun) {
                onBtnWinBClick()
            }
            hideFloatWindows()
        }
    }

    // region 悬浮窗初始化

    @SuppressLint("WrongConstant")
    private fun initSmallViewLayout() {
        wm = smallWindowManager()

        // 初始化位置选择悬浮窗 (window_a)：使用 WindowMetrics + TOP|START，初始位于屏幕中心。
        windowView = LayoutInflater.from(this).inflate(R.layout.window_a, null) as SmallWindowView
        mLayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, 8, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mLayoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        windowView?.setWm(wm)
        windowView?.setWmParams(mLayoutParams)
        if (windowView != null && wm != null && mLayoutParams != null) {
            windowView?.bindMovableCoordinatePicker(
                windowManager = wm!!,
                layoutParams = mLayoutParams!!
            )
        }

        // 初始化启动/停止按钮悬浮窗 (window_b)：统一使用可拖动、可记忆位置的默认按钮。
        btnWindowView =
            LayoutInflater.from(this).inflate(R.layout.window_b, null) as SmallWindowView
        btnLayoutParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, 8, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            btnLayoutParams?.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        btnWindowView?.enableMove = false
        btnWindowView?.setWm(wm)
        btnWindowView?.setWmParams(btnLayoutParams)

        // 启动/停止按钮：默认顶部居中，可拖动；开始绿边框，停止红边框。
        tvWinB = btnWindowView?.findViewById(R.id.tv_win_b)
        if (btnWindowView != null && tvWinB != null && wm != null && btnLayoutParams != null) {
            tvWinB?.setRunFloatState(false, startText = "点击")
            btnWindowView?.bindMovableRunButton(
                textView = tvWinB!!,
                windowManager = wm!!,
                layoutParams = btnLayoutParams!!,
                onClick = { onBtnWinBClick() },
                positionKey = "main_run"
            )
        }
    }

    @SuppressLint("WrongConstant")
    private fun alertWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                val intent = Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
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

        if (windowView?.windowId == null) {
            wm?.addView(windowView, mLayoutParams)
        }
        if (btnWindowView?.windowId == null) {
            wm?.addView(btnWindowView, btnLayoutParams)
        }
        resetBtnText()
        windowView?.setwmParamsFlags(8)
    }

    private fun dismissWindow() {
        if (windowView?.windowId != null) {
            try {
                wm?.removeView(windowView)
            } catch (_: Exception) {
            }
        }
        if (btnWindowView?.windowId != null) {
            try {
                wm?.removeView(btnWindowView)
            } catch (_: Exception) {
            }
        }
    }

    private fun showFloatWindows() {
        if (!MyService.isStart()) {
            Toast.makeText(this, "请先授权 ROOT 或 Shizuku", Toast.LENGTH_LONG).show()
            return
        }
        if (windowView == null) {
            initSmallViewLayout()
        }
        alertWindow()
        isShow = true
    }

    private fun hideFloatWindows() {
        isShow = false
        isRun = false
        isRunning = false
        dismissWindow()
    }

    // endregion

    // region 悬浮窗按钮点击逻辑

    @SuppressLint("WrongConstant")
    private fun onBtnWinBClick() {
        if (!isRun) {
            if (System.currentTimeMillis() - stopTime < FLOAT_RESTART_DEBOUNCE_MS) return
            isRun = true
            if (!isRun) return
            isRunning = true

            val point = windowView?.centerOnScreen()
            val x = point?.x ?: -1
            val y = point?.y ?: -1
            val screenBounds = wm?.maximumWindowMetrics?.bounds
            if (screenBounds == null || !screenBounds.contains(x, y)) {
                isRun = false
                isRunning = false
                Toast.makeText(this, "请先拖动选择点击位置", Toast.LENGTH_SHORT).show()
                return
            }
            if (!MyService.isStart()) {
                isRun = false
                isRunning = false
                Toast.makeText(
                    this,
                    "请先授权 ROOT 或 Shizuku，随后重新点击开始",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            tvWinB?.setRunFloatState(true)

            // 使位置选择悬浮窗不可触摸，防止误操作
            windowView?.setwmParamsFlags(24)

            val count = clickCount.toIntOrNull() ?: 0
            val interval = (clickInterval.toIntOrNull() ?: 1000).coerceAtLeast(10)

            val generation = clickGeneration.incrementAndGet()
            fun running() = isRun && clickGeneration.get() == generation
            singleThreadExecutor.execute {
                try {
                    Thread.sleep(50)
                    var completed = 0
                    while (running() && (count <= 0 || completed < count)) {
                        val started = android.os.SystemClock.uptimeMillis()
                        val ok = MyService.myService.dispatchGestureClickAndWait(
                            x.toFloat(), y.toFloat(), 32
                        ) { running() }
                        if (!ok) break
                        completed++
                        while (running()) {
                            val remaining = interval - (android.os.SystemClock.uptimeMillis() - started)
                            if (remaining <= 0) break
                            Thread.sleep(minOf(10L, remaining))
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    mainHandler.post {
                        if (clickGeneration.get() == generation) {
                            isRun = false
                            isRunning = false
                            resetBtnText()
                            if (isShow) windowView?.setwmParamsFlags(8)
                        }
                    }
                }
            }
        } else {
            stopTime = System.currentTimeMillis()
            isRun = false
            isRunning = false
            resetBtnText()
        }
    }

    private fun resetBtnText() {
        tvWinB?.setRunFloatState(false, startText = "点击")
    }

    // endregion

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "悬浮窗权限被拒绝", Toast.LENGTH_SHORT).show()
            } else {
                showWindow()
            }
        }
    }

    private fun startScriptList() {
        hideFloatWindows()
        startActivity(Intent(this, ScriptListActivityCompose::class.java))
    }

    private fun startRecordScript() {
        hideFloatWindows()
        startActivity(Intent(this, RecordScriptListActivityCompose::class.java))
    }

    private fun startScriptGroup() {
        hideFloatWindows()
        startActivity(Intent(this, ScriptGroupListActivityCompose::class.java))
    }

    private fun startKeyBinding() {
        hideFloatWindows()
        startActivity(Intent(this, KeyBindingListActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        isRun = false
        hideFloatWindows()
        powerKeyObserver?.stopListen()
        singleThreadExecutor.shutdownNow()
    }

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 2
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isFloatingWindowShow: Boolean,
    clickCount: String,
    clickInterval: String,
    showAccessibilityDialog: Boolean = false,
    onDismissAccessibilityDialog: () -> Unit = {},
    onOpenAccessibility: () -> Unit,
    onStartClicker: () -> Unit,
    onOpenScriptList: () -> Unit,
    onOpenRecordScript: () -> Unit,
    onOpenScriptGroup: () -> Unit,
    onOpenKeyBinding: () -> Unit,
    onCountChange: (String) -> Unit,
    onIntervalChange: (String) -> Unit,
    rootStateVersion: Int = 0
) {
    val isAccessibilityOn = remember(rootStateVersion) { MyService.isStart() }
    var selectedPage by rememberSaveable { mutableIntStateOf(0) }
    val homeScroll = rememberLazyListState()
    val featureScroll = rememberLazyListState()
    val reservedScroll = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var allowedPackages by remember { mutableStateOf(com.vicuna.clicker.root.api.AllowedCallers.packages(context)) }
    var packageInput by rememberSaveable { mutableStateOf("") }
    var shizukuState by remember { mutableStateOf(com.vicuna.clicker.root.input.ShizukuAccess.state()) }
    LaunchedEffect(Unit) {
        while (true) {
            shizukuState = com.vicuna.clicker.root.input.ShizukuAccess.state()
            kotlinx.coroutines.delay(1000)
        }
    }

    val pageNames = listOf("主页", "功能", "授权")
    val pageIcons = listOf(Icons.Filled.Home, Icons.Filled.Widgets, Icons.Filled.MoreHoriz)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedPage == 0) "Clicker" else pageNames[selectedPage], fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        bottomBar = {
            NavigationBar {
                pageNames.forEachIndexed { index, name ->
                    NavigationBarItem(
                        selected = selectedPage == index,
                        onClick = { selectedPage = index },
                        icon = { Icon(pageIcons[index], contentDescription = null) },
                        label = { Text(name) }
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = when (selectedPage) { 0 -> homeScroll; 1 -> featureScroll; else -> reservedScroll },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (selectedPage == 0) {
                // 品牌区
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.TouchApp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    "Clicker",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "自动化点击 · 手势模拟 · 脚本录制",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                                )
                            }
                        }
                    }
                }

                // ROOT 状态卡
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isAccessibilityOn) Color(0xFF2E7D32)
                                        else MaterialTheme.colorScheme.error
                                    )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("输入后端 · ROOT 优先", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (isAccessibilityOn) "已就绪，当前后端：${MyService.backendName()}"
                                    else "未授权，点击右侧按钮请求 ROOT",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!isAccessibilityOn) {
                                FilledTonalButton(onClick = onOpenAccessibility) {
                                    Text("开启")
                                }
                            }
                        }
                    }
                }

                // 连点器控制卡
                item {
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(if (shizukuState.startsWith("已授权")) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outline))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Shizuku 授权", style = MaterialTheme.typography.titleMedium)
                                Text(shizukuState, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("支持点击与回放 · 录制需设备权限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledTonalButton(onClick = {
                                Toast.makeText(context, com.vicuna.clicker.root.input.ShizukuAccess.request(), Toast.LENGTH_LONG).show()
                                shizukuState = com.vicuna.clicker.root.input.ShizukuAccess.state()
                            }) { Text(if (shizukuState.startsWith("已授权")) "检查" else "授权") }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Clicker", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "打开悬浮窗，拖动位置图标选择点击位置，再点击\"开始\"执行连点",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                OutlinedTextField(
                                    value = clickCount,
                                    onValueChange = { onCountChange(it.filter { c -> c.isDigit() }) },
                                    label = { Text("点击次数") },
                                    placeholder = { Text("0=无限") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = clickInterval,
                                    onValueChange = { onIntervalChange(it.filter { c -> c.isDigit() }) },
                                    label = { Text("间隔(ms)") },
                                    placeholder = { Text("≥10") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onStartClicker,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = if (isFloatingWindowShow) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ) else ButtonDefaults.buttonColors()
                            ) {
                                Icon(
                                    if (isFloatingWindowShow) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isFloatingWindowShow) "关闭悬浮窗" else "打开 Clicker",
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                    }
                }

                }
                if (selectedPage == 1) {
                    item { FeatureCard("普通脚本", "点击 · 手势 · 循环", Icons.Filled.List, onOpenScriptList, Modifier.fillMaxWidth()) }
                    item { FeatureCard("录制脚本", "录制操作回放", Icons.Filled.FiberManualRecord, onOpenRecordScript, Modifier.fillMaxWidth()) }
                    item { FeatureCard("自定义脚本", "脚本组合流程", Icons.Filled.Widgets, onOpenScriptGroup, Modifier.fillMaxWidth()) }
                    item { FeatureCard("自定义悬浮窗", "配置悬浮窗快捷按钮", Icons.Filled.Keyboard, onOpenKeyBinding, Modifier.fillMaxWidth()) }
                }
                if (selectedPage == 2) {
                    item {
                        Text("应用调用授权", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("允许列表内的应用按名称运行脚本，修改自动保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    item {
                        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = packageInput, onValueChange = { packageInput = it },
                                    label = { Text("应用包名") }, placeholder = { Text("例如 ink.roco.nearby") },
                                    singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                                FilledTonalButton(modifier = Modifier.height(48.dp), enabled = packageInput.isNotBlank(), onClick = {
                                    val name = packageInput.trim()
                                    if (com.vicuna.clicker.root.api.AllowedCallers.valid(name)) {
                                        allowedPackages = allowedPackages + name
                                        com.vicuna.clicker.root.api.AllowedCallers.save(context, allowedPackages)
                                        packageInput = ""
                                    } else Toast.makeText(context, "请输入有效的应用包名", Toast.LENGTH_SHORT).show()
                                }) { Text("添加") }
                            }
                        }
                    }
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("已授权应用 · ${allowedPackages.size}", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
                                if (allowedPackages.isEmpty()) {
                                    Text("暂无授权应用", modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                allowedPackages.sorted().forEach { packageName ->
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 16.dp))
                                    val appName = remember(packageName) {
                                        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
                                    }
                                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                            Text(appName.take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(appName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                            if(appName != packageName) Text(packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        }
                                        TextButton(onClick = {
                                            allowedPackages = allowedPackages - packageName
                                            com.vicuna.clicker.root.api.AllowedCallers.save(context, allowedPackages)
                                        }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                        }
                    }

                }
            item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }

        if (showAccessibilityDialog) {
            AlertDialog(
                onDismissRequest = onDismissAccessibilityDialog,
                title = { Text("ROOT 授权") },
                text = { Text("使用 ROOT 输入后端需要超级用户授权，是否现在请求授权？") },
                confirmButton = {
                    TextButton(onClick = onOpenAccessibility) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismissAccessibilityDialog) {
                        Text("取消")
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun FeatureCard(
        title: String,
        subtitle: String,
        icon: ImageVector,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Card(
            onClick = onClick,
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
