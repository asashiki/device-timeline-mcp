package com.devicetimeline.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.devicetimeline.agent.band.BandSample
import com.devicetimeline.agent.band.BandSync
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = object {
    val bg = Color(0xFFF6F7F5); val card = Color.White; val cardHi = Color(0xFFE5E9E7)
    val text = Color(0xFF15231F); val dim = Color(0xFF44564F); val faint = Color(0xFF52615A)
    val warm = Color(0xFF12534C); val cool = Color(0xFF12534C); val red = Color(0xFF933125); val green = Color(0xFF246348)
}
private val HibiColors = lightColorScheme(
    background = Ink.bg, surface = Ink.card, surfaceVariant = Ink.cardHi,
    primary = Ink.warm, onPrimary = Color.White, secondary = Ink.cool, onSecondary = Color.White,
    secondaryContainer = Color(0xFFDAEAE2), onSecondaryContainer = Ink.text,
    onBackground = Ink.text, onSurface = Ink.text, onSurfaceVariant = Ink.dim,
    error = Ink.red, outline = Color(0xFF77867E),
)


private data class PermSnapshot(
    val usage: Boolean = false,
    val battery: Boolean = false,
    val notifications: Boolean = false,
)

class MainActivity : ComponentActivity() {

    private val perms = mutableStateOf(PermSnapshot())
    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPerms() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            // Pairing data and encrypted state from the earlier test use the same package and key alias.
            withContext(Dispatchers.IO) { runCatching { BandSync.importPairing(this@MainActivity) } }
            renderScreen()
        }
    }

    private fun renderScreen() {
        setContent {
            androidx.compose.runtime.SideEffect {
                val bars = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                bars.isAppearanceLightStatusBars = true
                bars.isAppearanceLightNavigationBars = true
                window.statusBarColor = android.graphics.Color.rgb(246, 247, 245)
                window.navigationBarColor = android.graphics.Color.WHITE
            }
            MaterialTheme(colorScheme = HibiColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = Ink.bg) {
                    AgentScreen(
                        perms = perms.value,

                        onOpenUsage = { UsageTracker.openUsageAccessSettings(this) },
                        onOpenBattery = { openBatterySettings() },
                        onOpenNotif = { requestNotifications() },

                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The OS can delay package-replaced broadcasts. Opening the upgraded app
        // restores the user's existing recording setting through its normal service.
        if (SettingsStore(this).load().isRunningEnabled) {
            val resume = Intent(this, TrackingService::class.java).setAction(TrackingService.ACTION_START)
            runCatching { ContextCompat.startForegroundService(this, resume) }
                .onFailure { SettingsStore(this).appendLog("未能恢复监听，等待系统重试") }
        }
        refreshPerms()

    }

    private fun refreshPerms() {
        perms.value = PermSnapshot(
            usage = UsageTracker.hasUsageStatsPermission(this),
            battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
            notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else NotificationManagerCompat.from(this).areNotificationsEnabled(),
        )
    }

    private fun openBatterySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:$packageName".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
private fun AgentScreen(
    perms: PermSnapshot,
    onOpenUsage: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNotif: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val initial = remember { store.load() }

    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var reportBattery by remember { mutableStateOf(initial.reportBattery) }
    var autoStart by remember { mutableStateOf(initial.autoStartOnBoot) }
    var running by remember { mutableStateOf(initial.isRunningEnabled) }
    var serverOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.serverUrl.isBlank() || initial.token.isBlank()) }
    var status by remember { mutableStateOf("") }

    fun persist() {
        store.save(
            AgentSettings(
                serverUrl = serverUrl.trim(), token = token.trim(),
                heartbeatSeconds = initial.heartbeatSeconds,
                reportBattery = reportBattery, autoStartOnBoot = autoStart,
                isRunningEnabled = running, hcSyncEnabled = initial.hcSyncEnabled,
                hcSyncIntervalMinutes = initial.hcSyncIntervalMinutes,
                hcSyncRangeHours = initial.hcSyncRangeHours,
            )
        )
    }

    fun startService(action: String) {
        val i = Intent(context, TrackingService::class.java).apply { this.action = action }
        if (action == TrackingService.ACTION_START)
            ContextCompat.startForegroundService(context, i) else context.startService(i)
    }

    val configured = serverUrl.isNotBlank() && token.isNotBlank()

    var page by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if(configured) "今日" else "设置") }
    androidx.activity.compose.BackHandler(enabled = page != "今日") { page = "今日" }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    androidx.compose.runtime.key(page) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (page == "今日") "Hibi 日々" else page, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            androidx.compose.material3.TextButton(onClick = { page = if (page == "设置") "今日" else "设置" }) {
                Text(if (page == "设置") "完成" else "设置", fontSize = 17.sp)
            }
        }
        if (page != "设置") {
        BandMonitorCard(running=running, configured=configured, onStart = {
            running = true
            persist()
            startService(TrackingService.ACTION_START)
        })

        } else {

        SectionCard("后台记录") {
            ToggleRow("记录这台手机", running) { on ->
                if (!on) {
                    running = false
                    persist()
                    startService(TrackingService.ACTION_STOP)
                } else if (configured) {
                    running = true
                    persist()
                    startService(TrackingService.ACTION_START)
                } else {
                    serverOpen = true
                    status = "请先连接服务器"
                }
            }
            val band = remember { BandReporting(context.applicationContext) }
            var bandEnabled by remember { mutableStateOf(band.enabled) }
            val allowBand = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) { band.enable(true); bandEnabled = true; if (running) startService(TrackingService.ACTION_START) }
            }
            var hasBand by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { hasBand = withContext(Dispatchers.IO) { BandSync.hasPairing(context) } }
            if (hasBand) {
                ToggleRow("手环记录", bandEnabled) { on ->
                    if (on && !BandReporting.hasBluetooth(context) && Build.VERSION.SDK_INT >= 31) allowBand.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    else { band.enable(on); bandEnabled = on; if (running) startService(TrackingService.ACTION_START) }
                }
            }
            ToggleRow("开机后自动记录", autoStart) { autoStart = it; persist() }
            ToggleRow("手机电量", reportBattery) { reportBattery = it; persist() }
            LocationRow() { if (running) startService(TrackingService.ACTION_START) }
        }
        if (status.isNotBlank()) Text(status, color = Ink.dim, fontSize = 16.sp)
        if (!perms.usage || !perms.battery || !perms.notifications) SectionCard("后台权限") {
            if (!perms.usage) PermissionRow("使用记录", "记录前台应用", false, onOpenUsage)
            if (!perms.battery) PermissionRow("后台运行", "允许息屏后继续记录", false, onOpenBattery)
            if (!perms.notifications) PermissionRow("服务通知", "显示后台记录状态", false, onOpenNotif)
        }
        SectionCard("服务器") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if(configured) android.net.Uri.parse(serverUrl).host ?: "已配置" else "尚未连接", fontSize=17.sp)
                androidx.compose.material3.TextButton(onClick={serverOpen=!serverOpen}) { Text(if(serverOpen) "收起" else "编辑") }
            }
            if (serverOpen) {
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("服务器地址") }, placeholder = { Text("https://hibi.example.org") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("设备 Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
                Button(onClick={persist();serverOpen=false;status="设置已保存"},enabled=configured) { Text("保存连接") }
            }
        }
        }
        Spacer(Modifier.height(8.dp))
    }
    }

    }
}

@Composable
private fun BandMonitorCard(
    running: Boolean, configured: Boolean, onStart: () -> Unit,
) {
    val context = LocalContext.current
    val report = remember { BandReporting(context.applicationContext) }
    val revision by BandReporting.revision.collectAsState()
    var enabled by remember { mutableStateOf(report.enabled) }
    var bound by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf(report.status()) }
    var hint by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf<Map<String, BandSample>>(emptyMap()) }
    var hourlySteps by remember { mutableStateOf<List<Int?>>(emptyList()) }
    LaunchedEffect(revision) {
        hourlySteps = withContext(Dispatchers.IO) { BandSync.stepsByHour(context) }
        latest = withContext(Dispatchers.IO) { BandSync.latestByType(context).associateBy { it.type } }
        withContext(Dispatchers.IO) { BandSync.hasPairing(context) }.let { bound = it }
        enabled = report.enabled
        info = report.status()
    }
    fun start() {
        if (!bound || !configured) return

        report.enable(true)
        enabled = true
        hint = ""
        onStart()
    }
    val pairingScope = rememberCoroutineScope()
    val choosePairing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pairingScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BandSync.importPairingDocument(context, input)
                    } ?: error("Unable to read the pairing file")
                }.isSuccess
            }
            if (imported) bound = true
            hint = if (imported) "配对资料已保存，可以开启手环监测。" else "文件未能导入，请检查型号和配对资料格式。"
        }
    }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else hint = "打开手环监测需要附近设备权限。"
    }
    val link by com.devicetimeline.agent.band.BandLinkClient.state.collectAsState()
        val text = when {
            !bound -> "等待导入已配对的手环"
            !configured -> "请先保存服务器连接"
            enabled && !running -> "监听已暂停"
            !enabled -> "手环监测已关闭"
            !BandReporting.hasBluetooth(context) -> "需要附近设备权限才能读取手环"
            info.note == "手机蓝牙未开启" -> "请打开手机蓝牙，下次会自动同步"
            else -> link.note
        }
        val lastSleep = listOfNotNull(latest["sleep_session"], latest["sleep_band_interval"])
            .maxWithOrNull(compareBy<BandSample> { it.recordedAt }.thenBy { it.type == "sleep_session" })
        BandHeader(link, text)
        WristOverview(latest["heart_rate"], latest["steps"], hourlySteps, lastSleep, latest["oxygen_saturation"])
        if (hint.isNotEmpty()) Text(hint, color = Ink.warm, fontSize = 15.sp)
        if (!bound && Build.VERSION.SDK_INT >= 31) {
            OutlinedButton(onClick = { choosePairing.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) {
                Text("导入我的手环配对文件")
            }
        }
        if (!enabled || !running) {
            Button(enabled = configured && bound && Build.VERSION.SDK_INT >= 31,
                onClick = {
                    if (BandReporting.hasBluetooth(context)) start()
                    else btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) { Text("开启手环监测") }
        }
}

@Composable
private fun LocationRow(refresh: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(PhoneLocation.enabled(ctx)) }
    var permission by remember { mutableStateOf(PhoneLocation.hasPermission(ctx)) }
    val currentRefresh by rememberUpdatedState(refresh)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permission = PhoneLocation.hasPermission(ctx)
                if (enabled && permission) currentRefresh()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun openLocationSettings() {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${ctx.packageName}".toUri()))
    }
    val allowBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = PhoneLocation.hasPermission(ctx)
        if(permission) refresh()
    }
    val allowLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permission = PhoneLocation.hasPermission(ctx)
        if (permission) refresh()
        else if(granted && Build.VERSION.SDK_INT==29) allowBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else if(granted && Build.VERSION.SDK_INT>=30) openLocationSettings()
    }
    ToggleRow("手机位置",enabled) { on ->
        enabled = on
        PhoneLocation.setEnabled(ctx,on)
        if (on && !PhoneLocation.whenInUse(ctx)) allowLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        else if(on && !PhoneLocation.hasPermission(ctx)) openLocationSettings()
        else refresh()
    }
    if(enabled && !permission) {
        Text("后台记录需要「始终允许」位置权限。",fontSize=15.sp,color=Ink.dim)
        androidx.compose.material3.TextButton(onClick={openLocationSettings()}) { Text("打开位置权限") }
    } else if(enabled) Text("约每 15 分钟记录大致位置。",fontSize=15.sp,color=Ink.dim)
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Ink.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Card(
            colors = CardDefaults.cardColors(containerColor = Ink.card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(desc, color = Ink.faint, fontSize = 15.sp)
        }
        if (granted) {
            Text("✓ 已授予", color = Ink.green, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        } else {
            OutlinedButton(
                onClick = onGrant,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.warm),
            ) { Text("去授予") }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value=checked, role=androidx.compose.ui.semantics.Role.Switch, onValueChange=onChange).padding(vertical=4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 17.sp)
        Switch(checked = checked, onCheckedChange = null)
    }
}
