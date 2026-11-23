// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import com.example.healthapp.data.protocol.fitness.FitnessSync
import java.io.File

/** One authenticated band's private measurements. Step values are increments per device minute. */
data class BandSample(val type: String, val value: Int, val unit: String, val recordedAt: String, val source: String = "hibi_band_direct", val endAt: String? = null, val asleepMinutes: Int? = null, val sessionStart: String? = null,
    val progressOrigin: String? = null, val progressTimeBasis: String? = null, val previousObservedAt: String? = null, val previousMinutes: Int? = null, val stateOrigin: String? = null)
data class BandResult(val ok: Boolean, val status: String, val samples: Int = 0, val attemptAtMillis: Long = System.currentTimeMillis())

object BandSync {
    private val singleReader = Mutex()
    @Synchronized fun importPairingDocument(context: Context, input: java.io.InputStream) =
        Pairing.importDocument(context, input)
    @Synchronized fun importPairing(context: Context) {
        Pairing.importIfAvailable(context)
        val dir = File(context.noBackupFilesDir, "minute-frames")
        if (!dir.isDirectory || !hasPairing(context)) return
        val done = File(context.noBackupFilesDir, "probe-frames-import-v1")
        if (done.exists()) return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".frame") } ?: return
        val now = System.currentTimeMillis() / 1000L
        BandStore(context).use { store ->
            // The retired foreground probe wrote atomic, CRC-protected minute frames
            // in the same package. Preserve each source file and its original minute.
            for (file in files) {
                if (file.length() !in 1L..4L * 1024 * 1024) continue
                val bytes = file.readBytes()
                val frame = FitnessFileFrame.parse(bytes) ?: continue
                if (FitnessSync.isDailyRecord(frame.dataId)) store.store(frame, bytes, now)
            }
        }
        // A crash before this marker simply replays hash-idempotent inserts.
        done.createNewFile()
    }
    fun hasPairing(context: Context): Boolean = File(context.noBackupFilesDir, "hibi-band-pairing.bin").isFile
    fun pending(context: Context, limit: Int = 300) = BandStore(context).use { it.pending(limit) }
    fun ack(context: Context, sent: List<BandSample>) = BandStore(context).use { it.ack(sent) }
    fun latest(context: Context) = BandStore(context).use { it.latest() }
    fun latestSleep(context: Context) = BandStore(context).use { it.latestByType().firstOrNull { row -> row.type == "sleep_session" } }
    fun latestByType(context: Context) = BandStore(context).use { it.latestByType() }
    fun last(context: Context) = BandStore(context).use { it.last() }
    suspend fun collect(context: Context): BandResult = singleReader.withLock {
        val app = context.applicationContext
        val result = withContext(Dispatchers.IO) {
            try {
                importPairing(app)
                if (!hasPairing(app)) BandResult(false,"尚未导入手环配对资料")
                else if (Build.VERSION.SDK_INT >= 31 && app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                    BandResult(false,"请允许 Hibi 访问附近设备")
                else withTimeoutOrNull(55_000L) { callReader(app) } ?: BandResult(false,"读取超时，已关闭蓝牙连接")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { BandResult(false,"读取未完成（${e.javaClass.simpleName}）") }
        }
        withContext(Dispatchers.IO) { BandStore(app).use { it.record(result) } }
        result
    }
    private suspend fun callReader(app: Context): BandResult {
        val result = CompletableDeferred<BandResult>()
        val handler = Handler(Looper.getMainLooper())
        val replies = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                if (msg.what == 2) result.complete(BandResult(msg.data.getBoolean("ok"),msg.data.getString("status") ?: "读取无回执",msg.data.getInt("samples")))
            }
        })
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                try { Messenger(binder).send(Message.obtain(null,1).apply { replyTo = replies }) }
                catch (e: RemoteException) { result.complete(BandResult(false,"手环读取服务已中断")) }
            }
            override fun onServiceDisconnected(name: ComponentName) { handler.postDelayed({result.complete(BandResult(false,"手环读取进程已断开"))},250) }
            override fun onBindingDied(name: ComponentName) { result.complete(BandResult(false,"手环读取进程已更新")) }
            override fun onNullBinding(name: ComponentName) { result.complete(BandResult(false,"无法启动手环读取服务")) }
        }
        if (!app.bindService(Intent(app,BandReadService::class.java), conn, Context.BIND_AUTO_CREATE)) return BandResult(false,"无法启动手环读取服务")
        return try { result.await() } finally { app.unbindService(conn) }
    }
}
