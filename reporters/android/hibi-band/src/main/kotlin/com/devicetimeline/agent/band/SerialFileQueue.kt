// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId

internal object SerialFileQueue {
    const val MAX_FILES = 6

    fun next(
        now: Long,
        today: List<FitnessDataId>,
        exact: FitnessDataId?,
        dueSleep: List<FitnessDataId>,
        minutes: List<FitnessDataId>,
        issued: Set<FitnessDataId>,
        lastSleepRequest: Map<FitnessDataId, Long>,
    ): FitnessDataId? {
        if (issued.size >= MAX_FILES) return null
        val sleepCount = issued.count(SleepArchive::accepts)
        // A newly advertised version can have the same ID as yesterday's completed
        // or archived frame. Refresh recently listed sleep even when catchup is empty.
        val current = today.filter { SleepArchive.accepts(it) && now-it.timestamp in 0..172800L &&
            now-(lastSleepRequest[it] ?: 0L) >= 840L }.sortedByDescending { it.timestamp }
        val sleep = if (sleepCount < SleepCatchup.MAX_PER_CYCLE) {
            (current + listOfNotNull(exact) + dueSleep).firstOrNull { it !in issued }
        } else null
        val minute = minutes.firstOrNull { it !in issued }
        // Missing sleep files are retried later; a timeout must not starve new minutes.
        return if (sleepCount <= issued.size-sleepCount) sleep ?: minute else minute ?: sleep
    }
}
