package com.devicetimeline.agent

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch

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

    private val hcGrantedState = mutableStateOf(false)

    private val hcPermissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        hcGrantedState.value = granted.containsAll(hcPermissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen(
                        hcGranted = hcGrantedState.value,
                        onRequestHcPermissions = { hcPermissionLauncher.launch(hcPermissions) },
                        onRefreshHcState = { checkHcPermissions() },
                    )
                }
            }
        }
        checkHcPermissions()
    }

    private fun checkHcPermissions() {
        lifecycleScope.launch {
            hcGrantedState.value = try {
                if (HealthConnectClient.getSdkStatus(this@MainActivity) != HealthConnectClient.SDK_AVAILABLE) {
                    false
                } else {
                    HealthConnectClient.getOrCreate(this@MainActivity)
                        .permissionController.getGrantedPermissions()
                        .containsAll(hcPermissions)
                }
            } catch (_: Exception) {
                false
            }
        }
    }
}

@Composable
private fun AgentScreen(
    hcGranted: Boolean,
    onRequestHcPermissions: () -> Unit,
    onRefreshHcState: () -> Unit,
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
                serverUrl = serverUrl,
                token = token,
                heartbeatSeconds = heartbeat.toIntOrNull() ?: 30,
                reportBattery = reportBattery,
                autoStartOnBoot = autoStart,
                isRunningEnabled = running,
                hcSyncEnabled = hcSyncEnabled,
                hcSyncIntervalMinutes = initial.hcSyncIntervalMinutes,
                hcSyncRangeHours = initial.hcSyncRangeHours,
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hibi 日々", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server URL (例 https://link.asashiki.com)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Device Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = heartbeat,
            onValueChange = { heartbeat = it.filter(Char::isDigit) },
            label = { Text("上报间隔秒 (10–50)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ToggleRow("上报电池信息", reportBattery) { reportBattery = it; persist() }
        ToggleRow("开机自启", autoStart) { autoStart = it; persist() }

        Button(onClick = { persist(); status = "已保存" }, modifier = Modifier.fillMaxWidth()) {
            Text("保存设置")
        }

        OutlinedButton(
            onClick = { UsageTracker.openUsageAccessSettings(context) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("授予 使用情况访问 权限") }

        OutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}".toUri(),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("关闭电池优化") }

        // ── Health Connect 同步（可选扩展） ───────────────────────────────
        Text("Health Connect 健康同步（扩展）", style = MaterialTheme.typography.titleMedium)
        Text(
            "可选：把手机 Health Connect 的健康数据（心率 / 步数 / 睡眠等）定期上传到收集器。" +
                "一般用户用不到，按需开启。",
            style = MaterialTheme.typography.bodySmall,
        )
        ToggleRow("启用健康同步", hcSyncEnabled) { enabled ->
            hcSyncEnabled = enabled
            persist()
            if (enabled) {
                onRequestHcPermissions()
                HealthSyncScheduler.schedule(context, initial.hcSyncIntervalMinutes)
                status = "已启用健康同步"
            } else {
                HealthSyncScheduler.cancel(context)
                status = "已停用健康同步"
            }
        }
        if (hcSyncEnabled) {
            Text(
                if (hcGranted) "健康数据权限：已授予" else "健康数据权限：未授予（需授予后才能读取）",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onRequestHcPermissions() },
                    modifier = Modifier.weight(1f),
                ) { Text("授予健康权限") }
                OutlinedButton(
                    onClick = {
                        onRefreshHcState()
                        HealthSyncScheduler.runNow(context)
                        status = "已触发立即同步"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("立即同步") }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    running = true
                    persist()
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, TrackingService::class.java)
                            .apply { action = TrackingService.ACTION_START },
                    )
                    status = "已启动监听"
                },
                modifier = Modifier.weight(1f),
            ) { Text("启动") }
            OutlinedButton(
                onClick = {
                    running = false
                    persist()
                    context.startService(
                        Intent(context, TrackingService::class.java)
                            .apply { action = TrackingService.ACTION_STOP },
                    )
                    status = "已停止监听"
                },
                modifier = Modifier.weight(1f),
            ) { Text("停止") }
        }

        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }

        Text("最近日志", style = MaterialTheme.typography.titleMedium)
        Text(
            store.loadLogs(30).asReversed().joinToString("\n").ifBlank { "（暂无）" },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
