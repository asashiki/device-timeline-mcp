package com.devicetimeline.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Asashiki "Ink Night" palette — keeps the agent in the same visual system as
// the dashboard and console.
private val Ink = object {
    val bg = Color(0xFF0B0D14); val card = Color(0xFF1A1D27); val cardHi = Color(0xFF22252F)
    val text = Color(0xFFE8E2D4); val dim = Color(0xFF8C8E96); val faint = Color(0xFF565862)
    val warm = Color(0xFFD89870); val cool = Color(0xFF7FA8B5); val red = Color(0xFFC97064); val green = Color(0xFF85B58E)
}
private val HibiColors = darkColorScheme(
    background = Ink.bg, surface = Ink.card, surfaceVariant = Ink.cardHi,
    primary = Ink.warm, onPrimary = Ink.bg, secondary = Ink.cool, onSecondary = Ink.bg,
    onBackground = Ink.text, onSurface = Ink.text, onSurfaceVariant = Ink.dim,
    error = Ink.red, outline = Color(0x26E8E2D4),
)

private enum class HcStatus { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

private data class PermSnapshot(
    val usage: Boolean = false,
    val battery: Boolean = false,
    val notifications: Boolean = false,
)

class MainActivity : ComponentActivity() {

    private val hcPermissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )

    private val perms = mutableStateOf(PermSnapshot())
    private val hcGranted = mutableStateOf(false)
    private val hcStatus = mutableStateOf(HcStatus.UNAVAILABLE)

    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hcGranted.value = granted.containsAll(hcPermissions)
        refreshHealth()
        // Granting HC permission is the moment health data becomes readable —
        // kick an immediate sync so data shows up without waiting for the cycle.
        if (hcGranted.value) HealthSyncScheduler.runNow(this)
    }

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
        setContent {
            MaterialTheme(colorScheme = HibiColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(
                        perms = perms.value,
                        hcStatus = hcStatus.value,
                        hcGranted = hcGranted.value,
                        onOpenUsage = { UsageTracker.openUsageAccessSettings(this) },
                        onOpenBattery = { openBatterySettings() },
                        onOpenNotif = { requestNotifications() },
                        onRequestHc = { requestHealthPermissions() },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPerms()
        refreshHealth()
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

    private fun refreshHealth() {
        val status = when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> HcStatus.AVAILABLE
            HealthConnectClient.SDK_AVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.NEEDS_UPDATE
            else -> HcStatus.UNAVAILABLE
        }
        hcStatus.value = status
        if (status != HcStatus.AVAILABLE) { hcGranted.value = false; return }
        lifecycleScope.launch {
            hcGranted.value = runCatching {
                HealthConnectClient.getOrCreate(this@MainActivity)
                    .permissionController.getGrantedPermissions().containsAll(hcPermissions)
            }.getOrDefault(false)
        }
    }

    private fun requestHealthPermissions() {
        when (hcStatus.value) {
            HcStatus.AVAILABLE -> hcPermissionLauncher.launch(hcPermissions)
            // No Health Connect on this device (Android <14 ships it as a Play app) →
            // send the user to install/update it instead of silently doing nothing.
            HcStatus.NEEDS_UPDATE, HcStatus.UNAVAILABLE -> openHealthConnectInstall()
        }
    }

    private fun openHealthConnectInstall() {
        val pkg = "com.google.android.apps.healthdata"
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW,
                "https://play.google.com/store/apps/details?id=$pkg".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
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
    hcStatus: HcStatus,
    hcGranted: Boolean,
    onOpenUsage: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenNotif: () -> Unit,
    onRequestHc: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val initial = remember { store.load() }

    var serverUrl by remember { mutableStateOf(initial.serverUrl) }
    var token by remember { mutableStateOf(initial.token) }
    var heartbeat by remember { mutableStateOf(initial.heartbeatSeconds.toString()) }
    var reportBattery by remember { mutableStateOf(initial.reportBattery) }
    var autoStart by remember { mutableStateOf(initial.autoStartOnBoot) }
    var running by remember { mutableStateOf(initial.isRunningEnabled) }
    var hcSyncEnabled by remember { mutableStateOf(initial.hcSyncEnabled) }
    var status by remember { mutableStateOf("") }

    fun persist() {
        store.save(
            AgentSettings(
                serverUrl = serverUrl.trim(), token = token.trim(),
                heartbeatSeconds = heartbeat.toIntOrNull() ?: 30,
                reportBattery = reportBattery, autoStartOnBoot = autoStart,
                isRunningEnabled = running, hcSyncEnabled = hcSyncEnabled,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("HIBI · DEVICE TIMELINE", color = Ink.warm, fontSize = 11.sp,
                letterSpacing = 2.6.sp, fontWeight = FontWeight.Medium)
            Text("Hibi 日々", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }

        // ── Status card (primary) ─────────────────────────────────────────────
        StatusCard(running = running, configured = configured)

        // ── Primary action: Start / Stop ──────────────────────────────────────
        if (!running) {
            Button(
                onClick = {
                    running = true; persist(); startService(TrackingService.ACTION_START)
                    status = "已启动监听"
                },
                enabled = configured,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text(if (configured) "启动监听" else "请先填写下方服务器与 Token", fontSize = 16.sp) }
        } else {
            OutlinedButton(
                onClick = {
                    running = false; persist(); startService(TrackingService.ACTION_STOP)
                    status = "已停止监听"
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("停止监听", fontSize = 16.sp) }
        }
        if (status.isNotBlank()) Text(status, color = Ink.dim, fontSize = 12.sp)

        // ── Connection ────────────────────────────────────────────────────────
        SectionCard("连接") {
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("服务器地址") }, placeholder = { Text("https://link.asashiki.com") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("设备 Token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = heartbeat, onValueChange = { heartbeat = it.filter(Char::isDigit) },
                label = { Text("上报间隔秒（10–50）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            ToggleRow("上报电池信息", reportBattery) { reportBattery = it; persist() }
            ToggleRow("开机自启", autoStart) { autoStart = it; persist() }
            Button(onClick = { persist(); status = "已保存" }, modifier = Modifier.fillMaxWidth()) {
                Text("保存设置")
            }
        }

        // ── Permissions (rows collapse to a check once granted) ───────────────
        SectionCard("权限") {
            PermissionRow(
                title = "使用情况访问", desc = "读取前台应用（核心权限）",
                granted = perms.usage, onGrant = onOpenUsage,
            )
            PermissionRow(
                title = "忽略电池优化", desc = "防止系统在后台杀掉上报",
                granted = perms.battery, onGrant = onOpenBattery,
            )
            PermissionRow(
                title = "通知", desc = "常驻通知保活前台服务",
                granted = perms.notifications, onGrant = onOpenNotif,
            )
        }

        // ── Health Connect (optional extension) ───────────────────────────────
        SectionCard("健康同步（扩展）") {
            Text(
                "可选：把手机 Health Connect 的健康数据（心率/步数/睡眠等）定期上传到收集器。一般用户用不到。",
                color = Ink.dim, fontSize = 12.sp,
            )
            ToggleRow("启用健康同步", hcSyncEnabled) { enabled ->
                hcSyncEnabled = enabled; persist()
                if (enabled) {
                    onRequestHc()
                    HealthSyncScheduler.schedule(context, initial.hcSyncIntervalMinutes)
                } else HealthSyncScheduler.cancel(context)
            }
            if (hcSyncEnabled) {
                when (hcStatus) {
                    HcStatus.UNAVAILABLE -> StatusLine("本机未安装 Health Connect", Ink.red)
                    HcStatus.NEEDS_UPDATE -> StatusLine("Health Connect 需更新", Ink.warm)
                    HcStatus.AVAILABLE ->
                        if (hcGranted) StatusLine("健康数据权限：已授予", Ink.green)
                        else StatusLine("健康数据权限：未授予", Ink.warm)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!(hcStatus == HcStatus.AVAILABLE && hcGranted)) {
                        OutlinedButton(onClick = onRequestHc, modifier = Modifier.weight(1f)) {
                            Text(when (hcStatus) {
                                HcStatus.AVAILABLE -> "授予健康权限"
                                HcStatus.NEEDS_UPDATE -> "更新 Health Connect"
                                HcStatus.UNAVAILABLE -> "安装 Health Connect"
                            })
                        }
                    }
                    OutlinedButton(
                        onClick = { HealthSyncScheduler.runNow(context); status = "已触发立即同步" },
                        enabled = hcStatus == HcStatus.AVAILABLE && hcGranted && configured,
                        modifier = Modifier.weight(1f),
                    ) { Text("立即同步") }
                }
            }
        }

        // ── Logs ──────────────────────────────────────────────────────────────
        SectionCard("最近日志") {
            Text(
                store.loadLogs(30).asReversed().joinToString("\n").ifBlank { "（暂无）" },
                color = Ink.dim, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(running: Boolean, configured: Boolean) {
    val (dotColor, label, sub) = when {
        running -> Triple(Ink.green, "监听中", "正在上报前台应用与设备状态")
        !configured -> Triple(Ink.faint, "未配置", "填写服务器地址与 Token 后即可启动")
        else -> Triple(Ink.faint, "已停止", "点击「启动监听」开始上报")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = Ink.card),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.size(12.dp)
                .background(dotColor, RoundedCornerShape(50)))
            Column {
                Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(sub, color = Ink.dim, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = Ink.dim, fontSize = 11.sp,
            letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
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
            Text(desc, color = Ink.faint, fontSize = 11.sp)
        }
        if (granted) {
            Text("✓ 已授予", color = Ink.green, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        } else {
            OutlinedButton(
                onClick = onGrant,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink.warm),
            ) { Text("去授予") }
        }
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.sp)
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
