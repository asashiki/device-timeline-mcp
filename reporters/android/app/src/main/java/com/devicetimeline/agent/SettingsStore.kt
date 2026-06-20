package com.devicetimeline.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AgentSettings = AgentSettings(
        serverUrl = prefs.getString(KEY_SERVER_URL, "") ?: "",
        token = prefs.getString(KEY_TOKEN, "") ?: "",
        heartbeatSeconds = sanitizeHeartbeat(prefs.getInt(KEY_HEARTBEAT_SECONDS, 30)),
        reportBattery = prefs.getBoolean(KEY_REPORT_BATTERY, true),
        autoStartOnBoot = prefs.getBoolean(KEY_AUTO_START, false),
        isRunningEnabled = prefs.getBoolean(KEY_RUNNING_ENABLED, false),
        hcSyncEnabled = prefs.getBoolean(KEY_HC_SYNC_ENABLED, false),
        hcSyncIntervalMinutes = prefs.getLong(KEY_HC_SYNC_INTERVAL, 60L),
        hcSyncRangeHours = prefs.getLong(KEY_HC_SYNC_RANGE, 24L),
    )

    fun save(settings: AgentSettings) {
        prefs.edit()
            .putString(KEY_SERVER_URL, settings.serverUrl.trim())
            .putString(KEY_TOKEN, settings.token.trim())
            .putInt(KEY_HEARTBEAT_SECONDS, sanitizeHeartbeat(settings.heartbeatSeconds))
            .putBoolean(KEY_REPORT_BATTERY, settings.reportBattery)
            .putBoolean(KEY_AUTO_START, settings.autoStartOnBoot)
            .putBoolean(KEY_RUNNING_ENABLED, settings.isRunningEnabled)
            .putBoolean(KEY_HC_SYNC_ENABLED, settings.hcSyncEnabled)
            .putLong(KEY_HC_SYNC_INTERVAL, settings.hcSyncIntervalMinutes.coerceAtLeast(15L))
            .putLong(KEY_HC_SYNC_RANGE, settings.hcSyncRangeHours.coerceAtLeast(1L))
            .apply()
    }

    fun setRunningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RUNNING_ENABLED, enabled).apply()
    }

    @Synchronized
    fun appendLog(message: String) {
        val normalized = message.replace("\n", " ").trim()
        if (normalized.isBlank()) return
        val logs = readLogsInternal()
        logs.add("${java.time.LocalDateTime.now().format(LOG_TIME_FORMAT)} $normalized")
        while (logs.size > MAX_LOG_ITEMS) logs.removeAt(0)
        writeLogsInternal(logs)
    }

    @Synchronized
    fun loadLogs(limit: Int = MAX_LOG_ITEMS): List<String> {
        val logs = readLogsInternal()
        if (limit <= 0 || logs.size <= limit) return logs
        return logs.takeLast(limit)
    }

    @Synchronized
    fun clearLogs() {
        prefs.edit().putString(KEY_LOGS, "[]").apply()
    }

    private fun readLogsInternal(): MutableList<String> {
        val raw = prefs.getString(KEY_LOGS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.optString(index).orEmpty() }
                .filter { it.isNotBlank() }
                .toMutableList()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeLogsInternal(logs: List<String>) {
        val jsonArray = JSONArray()
        logs.forEach { jsonArray.put(it) }
        prefs.edit().putString(KEY_LOGS, jsonArray.toString()).apply()
    }

    private fun sanitizeHeartbeat(value: Int): Int = min(50, max(10, value))

    companion object {
        private const val PREFS_NAME = "device_timeline_agent"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_HEARTBEAT_SECONDS = "heartbeat_seconds"
        private const val KEY_REPORT_BATTERY = "report_battery"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_RUNNING_ENABLED = "running_enabled"
        private const val KEY_HC_SYNC_ENABLED = "hc_sync_enabled"
        private const val KEY_HC_SYNC_INTERVAL = "hc_sync_interval_minutes"
        private const val KEY_HC_SYNC_RANGE = "hc_sync_range_hours"
        private const val KEY_LOGS = "logs"
        private const val MAX_LOG_ITEMS = 240
        private val LOG_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
