package com.devicetimeline.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Backup watchdog. The primary recovery path is the AlarmManager-based
 * ServiceWatchdogReceiver in TrackingService; this WorkManager job is a second
 * layer that survives more aggressive OEM kills (e.g. when AlarmManager alarms
 * also get dropped).
 *
 * Runs every ~15 minutes and re-launches the foreground service if the user has
 * tracking enabled but the service is not currently running.
 */
class WatchdogWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val settings = SettingsStore(ctx).load()
        val shouldRun = settings.isRunningEnabled &&
            settings.serverUrl.isNotBlank() &&
            settings.token.isNotBlank()
        if (!shouldRun) return Result.success()

        if (isServiceRunning(ctx)) return Result.success()

        val intent = Intent(ctx, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        runCatching {
            ContextCompat.startForegroundService(ctx, intent)
            SettingsStore(ctx).appendLog("WorkManager 兜底：重启监听服务")
        }
        return Result.success()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(ctx: Context): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return false
        return runCatching {
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == TrackingService::class.java.name }
        }.getOrDefault(false)
    }

    companion object {
        private const val UNIQUE_NAME = "device_timeline_watchdog"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
