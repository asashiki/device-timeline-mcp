// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId

internal object SleepArchive {
    private val categories = setOf(8, 9, 12, 13, 32, 33)
    private val china = ZoneId.of("Asia/Shanghai")
    fun accepts(id: FitnessDataId) = id.typeByte in categories
    fun recent(id: FitnessDataId, now: Long): Boolean {
        val today = Instant.ofEpochSecond(now).atZone(china).toLocalDate()
        return id.timestamp in today.minusDays(6).atStartOfDay(china).toEpochSecond()..now
    }
    private fun group(ids: List<FitnessDataId>): List<Map<String, Any>> = ids.filter(::accepts).groupBy {
        Triple(Instant.ofEpochSecond(it.timestamp).atZone(china).toLocalDate().toString(), it.typeByte, it.version)
    }.entries.sortedByDescending { it.key.first }.map { (key, values) ->
        mapOf("fileDate" to key.first, "type" to key.second, "version" to key.third, "count" to values.size)
    }
    fun groups(ids: List<FitnessDataId>?) = ids?.let(::group)
    fun label(ids: List<FitnessDataId>?): String {
        val group = groups(ids) ?: return "未取得"
        if (group.isEmpty()) return "无睡眠候选"
        return group.take(10).joinToString("/") { "${it["fileDate"].toString().takeLast(5)}型${it["type"]}×${it["count"]}" } +
            (if (group.size > 10) " 等${group.size}组" else "")
    }

    /** Input was already CRC-checked by BandParts. Match Gadgetbridge 2026.11.1
     * SleepStagesParser's activity subtype 3 DETAILS v2 and SleepDetailsParser's
     * activity subtype 8 DETAILS v2. The 9/13 SUMMARY layouts have no verified
     * decoder in that release; archive them for inspection only. */
    fun envelope(bytes: ByteArray): FitnessFileFrame? {
        val id = FitnessDataId.parse(bytes, 4) ?: return null
        if (!accepts(id) || bytes.size < 16 || bytes[11] != 0.toByte()) return null
        val bits = when {
            id.typeByte == 33 -> return FitnessFileFrame.parse(bytes)
            id.typeByte == 32 && id.version == 2 -> 1
            else -> 0 // preserve unexplained payloads without imposing a bitmap layout
        }
        if (bytes.size < 16+bits) return null
        return FitnessFileFrame(id, bytes.copyOfRange(12,12+bits),bytes.copyOfRange(12+bits,bytes.size-4))
    }
    fun knownSession(frame: FitnessFileFrame, now: Long): BandSleep? {
        val id = frame.dataId
        if (id.typeByte == 32 && id.version == 2) {
            val buf = frame.body
            if (buf.size < 9 || buf[0] != 1.toByte()) return null
            // Native v2 subtype 8 has 1-byte validity mask and LE bed/wake fields.
            val mask = frame.bitmap.singleOrNull()?.toInt() ?: return null
            if ((mask and 0xc0) != 0xc0) return null
            val data = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            return session(uint(data,1),uint(data,5),null,now)
        }
        if (id.typeByte != 12 || id.version != 2 || frame.body.size < 29) return null
        val body = ByteBuffer.wrap(frame.body).order(ByteOrder.LITTLE_ENDIAN)
        // 7 bytes metadata, sleep duration u16, start/end times u32, 3 bytes
        // metadata, four u16 totals, 1 byte metadata, then 5-byte stage entries.
        if ((frame.body.size-29)%5 != 0 || frame.body[0] != (-1).toByte() || frame.body[1] != (-1).toByte()) return null
        val duration = body.getShort(7).toInt() and 65535
        return session(uint(body,9),uint(body,13),duration,now)
    }
    private fun uint(b: ByteBuffer, n: Int) = b.getInt(n).toLong() and 0xffffffffL
    private fun session(start: Long,end: Long,asleep: Int?, now: Long): BandSleep? {
        if (start < 946684800L || end > now || end-start !in 60L..86_400L) return null
        val span = ((end-start)/60).toInt()
        if (asleep != null && asleep !in 1..((end-start+59)/60).toInt()) return null
        return BandSleep(start,end,span,asleep)
    }
}
