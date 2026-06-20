package com.devicetimeline.agent

data class AgentSettings(
    val serverUrl: String = "",
    val token: String = "",
    val heartbeatSeconds: Int = 30,
    val reportBattery: Boolean = true,
    val autoStartOnBoot: Boolean = false,
    val isRunningEnabled: Boolean = false,
    // Optional Health Connect sync extension (opt-in; off by default).
    val hcSyncEnabled: Boolean = false,
    val hcSyncIntervalMinutes: Long = 60,
    val hcSyncRangeHours: Long = 24,
)

data class ForegroundAppInfo(
    val packageName: String,
    val appName: String,
    val timestampMs: Long,
)

data class DeviceExtras(
    val batteryPercent: Int?,
    val batteryCharging: Boolean?,
    val networkType: String,
)
