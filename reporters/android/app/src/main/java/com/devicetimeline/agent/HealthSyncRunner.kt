package com.devicetimeline.agent

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import org.json.JSONObject
import java.time.Instant

object HealthSyncRunner {

    private const val TAG = "HealthSyncRunner"
    private const val BATCH_SIZE = 400

    enum class Outcome { OK, EMPTY, SKIPPED, RETRY, FAILURE }

    private suspend fun grantedCount(context: Context): Int = try {
        HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions().size
    } catch (_: Exception) { -1 }

    // Foreground "立即同步" path: runs the full flow and returns a human-readable
    // diagnosis of exactly where it stops, so the user can see why no data is
    // arriving (HC unavailable / no permission / no data in window / upload error).
    suspend fun diagnoseAndSync(context: Context): String {
        val store = SettingsStore(context)
        val settings = store.load()
        val baseUrl = settings.serverUrl.trim().trimEnd('/')
        val token = settings.token.trim()
        if (baseUrl.isBlank() || token.isBlank()) return "未填写服务器地址或 Token"

        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            return "Health Connect 不可用（状态码 $sdkStatus）。本机需安装/启用 Google「Health Connect」。"
        }

        val granted = try {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            return "读取权限失败：${e.javaClass.simpleName}: ${e.message}"
        }

        return try {
            val reader = HealthConnectReader(context)
            val to = Instant.now()
            var records = snapshotToRecords(
                reader.readSnapshot(to.minusSeconds(settings.hcSyncRangeHours * 3600L), to)
            )
            var widened = false
            // If the normal window is empty, fall back to a 30-day read so a phone
            // whose health data is sparse/older still syncs (and so we can tell
            // "HC empty" apart from "nothing recent").
            if (records.isEmpty()) {
                records = snapshotToRecords(reader.readSnapshot(to.minusSeconds(30L * 24 * 3600), to))
                widened = records.isNotEmpty()
            }

            if (granted.isEmpty() && records.isEmpty()) {
                "已授权 0 项健康权限——请在 Health Connect 里给「Hibi 日々」勾选要读取的数据类型。"
            } else if (records.isEmpty()) {
                "已授权 ${granted.size} 项，但 Health Connect 近 30 天对这些类型都没有数据。" +
                    "需要数据源 App（小米运动健康 / 华为运动健康 / 三星 Health / Google Fit 等）开启「连接 / 写入 Health Connect」，HC 里才会有数据可读。"
            } else {
                var uploaded = 0
                for (batch in records.chunked(BATCH_SIZE)) {
                    val err = ApiReporter.postHealthBatch(baseUrl, token, batch)
                    if (err != null) return "读到 ${records.size} 条，但上传失败：$err"
                    uploaded += batch.size
                }
                store.appendLog("HC: 上传 $uploaded 条记录")
                "成功 ✅ 已授权 ${granted.size} 项，上传 $uploaded 条健康记录" +
                    (if (widened) "（用了近 30 天窗口）" else "") + "。"
            }
        } catch (e: Exception) {
            "读取/上传异常：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    suspend fun runOnce(context: Context): Outcome {
        val store = SettingsStore(context)
        val settings = store.load()

        if (!settings.hcSyncEnabled) {
            Log.d(TAG, "HC sync disabled, skipping")
            return Outcome.SKIPPED
        }

        val baseUrl = settings.serverUrl.trim().trimEnd('/')
        val token = settings.token.trim()
        if (baseUrl.isBlank() || token.isBlank()) {
            Log.w(TAG, "Server URL or token not set")
            return Outcome.FAILURE
        }

        val sdkStatus = HealthConnectClient.getSdkStatus(context)
        if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
            Log.w(TAG, "HealthConnect not available (status=$sdkStatus)")
            return Outcome.SKIPPED
        }

        return try {
            val reader = HealthConnectReader(context)
            val to = Instant.now()
            var records = snapshotToRecords(
                reader.readSnapshot(to.minusSeconds(settings.hcSyncRangeHours * 3600L), to)
            )
            // Fall back to a 30-day window so sparse/older data still syncs.
            if (records.isEmpty()) {
                records = snapshotToRecords(reader.readSnapshot(to.minusSeconds(30L * 24 * 3600), to))
            }

            if (records.isEmpty()) {
                // Distinguish "no permission" from "permission OK but no samples":
                // both otherwise read back as empty and look identical in the log.
                val granted = grantedCount(context)
                Log.i(TAG, "No health records (granted=$granted)")
                store.appendLog(
                    if (granted <= 0) "HC: 未授权（已授权 0 项，去 Health Connect 给本应用勾选权限）"
                    else "HC: 无数据（已授权 $granted 项，近 30 天无样本，检查数据源是否写入 HC）"
                )
                return Outcome.EMPTY
            }

            val batches = records.chunked(BATCH_SIZE)
            var uploaded = 0
            for (batch in batches) {
                val err = ApiReporter.postHealthBatch(baseUrl, token, batch)
                if (err != null) {
                    Log.w(TAG, "HC sync failed: $err")
                    store.appendLog("HC: 上传失败 $err")
                    return Outcome.RETRY
                }
                uploaded += batch.size
            }
            Log.i(TAG, "HC sync OK: $uploaded records")
            store.appendLog("HC: 上传 $uploaded 条记录")
            Outcome.OK
        } catch (e: Exception) {
            Log.e(TAG, "HC sync error: ${e.message}", e)
            store.appendLog("HC: 错误 ${e.javaClass.simpleName}")
            Outcome.RETRY
        }
    }

    private fun snapshotToRecords(snapshot: HealthSnapshot): List<JSONObject> {
        val list = mutableListOf<JSONObject>()

        snapshot.heartRate?.forEach { s ->
            list.add(record("heart_rate", s.bpm.toDouble(), null, "bpm", s.time))
        }
        snapshot.steps?.forEach { s ->
            list.add(record("steps", s.count.toDouble(), null, "count", s.startTime))
        }
        snapshot.sleep?.forEach { s ->
            val dur = durationMinutes(s.startTime, s.endTime)
            list.add(record("sleep", dur, null, "minutes", s.startTime))
        }
        snapshot.calories?.forEach { s ->
            list.add(record("total_calories", s.kcal, null, "kcal", s.startTime))
        }
        snapshot.spo2?.forEach { s ->
            list.add(record("oxygen_saturation", s.percentage, null, "percent", s.time))
        }
        snapshot.distance?.forEach { s ->
            list.add(record("distance", s.meters, null, "meters", s.startTime))
        }
        snapshot.exercise?.forEach { s ->
            val dur = durationMinutes(s.startTime, s.endTime)
            list.add(record("exercise", dur, null, "minutes", s.startTime))
        }
        snapshot.bloodPressure?.forEach { s ->
            val vj = JSONObject()
                .put("systolic", s.systolic)
                .put("diastolic", s.diastolic)
            list.add(record("blood_pressure", null, vj, "mmHg", s.time))
        }
        snapshot.temperature?.forEach { s ->
            list.add(record("body_temperature", s.celsius, null, "celsius", s.time))
        }
        snapshot.respiratoryRate?.forEach { s ->
            list.add(record("respiratory_rate", s.rpm, null, "breaths_per_min", s.time))
        }
        snapshot.bloodGlucose?.forEach { s ->
            list.add(record("blood_glucose", s.mmolPerL, null, "mmol_per_l", s.time))
        }
        snapshot.weight?.forEach { s ->
            list.add(record("weight", s.kg, null, "kg", s.time))
        }
        snapshot.height?.forEach { s ->
            list.add(record("height", s.meters, null, "meters", s.time))
        }

        return list
    }

    private fun record(
        type: String,
        value: Double?,
        valueJson: JSONObject?,
        unit: String,
        recordedAt: String
    ): JSONObject {
        val obj = JSONObject()
            .put("type", type)
            .put("unit", unit)
            .put("recordedAt", recordedAt)
            .put("source", "health_connect")
        if (value != null) obj.put("value", value)
        if (valueJson != null) obj.put("valueJson", valueJson)
        return obj
    }

    private fun durationMinutes(startIso: String, endIso: String): Double {
        return try {
            val start = Instant.parse(startIso)
            val end = Instant.parse(endIso)
            (end.epochSecond - start.epochSecond) / 60.0
        } catch (_: Exception) {
            0.0
        }
    }
}
