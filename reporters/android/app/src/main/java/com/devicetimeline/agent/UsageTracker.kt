package com.devicetimeline.agent

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.KeyguardManager
import android.os.PowerManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

object UsageTracker {
    private var through = 0L
    private var timeline = ForegroundTimeline()
    fun reset() {
        through = 0L
        timeline = ForegroundTimeline()
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openUsageAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Read every transition since the last poll. On a cold start we only reconstruct
     * the present state from recent history, without claiming past recording time. */
    fun observe(context: Context): List<ForegroundAppInfo> {
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val bootstrap = through == 0L || end < through || end - through > 5 * 60_000
        if (bootstrap) timeline = ForegroundTimeline()
        val begin = if (bootstrap) end - 6 * 3_600_000 else through
        val events = manager.queryEvents(begin, end)
        if (events == null) {
            reset()
            val locked = !context.getSystemService(PowerManager::class.java).isInteractive ||
                context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
            return listOf(if (locked) ForegroundAppInfo("android.idle", "已锁屏或屏幕关闭", end, "android_locked_observation")
                else ForegroundAppInfo(null, "没有可确认的前台应用", end, "android_no_foreground"))
        }
        val changes = mutableListOf<ForegroundTimeline.Point>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val at = e.timeStamp
            if (at < begin || at >= end) continue
            val pkg = e.packageName
            // MOVEs and RESUMED/PAUSED use the same numeric IDs on newer Android.
            val points = when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> pkg?.let { timeline.resume(it, e.className, at) }.orEmpty()
                UsageEvents.Event.MOVE_TO_BACKGROUND, UsageEvents.Event.ACTIVITY_STOPPED ->
                    pkg?.let { timeline.pause(it, e.className, at) }.orEmpty()
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> timeline.inactive(at, e.eventType == UsageEvents.Event.KEYGUARD_SHOWN)
                UsageEvents.Event.SCREEN_INTERACTIVE, UsageEvents.Event.KEYGUARD_HIDDEN -> {
                    timeline.wake(e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN)
                    emptyList()
                }
                else -> emptyList()
            }
            if (!bootstrap) changes.addAll(points)
        }
        through = end
        val interactive = context.getSystemService(PowerManager::class.java).isInteractive &&
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        changes.add(timeline.now(end, interactive))
        return changes.map { point ->
            val name = when (val pkg = point.pkg) {
                null -> "没有可确认的前台应用"
                "android.idle" -> "已锁屏或屏幕关闭"
                else -> resolveAppName(context, pkg)
            }
            ForegroundAppInfo(point.pkg, name, point.at, point.source)
        }
    }

    private fun resolveAppName(context: Context, packageName: String): String {
        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }
}
