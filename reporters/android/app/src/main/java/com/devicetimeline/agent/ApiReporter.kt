package com.devicetimeline.agent

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.concurrent.TimeUnit

object ApiReporter {
    private const val TAG = "ApiReporter"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    fun postReport(
        settings: AgentSettings,
        appInfo: ForegroundAppInfo,
        extras: DeviceExtras,
    ): Boolean {
        val baseUrl = normalizeBaseUrl(settings.serverUrl) ?: return false
        if (settings.token.isBlank()) return false

        val extraJson = JSONObject()
        if (settings.reportBattery) {
            extras.batteryPercent?.let { extraJson.put("battery_percent", it) }
            extras.batteryCharging?.let { extraJson.put("battery_charging", it) }
        }
        extraJson.put("network_type", extras.networkType)

        // appId = package name; windowTitle = human app label (Android has no window titles).
        val body = JSONObject()
            .put("appId", appInfo.packageName)
            .put("windowTitle", appInfo.appName)
            .put("occurredAt", Instant.ofEpochMilli(appInfo.timestampMs).toString())
            .put("extra", extraJson)

        val request = Request.Builder()
            .url("$baseUrl/api/devices/report")
            .addHeader("Authorization", "Bearer ${settings.token}")
            .addHeader("User-Agent", "device-timeline-android-agent/1.0.0")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.w(TAG, "Request failed: ${response.code}")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.w(TAG, "Request error: ${e.message}")
            false
        }
    }

    fun normalizeBaseUrl(raw: String): String? {
        val candidate = raw.trim().trimEnd('/')
        if (candidate.isBlank()) return null
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if ((scheme != "https" && scheme != "http") || host.isBlank()) return null
            candidate
        } catch (_: Exception) {
            null
        }
    }
}
