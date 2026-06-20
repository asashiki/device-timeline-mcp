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
            val from = to.minusSeconds(settings.hcSyncRangeHours * 3600L)
            val snapshot = reader.readSnapshot(from, to)
            val records = snapshotToRecords(snapshot)

            if (records.isEmpty()) {
                Log.i(TAG, "No health records to sync")
                store.appendLog("HC: 无数据")
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
