// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessDataId
import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import com.example.healthapp.data.protocol.fitness.FitnessSleepReportParser
import com.example.healthapp.data.protocol.fitness.FitnessSync

internal data class SleepPoll(val poll: String, val observed: Long, val minutes: Int?, val nativeAt: Long?)
internal data class ProgressSignal(val kind: String, val at: Long, val value: Int, val origin: String, val basis: String, val previousAt: Long? = null, val previousMinutes: Int? = null)
internal data class BandProgress(val start: Long, val nativeAt: Long?, val minutes: Int?) {
    fun compare(previous: SleepPoll?, now: Long): ProgressSignal? {
        if (previous == null || now - previous.observed !in 45L..600L) return null
        val counterRose = previous.minutes != null && minutes != null && plausibleGrowth(previous.minutes, minutes, now - previous.observed)
        if (nativeAt != null) {
            if (now - nativeAt !in 0L..300L || previous.nativeAt == null || nativeAt - previous.nativeAt !in 30L..600L) return null
            if (minutes == null) return ProgressSignal("sleep_interval_progress", nativeAt, 1,"band_sleep_report","device_sample")
            if (!counterRose || minutes > (nativeAt - start + 59L) / 60) return null
            return ProgressSignal("sleep_progress",nativeAt,minutes,"band_sleep_report","device_sample")
        }
        if (!counterRose || previous.nativeAt != null) return null
        return ProgressSignal("sleep_progress",now,minutes!!,"band_live_progress","phone_observed_growth",previous.observed,previous.minutes)
    }
    companion object {
        fun from(frame: FitnessFileFrame, now: Long): BandProgress? {
            if (!FitnessSync.isFullDaySleep(frame.dataId) || frame.dataId.version !in 1..6) return null
            val report = FitnessSleepReportParser.parse(frame) ?: return null
            val start = report.startTimeSec ?: return null
            if (report.sleepFull != 0 || (report.endTimeSec ?: 0L) > 0 || start !in (now - 86_400)..(now - 60)) return null
            // These points are part of THIS unfinished sleep report, timestamped by the band.
            // Never substitute standalone heart rate, wall-clock transfer time or start + minutes.
            fun last(first: Long?, interval: Int, points: List<Int>, valid: IntRange): Long? {
                if (first == null || first < start || interval !in 1..3600) return null
                val i = points.indexOfLast { it in valid }
                if (i < 0) return null
                return (first + i.toLong() * interval).takeIf { it in (start + 60)..now }
            }
            val native = listOfNotNull(last(report.heartRateFirstTimeSec, report.heartRateIntervalSec, report.heartRates, 1..254),
                last(report.bloodOxygenFirstTimeSec, report.bloodOxygenIntervalSec, report.bloodOxygens, 1..100)).maxOrNull()
            // Unknown on v1-v4. An older native endpoint remains an old observation, even if
            // a later phone poll fetched it again. Reject physically impossible sensor totals.
            val minutes = report.asleepDurationMin?.takeIf { it in 1L..((now - start + 59)/60) }?.toInt()
            if (native == null && minutes == null) return null
            return BandProgress(start, native, minutes)
        }
        fun plausibleGrowth(oldMinutes: Int, newMinutes: Int, gapSeconds: Long): Boolean =
            gapSeconds in 45..600 && newMinutes - oldMinutes in 1..((gapSeconds + 59)/60).toInt()+1
    }
}

/** Join a requested, freshly transferred report with this connection's TODAY index.
 * A recovery read may arrive before the index. Keep its original observation time. */
internal class CurrentSleepPoll {
    data class Observation(val point: BandProgress, val at: Long)
    private val requested = mutableSetOf<FitnessDataId>()
    private val today = mutableSetOf<FitnessDataId>()
    private val pending = mutableMapOf<FitnessDataId, Observation>()
    private val observedStarts = mutableSetOf<Long>()

    @Synchronized fun requesting(ids: Collection<FitnessDataId>) {
        requested.addAll(ids.filter(FitnessSync::isFullDaySleep))
    }

    @Synchronized fun received(frame: FitnessFileFrame, wholeThisPoll: Boolean, at: Long): List<Observation> {
        if (!wholeThisPoll || frame.dataId !in requested) return emptyList()
        val point = BandProgress.from(frame, at) ?: return emptyList()
        pending.putIfAbsent(frame.dataId, Observation(point, at))
        return matched()
    }

    @Synchronized fun indexed(ids: Collection<FitnessDataId>): List<Observation> {
        today.addAll(ids.filter(FitnessSync::isFullDaySleep))
        return matched()
    }

    private fun matched(): List<Observation> = buildList {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (id, observation) = iterator.next()
            if (id !in today) continue
            iterator.remove()
            if (observedStarts.add(observation.point.start)) add(observation)
        }
    }
}
