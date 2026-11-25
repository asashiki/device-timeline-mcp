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
    fun stepsByHour(context: Context) = BandStore(context).use { it.stepsByHour() }
    fun latestByType(context: Context) = BandStore(context).use { it.latestByType() }
    fun last(context: Context) = BandStore(context).use { it.last() }
}
