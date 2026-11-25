// SPDX-License-Identifier: AGPL-3.0-only
package com.devicetimeline.agent.band

import com.example.healthapp.data.protocol.fitness.FitnessFileFrame
import com.example.healthapp.data.protocol.fitness.FitnessSleepReportParser
import com.example.healthapp.data.protocol.fitness.FitnessSync

/** Native timestamps belong to the session; device capture/transfer time is not a sleep timestamp. */
internal data class BandSleep(val start: Long, val end: Long, val elapsedMinutes: Int, val asleepMinutes: Int?) {
    companion object {
        // Native v4 can supply valid bed and wake fields before setting sleepFull.
        // Keep this interval independently, so it cannot count as completed sleep.
        fun provisional(frame: FitnessFileFrame, now: Long): BandSleep? {
            if (!FitnessSync.isFullDaySleep(frame.dataId) || frame.dataId.version != 4) return null
            val report = FitnessSleepReportParser.parse(frame) ?: return null
            val start = report.startTimeSec ?: return null
            val end = report.endTimeSec ?: return null
            if (report.sleepFull != 0 || start != frame.dataId.timestamp ||
                start < 946684800L || end > now || end - start !in 60L..86400L) return null
            return BandSleep(start, end, ((end-start)/60).toInt(), null)
        }

        fun decode(frame: FitnessFileFrame, now: Long): BandSleep? {
            if (!FitnessSync.isFullDaySleep(frame.dataId)) return SleepArchive.knownSession(frame, now)
            if (frame.dataId.version !in 1..6) return null
            val report = FitnessSleepReportParser.parse(frame) ?: return null
            val start = report.startTimeSec ?: return null
            val end = report.endTimeSec ?: return null
            if (report.sleepFull != 1 || start < 946684800L || end <= start || end > now) return null
            val span = end - start
            val minutes = span / 60
            if (minutes !in 1..1440) return null
            // Native v1-v4 has times only; schema v5/v6 gates actual sleep duration by a validity bit.
            val asleep = report.asleepDurationMin?.takeIf { it > 0 && it <= (span + 59) / 60 }?.toInt()
            return BandSleep(start, end, minutes.toInt(), asleep)
        }
    }
}
