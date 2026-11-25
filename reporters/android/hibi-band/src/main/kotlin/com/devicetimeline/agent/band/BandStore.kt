// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.fitness.FitnessDailyRecordParser
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import com.example.healthapp.data.protocol.fitness.FitnessSync
import java.time.Instant
import java.security.MessageDigest

/** Keep raw received minutes and pending uploads in the same transaction, outside Android backup. */
internal class BandStore(context: Context) : SQLiteOpenHelper(context, java.io.File(context.noBackupFilesDir, "band.sqlite").absolutePath, null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE frames(hash TEXT PRIMARY KEY,id_time INTEGER NOT NULL, version INTEGER NOT NULL, tz INTEGER NOT NULL, type INTEGER NOT NULL, bytes BLOB NOT NULL)")
        db.execSQL("CREATE INDEX frame_id ON frames(id_time,version,tz,type)")
        db.execSQL("CREATE TABLE samples(kind TEXT NOT NULL,at INTEGER NOT NULL,value INTEGER NOT NULL,uploaded INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind,at))")
        addSleepColumns(db)
        addProgressSchema(db)
        db.execSQL("CREATE INDEX samples_pending ON samples(uploaded,at)")
        db.execSQL("CREATE TABLE receipt(id INTEGER PRIMARY KEY CHECK(id=1),ok INTEGER NOT NULL,description TEXT NOT NULL,samples INTEGER NOT NULL,attempt_at INTEGER NOT NULL)")
    }
    private fun addSleepColumns(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN end_at INTEGER")
        db.execSQL("ALTER TABLE samples ADD COLUMN asleep_min INTEGER")
    }
    private fun addProgressSchema(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE samples ADD COLUMN progress_json TEXT")
        db.execSQL("CREATE TABLE sleep_poll(peer TEXT NOT NULL, start INTEGER NOT NULL, poll TEXT NOT NULL, observed INTEGER NOT NULL, counter INTEGER, endpoint INTEGER, PRIMARY KEY(peer,start))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..3 && newVersion == 4)
        if (oldVersion == 1) replaySleepV1(db)
        if (oldVersion < 3) addProgressSchema(db)
        val now = System.currentTimeMillis()/1000L
        db.rawQuery("SELECT bytes FROM frames WHERE type=33 AND version=4 ORDER BY rowid", null).use { rows ->
            while (rows.moveToNext()) {
                val frame = FitnessFileFrame.parse(rows.getBlob(0)) ?: continue
                BandSleep.provisional(frame, now)?.let { storeSleep(db, it, "sleep_band_interval") }
            }
        }
    }
    private fun replaySleepV1(db: SQLiteDatabase) {
        addSleepColumns(db)
        // Replay only full-day sleep frames, leaving already-delivered minute rows untouched.
        val now = System.currentTimeMillis() / 1000L
        db.rawQuery("SELECT bytes FROM frames WHERE type=33 ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) {
                val frame = FitnessFileFrame.parse(c.getBlob(0)) ?: continue
                BandSleep.decode(frame, now)?.let { storeSleep(db, it) }
            }
        }
    }
    private fun storeSleep(db: SQLiteDatabase, session: BandSleep, kind: String = "sleep_session"): Int {
        val args = arrayOf(kind, session.start.toString())
        val previous = db.rawQuery("SELECT end_at,asleep_min FROM samples WHERE kind=? AND at=?", args)
            .use { c -> if (c.moveToFirst()) c.getLong(0) to (if (c.isNull(1)) null else c.getInt(1)) else null }
        // A repeated or older summary for the same night can arrive in history after today's file.
        if (previous != null && (previous.first > session.end ||
                (previous.first == session.end && (session.asleepMinutes == null || session.asleepMinutes == previous.second)))) return 0
        db.execSQL("INSERT OR REPLACE INTO samples(kind,at,value,uploaded,end_at,asleep_min) VALUES(?,?,?,0,?,?)",
            arrayOf(kind, session.start, session.elapsedMinutes, session.end, session.asleepMinutes))
        return 1
    }
    fun storeSleepState(asleep: Boolean, observedAtSeconds: Long, origin: String) {
        require(origin == "band_basic_status" || origin == "band_status_event")
        val meta = org.json.JSONObject().put("origin", origin).put("timeBasis", "phone_observed").toString()
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO samples(kind,at,value,progress_json) VALUES('sleep_state',?,?,?)",
            arrayOf(observedAtSeconds, if (asleep) 1 else 0, meta),
        )
    }
    fun observeProgress(peer: String, poll: String, point: BandProgress, now: Long): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val args = arrayOf(peer, point.start.toString())
            val prior = db.rawQuery("SELECT poll,observed,counter,endpoint FROM sleep_poll WHERE peer=? AND start=?", args).use { c ->
                if (!c.moveToFirst()) null else SleepPoll(c.getString(0), c.getLong(1), if(c.isNull(2)) null else c.getInt(2), if(c.isNull(3)) null else c.getLong(3))
            }
            if (prior?.poll == poll || (prior != null && now <= prior.observed)) { db.setTransactionSuccessful(); return 0 }
            // A known finished session wins over any live or replayed progress for it.
            if (db.rawQuery("SELECT 1 FROM samples WHERE kind='sleep_session' AND at>=? AND at<=? LIMIT 1", arrayOf(point.start.toString(),now.toString())).use { it.moveToFirst() }) {
                db.setTransactionSuccessful(); return 0
            }
            val signal = point.compare(prior, now)
            db.execSQL("INSERT OR REPLACE INTO sleep_poll(peer,start,poll,observed,counter,endpoint) VALUES(?,?,?,?,?,?)",
                arrayOf(peer, point.start, poll, now, point.minutes, point.nativeAt))
            var inserted = 0
            if (signal != null) {
                val meta = org.json.JSONObject().put("sessionStart", instant(point.start))
                    .put("origin", signal.origin).put("timeBasis", signal.basis)
                signal.previousAt?.let { meta.put("previousObservedAt", instant(it)) }
                signal.previousMinutes?.let { meta.put("previousMinutes", it) }
                db.compileStatement("INSERT OR IGNORE INTO samples(kind,at,value,progress_json) VALUES(?,?,?,?)").use { stmt ->
                    stmt.bindString(1,signal.kind); stmt.bindLong(2,signal.at); stmt.bindLong(3,signal.value.toLong()); stmt.bindString(4,meta.toString())
                    if (stmt.executeInsert() != -1L) inserted++
                }
            }
            db.setTransactionSuccessful(); return inserted
        } finally { db.endTransaction() }
    }
    private fun instant(seconds: Long) = Instant.ofEpochSecond(seconds).toString()
    fun hasCompleteFrame(id: FitnessDataId): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM frames WHERE id_time=? AND version=? AND tz=? AND type=? LIMIT 1",
        arrayOf(id.timestamp.toString(), id.version.toString(), id.tzIn15Min.toString(), id.typeByte.toString())
    ).use { it.moveToFirst() }
    fun store(frame: FitnessFileFrame, bytes: ByteArray, now: Long): Int {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.rawQuery("SELECT 1 FROM frames WHERE hash=?", arrayOf(hash)).use { it.moveToFirst() }) {
                db.setTransactionSuccessful(); return 0
            }
            db.execSQL("INSERT INTO frames(hash,id_time,version,tz,type,bytes) VALUES(?,?,?,?,?,?)", arrayOf(hash, frame.dataId.timestamp, frame.dataId.version, frame.dataId.tzIn15Min, frame.dataId.typeByte, bytes))
            if (!FitnessSync.isDailyRecord(frame.dataId)) {
                val saved = BandSleep.decode(frame, now)?.let { storeSleep(db, it) }
                    ?: BandSleep.provisional(frame, now)?.let { storeSleep(db, it, "sleep_band_interval") } ?: 0
                val point = BandProgress.freshRecord(frame, now)
                val added = if (point == null) 0 else {
                    val meta = org.json.JSONObject().put("sessionStart", instant(point.first)).toString()
                    db.compileStatement("INSERT OR IGNORE INTO samples(kind,at,value,progress_json) VALUES('sleep_record_sample',?,1,?)").use {
                        it.bindLong(1, point.second)
                        it.bindString(2, meta)
                        if (it.executeInsert() == -1L) 0 else 1
                    }
                }
                db.setTransactionSuccessful()
                return saved + added
            }
            val valid = FitnessDailyRecordParser.parseValid(frame.bitmap, frame.dataId.version)
            val stride = FitnessDailyRecordParser.recordLength(valid)
            var count = 0
            // Upstream v1 layout is unverified. Retain raw bytes even when no measurement is usable.
            if (frame.dataId.version in 2..5 && stride > 0 && frame.body.size % stride == 0) {
                // Use UPDATE/INSERT OR IGNORE rather than UPSERT for the oldest
                // Android versions supported by Hibi (system SQLite before 3.24).
                val change = db.compileStatement("UPDATE samples SET value=?, uploaded=0 WHERE kind=? AND at=? AND value!=?")
                val insert = db.compileStatement("INSERT OR IGNORE INTO samples(kind,at,value,uploaded) VALUES(?,?,?,0)")
                try {
                    FitnessDailyRecordParser.parse(frame).forEachIndexed { i, values ->
                        val at = frame.dataId.timestamp + i * 60L
                        if (at !in 946684800L..now) return@forEachIndexed
                        // Field presence/validity comes from the band's own bitmap. A missing step field must never become 0.
                        for ((kind, v) in listOf("heart_rate" to values[FitnessDailyRecordParser.ItemType.HR],
                            "steps" to values[FitnessDailyRecordParser.ItemType.STEPS])) {
                            if (v == null || (kind == "heart_rate" && v !in 1L..254L) ||
                                (kind == "steps" && v !in 0L..0x3fffL)) continue
                            change.bindLong(1, v)
                            change.bindString(2, kind)
                            change.bindLong(3, at)
                            change.bindLong(4, v)
                            if (change.executeUpdateDelete() > 0) {
                                count++
                            } else {
                                insert.bindString(1, kind)
                                insert.bindLong(2, at)
                                insert.bindLong(3, v)
                                if (insert.executeInsert() != -1L) count++
                            }
                        }
                    }
                } finally {
                    change.close()
                    insert.close()
                }
            }
            db.setTransactionSuccessful()
            return count
        } finally { db.endTransaction() }
    }
    private fun sample(row: Cursor): BandSample {
        val kind = row.getString(0)
        val unit = when (kind) { "heart_rate" -> "bpm"; "steps" -> "count"; "sleep_session", "sleep_band_interval" -> "minutes"; "sleep_state", "sleep_interval_progress", "sleep_record_sample" -> "state"; "sleep_progress" -> "minutes"; else -> error("Unknown band metric") }
        val meta = if(row.isNull(5)) null else org.json.JSONObject(row.getString(5))
        return BandSample(kind, row.getInt(2), unit, Instant.ofEpochSecond(row.getLong(1)).toString(),
            endAt = if (row.isNull(3)) null else Instant.ofEpochSecond(row.getLong(3)).toString(),
            asleepMinutes = if (row.isNull(4)) null else row.getInt(4),
            stateOrigin=if (kind == "sleep_state") meta?.getString("origin") ?: "band_basic_status" else null,
            sessionStart=meta?.takeIf { it.has("sessionStart") }?.getString("sessionStart"), progressOrigin=meta?.getString("origin"), progressTimeBasis=meta?.getString("timeBasis"),
            previousObservedAt=meta?.takeIf { it.has("previousObservedAt") }?.getString("previousObservedAt"),
            previousMinutes=meta?.takeIf { it.has("previousMinutes") }?.getInt("previousMinutes"))
    }
    fun pending(limit: Int): List<BandSample> = readableDatabase.rawQuery(
        "SELECT kind,at,value,end_at,asleep_min,progress_json FROM samples WHERE uploaded=0 ORDER BY CASE WHEN kind IN ('sleep_record_sample','sleep_state','sleep_interval_progress','sleep_progress') AND at>=CAST(strftime('%s','now') AS INTEGER)-300 THEN 0 ELSE 1 END,at,kind LIMIT ?", arrayOf(limit.coerceIn(1, 500).toString())
    ).use { row -> buildList { while (row.moveToNext()) add(sample(row)) } }
    fun latest(): BandSample? = readableDatabase.rawQuery(
        "SELECT kind,at,value,end_at,asleep_min,progress_json FROM samples ORDER BY (kind='heart_rate') DESC, at DESC LIMIT 1", null
    ).use { if (it.moveToFirst()) sample(it) else null }
    fun latestByType(): List<BandSample> = readableDatabase.rawQuery("""SELECT s.kind,s.at,s.value,s.end_at,s.asleep_min,s.progress_json
        FROM samples s JOIN (SELECT kind,MAX(at) AS at FROM samples GROUP BY kind) last
        ON last.kind=s.kind AND last.at=s.at ORDER BY s.kind""", null)
        .use { c -> buildList { while (c.moveToNext()) add(sample(c)) } }
    fun ack(sent: List<BandSample>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE samples SET uploaded=1 WHERE kind=? AND at=? AND value=? AND end_at IS ? AND asleep_min IS ?").use { stmt ->
                sent.forEach { sample ->
                    stmt.bindString(1, sample.type); stmt.bindLong(2, Instant.parse(sample.recordedAt).epochSecond); stmt.bindLong(3, sample.value.toLong())
                    if (sample.endAt == null) stmt.bindNull(4) else stmt.bindLong(4, Instant.parse(sample.endAt).epochSecond)
                    if (sample.asleepMinutes == null) stmt.bindNull(5) else stmt.bindLong(5, sample.asleepMinutes.toLong())
                    stmt.executeUpdateDelete()
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun record(r: BandResult) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO receipt(id,ok,description,samples,attempt_at) VALUES(1,?,?,?,?)", arrayOf(if(r.ok) 1 else 0, r.status, r.samples, r.attemptAtMillis))
    }
    fun last(): BandResult? = readableDatabase.rawQuery("SELECT ok,description,samples,attempt_at FROM receipt WHERE id=1", null)
        .use { if (it.moveToFirst()) BandResult(it.getInt(0) == 1, it.getString(1), it.getInt(2), it.getLong(3)) else null }
}
