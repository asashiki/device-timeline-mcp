// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.fitness.FitnessSync

internal class RecentMinuteFiles(saved: String) {
    private val ids = linkedSetOf<FitnessDataId>()
    init {
        saved.split(';').take(32).forEach { text ->
            val parts = text.split(',')
            if (parts.size == 4) {
                val values = parts.map { it.toLongOrNull() }
                if (values.all { it != null })
                    ids.add(FitnessDataId(values[0]!!, values[1]!!.toInt(), values[2]!!.toInt(), values[3]!!.toInt()))
            }
        }
    }

    private fun valid(id: FitnessDataId, now: Long) =
        FitnessSync.isDailyRecord(id) && id.version in 2..5 && now - id.timestamp in 0..5400

    @Synchronized
    fun add(incoming: Iterable<FitnessDataId>, now: Long): String? {
        val limited = (ids + incoming).filter { valid(it, now) }.sortedByDescending { it.timestamp }.take(32).toSet()
        if (limited == ids) return null
        ids.clear(); ids.addAll(limited)
        return ids.joinToString(";") { "${it.timestamp},${it.tzIn15Min},${it.version},${it.typeByte}" }
    }

    /** A file emitted just now typically has only its first minute. Prefer the
     * previous few known blocks once they've had time to fill, not just the newest ID. */
    @Synchronized
    fun forRefresh(now: Long, indexed: List<FitnessDataId>): List<FitnessDataId> =
        (ids + indexed).filter { valid(it, now) && now - it.timestamp >= 120 }
            .sortedByDescending { it.timestamp }.take(3)
}
