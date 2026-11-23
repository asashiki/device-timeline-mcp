// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import android.util.AtomicFile
import com.example.healthapp.data.protocol.fitness.FitnessDataId
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.Instant

/** A private, bounded queue of exact IDs independently observed by the owner.
 * Only IDs enter the queue. Dates/durations in the results are decoded from
 * matching fresh, CRC-verified data returned over the authenticated session. */
internal class KnownSleepRequests(private val directory: File, private val peer: String) {
    private val results = AtomicFile(File(directory, "hibi-sleep-request-results.json"))
    private val rows = linkedMapOf<FitnessDataId, JSONObject>()
    private val inFlight = mutableSetOf<FitnessDataId>()

    init {
        val config = File(directory, "hibi-sleep-requests.json")
        if (config.isFile && config.length() in 1..2048) {
            val ids = runCatching { readRequests(JSONObject(config.readText())) }.getOrDefault(emptyList())
            if (ids.isNotEmpty()) {
                val history = runCatching { JSONObject(results.readFully().toString(Charsets.UTF_8)) }.getOrNull()
                for (id in ids) {
                    val old = if (history?.optString("peer") == peer) history.optJSONObject("entries")?.optJSONObject(idKey(id)) else null
                    rows[id] = old ?: idJson(id).put("attempts",0).put("matchedFullFrame",false)
                }
            }
        }
    }

    @Synchronized fun next(): FitnessDataId? = rows.entries.firstOrNull { (id,row) ->
        id !in inFlight && row.optInt("attempts") < 2 && !row.optBoolean("matchedFullFrame")
    }?.key

    @Synchronized fun begin(id: FitnessDataId) {
        val row = rows.getValue(id)
        check(id !in inFlight && row.optInt("attempts") < 2 && !row.optBoolean("matchedFullFrame"))
        inFlight.add(id)
        row.put("attempts",row.getInt("attempts")+1).put("lastAttemptAt",Instant.now().toString()).put("matchingFirstFragment",false)
            .put("matchedFullFrame",false).put("decodedSession",false).put("requestSubmitted",false)
        save() // Reserve before send: service/process failure cannot cause indefinite resends.
    }
    @Synchronized fun sent(id: FitnessDataId) { rows.getValue(id).put("requestSubmitted",true); save() }
    @Synchronized fun first(id: FitnessDataId?) {
        if (id == null || id !in inFlight) return
        val row = rows.getValue(id)
        if (!row.optBoolean("matchingFirstFragment")) { row.put("matchingFirstFragment",true); save() }
    }
    @Synchronized fun full(id: FitnessDataId?, session: BandSleep?, currentTransfer: Boolean) {
        if (id == null || id !in inFlight || !currentTransfer) return
        val row = rows.getValue(id)
        row.put("matchedFullFrame",true).put("matchedAt",Instant.now().toString()).put("decodedSession",session!=null)
        if (session != null) {
            row.put("bodyStart",Instant.ofEpochSecond(session.start).toString()).put("bodyEnd",Instant.ofEpochSecond(session.end).toString())
                .put("elapsedMinutes",session.elapsedMinutes).put("asleepMinutes",session.asleepMinutes ?: JSONObject.NULL)
        }
        save()
    }
    @Synchronized fun status(): String = if (rows.isEmpty()) "" else rows.values.joinToString("／") { row ->
        val attempts=row.optInt("attempts")
        when {
            row.optBoolean("decodedSession") -> "目标睡眠已从手环读回并解析"
            row.optBoolean("matchedFullFrame") -> "目标睡眠文件已完整读回，时段尚未解析"
            row.optBoolean("matchingFirstFragment") -> "目标文件仅收到首片（$attempts/2）"
            row.optBoolean("requestSubmitted") -> "已按编号补拉（$attempts/2），尚无匹配文件"
            attempts>0 -> "补拉命令尚未完成（$attempts/2）"
            else -> "目标文件待查询"
        }
    }
    private fun save() {
        val entries = JSONObject(); rows.forEach { (id,row) -> entries.put(idKey(id),row) }
        val contents = JSONObject().put("version",1).put("peer",peer).put("entries",entries)
        val stream = results.startWrite()
        try { stream.write(contents.toString().toByteArray()); results.finishWrite(stream) }
        catch(e: Exception) { results.failWrite(stream); throw e }
    }
    companion object {
        private fun idKey(id:FitnessDataId) = "${id.timestamp}_${id.tzIn15Min}_${id.version}_${id.typeByte}"
        private fun idJson(id:FitnessDataId) = JSONObject().put("timestamp",id.timestamp).put("tzIn15Min",id.tzIn15Min)
            .put("version",id.version).put("typeByte",id.typeByte)
        private fun readRequests(config: JSONObject): List<FitnessDataId> {
            require(config.getInt("version")==1)
            val files: JSONArray=config.getJSONArray("files")
            require(files.length() in 1..3)
            val now = Instant.now().epochSecond
            return (0 until files.length()).map { i ->
                val p=files.getJSONObject(i)
                val id=FitnessDataId(p.getLong("timestamp"),p.getInt("tzIn15Min"),p.getInt("version"),p.getInt("typeByte"))
                require(id.timestamp in now-30L*86400..now && id.tzIn15Min in 0..56 && id.version in 1..6 && SleepArchive.accepts(id))
                id
            }.distinct()
        }
    }
}
