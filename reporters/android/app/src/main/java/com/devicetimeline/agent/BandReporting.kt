package com.devicetimeline.agent

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.devicetimeline.agent.band.BandSample
import com.devicetimeline.agent.band.BandSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class BandStatus(
    val note: String = "等待启动",
    val readAt: Long = 0,
    val uploadedAt: Long = 0,
    val uploadNote: String = "",
)

internal class BandReporting(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("hibi_band_status", Context.MODE_PRIVATE)
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    fun enable(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (!value) note("手环监测已关闭")
    }
    fun status() = BandStatus(
        prefs.getString("note", null) ?: "等待启动", prefs.getLong("read_at", 0),
        prefs.getLong("uploaded_at", 0),
        prefs.getString("upload_note", "") ?: "",
    )
    private fun note(text: String) { prefs.edit().putString("note", text).apply(); refresh() }
    suspend fun loop() = kotlinx.coroutines.coroutineScope {
        if (!enabled || !hasBluetooth(ctx) || !BandSync.hasPairing(ctx)) return@coroutineScope
        launch { com.devicetimeline.agent.band.BandLinkClient.maintain(ctx.applicationContext) }
        launch { WristRemote(ctx).run() }
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try { uploadPending() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { uploadStatus("待联网后补传") }
            delay(15000)
        }
    }
    private suspend fun uploadPending() {
        // Drain the initial history in bounded batches. Ack only data confirmed by the receiver.
        val settings = SettingsStore(ctx).load()
        while (enabled) {
            val batch = BandSync.pending(ctx, limit = 200)
            if (batch.isEmpty()) break
            val outcome = send(settings, batch)
            if (outcome != null) {
                uploadStatus(outcome)
                return
            }
            BandSync.ack(ctx, batch)
            prefs.edit().putLong("uploaded_at", System.currentTimeMillis()).putLong("read_at",System.currentTimeMillis())
                .putString("upload_note", "已收到服务器回执").apply()
            refresh()
        }
        // A collector still running the previous release must continue to receive core health data.
        val extended = BandSync.pendingExtended(ctx, 170)
        if (enabled && extended.isNotEmpty() && send(settings, extended) == null) BandSync.ack(ctx, extended)
    }
    private fun uploadStatus(text: String) {
        prefs.edit().putString("upload_note", text).apply()
        refresh()
    }
    private suspend fun send(settings: AgentSettings, batch: List<BandSample>): String? {
        val base = settings.serverUrl.trim().toHttpUrlOrNull()
        if (base == null || !base.isHttps || base.username.isNotEmpty() || base.password.isNotEmpty() ||
            settings.token.isBlank()) return "请先保存 HTTPS 服务器地址和设备 Token"
        val url = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/api/devices/band-health")
            .query(null).fragment(null).build()
        val rows = JSONArray()
        for (row in batch) {
            val record = JSONObject().put("type", row.type).put("value", row.value)
                .put("unit", row.unit).put("source", row.source).put("recordedAt", row.recordedAt)
            when (row.type) {
                "sleep_record_sample" -> record.put("valueJson", JSONObject().put("sessionStart", requireNotNull(row.sessionStart)))
                "sleep_state" -> record.put("valueJson", JSONObject()
                    .put("origin", requireNotNull(row.stateOrigin)).put("timeBasis", "phone_observed"))
                "sleep_interval_progress", "sleep_progress" -> {
                    val detail = JSONObject()
                        .put("sessionStart", requireNotNull(row.sessionStart))
                        .put("origin", requireNotNull(row.progressOrigin))
                        .put("timeBasis", requireNotNull(row.progressTimeBasis))
                    row.previousObservedAt?.let { detail.put("previousObservedAt", it) }
                    row.previousMinutes?.let { detail.put("previousMinutes", it) }
                    record.put("valueJson", detail)
                }
                "sleep_session", "sleep_band_interval" -> row.endAt?.let { end ->
                    val detail = JSONObject().put("endAt", end)
                    row.asleepMinutes?.let { detail.put("asleepMinutes", it) }
                    record.put("valueJson", detail)
                }
            }
            rows.put(record)
        }
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${settings.token.trim()}")
            .post(JSONObject().put("records", rows).toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(req)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume("网络不可用，已保留在本机")
                }
                override fun onResponse(call: Call, response: Response) = response.use {
                    val result = when {
                        !it.isSuccessful -> "上传未成功（HTTP ${it.code}），已保留在本机"
                        else -> {
                            val ack = runCatching { JSONObject(it.peekBody(8192).string()) }.getOrNull()
                            if (ack?.optBoolean("ok") == true && ack.optInt("accepted", -1) == batch.size) null
                            else "服务器未确认整批数据，已保留在本机"
                        }
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
        }
    }
    companion object {
        private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
        val revision = MutableStateFlow(0L)
        private fun refresh() { revision.value = SystemClock.elapsedRealtimeNanos() }
        fun hasBluetooth(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    }
}
