package com.devicetimeline.agent

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

// Fallback periodic + on-demand sync path. The primary sync happens inside
// TrackingService so it inherits the FGS background guarantees (wake lock +
// setAlarmClock watchdog); this worker stays as a safety net and powers the
// "立即同步" button.
class HealthSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return when (HealthSyncRunner.runOnce(applicationContext)) {
            HealthSyncRunner.Outcome.OK,
            HealthSyncRunner.Outcome.EMPTY,
            HealthSyncRunner.Outcome.SKIPPED -> Result.success()
            HealthSyncRunner.Outcome.RETRY -> Result.retry()
            HealthSyncRunner.Outcome.FAILURE -> Result.failure()
        }
    }
}
