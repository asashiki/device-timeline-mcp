package com.devicetimeline.agent

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object HealthSyncScheduler {

    private const val TAG = "HealthSyncScheduler"
    private const val WORK_NAME = "device_timeline_hc_sync"

    fun schedule(context: Context, intervalMinutes: Long) {
        val interval = intervalMinutes.coerceAtLeast(15L)
        Log.i(TAG, "Scheduling HC sync every $interval min")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<HealthSyncWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        Log.i(TAG, "Cancelling HC sync")
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun runNow(context: Context) {
        Log.i(TAG, "Triggering immediate HC sync")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }
}
