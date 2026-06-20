package com.devicetimeline.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

class TrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsStore: SettingsStore

    private var trackingJob: Job? = null
    private var healthSyncJob: Job? = null
    private var lastHealthSyncAt = 0L
    private var lastSentKey = ""
    private var lastSuccessfulReportAt = 0L
    private var lastNotificationText = ""
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        createNotificationChannel()
        settingsStore.appendLog("服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                settingsStore.setRunningEnabled(false)
                settingsStore.appendLog("收到停止指令")
                WatchdogWorker.cancel(applicationContext)
                stopTracking()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                settingsStore.appendLog("收到启动指令")
                WatchdogWorker.enqueue(applicationContext)
                startTrackingIfNeeded()
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        val shouldRecover = settingsStore.load().isRunningEnabled
        stopTracking(cancelWatchdog = !shouldRecover)
        if (shouldRecover) {
            scheduleWatchdog(20_000)
            settingsStore.appendLog("服务被销毁，已安排自动恢复")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (settingsStore.load().isRunningEnabled) {
            scheduleWatchdog(15_000)
            settingsStore.appendLog("任务被系统移除，已安排自动恢复")
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun startTrackingIfNeeded() {
        if (trackingJob?.isActive == true) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("正在准备监听"), effectiveServiceType())
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在准备监听"))
        }
        acquireWakeLock()
        scheduleWatchdog()

        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    runOneTick()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val msg = "循环异常：${t.javaClass.simpleName}: ${t.message}"
                    settingsStore.appendLog(msg)
                    android.util.Log.e("TrackingService", msg, t)
                    delay(5_000)
                }
            }
        }

        startHealthSyncLoop()
    }

    // Run Health Connect sync from inside the FGS so it inherits the wake lock +
    // setAlarmClock watchdog. WorkManager's PeriodicWorkRequest gets killed by
    // MIUI/HyperOS when the app sits in the background for hours; this loop does
    // not, because the FGS is what keeps the process alive. (HealthSyncWorker
    // remains as a fallback + the "立即同步" button.)
    private fun startHealthSyncLoop() {
        if (healthSyncJob?.isActive == true) return
        healthSyncJob = serviceScope.launch {
            delay(INITIAL_HEALTH_SYNC_DELAY_MS)
            while (isActive) {
                try {
                    val settings = settingsStore.load()
                    val intervalMs = settings.hcSyncIntervalMinutes.coerceAtLeast(15L) * 60_000L
                    val now = System.currentTimeMillis()
                    if (settings.hcSyncEnabled && now - lastHealthSyncAt >= intervalMs) {
                        HealthSyncRunner.runOnce(this@TrackingService)
                        lastHealthSyncAt = System.currentTimeMillis()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    val msg = "健康同步异常：${t.javaClass.simpleName}: ${t.message}"
                    settingsStore.appendLog(msg)
                    android.util.Log.e("TrackingService", msg, t)
                }
                delay(HEALTH_SYNC_CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun runOneTick() {
        val settings = settingsStore.load()
        scheduleWatchdog(calculateWatchdogDelay(settings))

        if (!settings.isRunningEnabled) {
            setServiceState("等待启动", "等待用户启动监听")
            delay(2_000)
            return
        }

        if (!UsageTracker.hasUsageStatsPermission(this@TrackingService)) {
            setServiceState("未授予使用情况访问权限")
            delay(5_000)
            return
        }

        val appInfo = UsageTracker.currentForegroundApp(this@TrackingService)
        if (appInfo == null) {
            setServiceState("等待前台应用")
            delay(settings.heartbeatSeconds * 1_000L)
            return
        }

        val extras = DeviceContextProvider.readExtras(this@TrackingService)
        val timeBucket = appInfo.timestampMs / 10_000L
        val dedupKey = "${appInfo.packageName}:$timeBucket"
        val now = System.currentTimeMillis()
        val forceHeartbeat = now - lastSuccessfulReportAt >= FORCE_REPORT_INTERVAL_MS

        if (dedupKey != lastSentKey || forceHeartbeat) {
            val sent = ApiReporter.postReport(settings, appInfo, extras)
            if (sent) {
                lastSentKey = dedupKey
                lastSuccessfulReportAt = now
                setServiceState("正在上报：${appInfo.appName}", "上报成功：${appInfo.appName}")
            } else {
                setServiceState("上报失败，正在重试")
            }
        }

        delay(settings.heartbeatSeconds * 1_000L)
    }

    private fun stopTracking(cancelWatchdog: Boolean = true) {
        trackingJob?.cancel()
        trackingJob = null
        healthSyncJob?.cancel()
        healthSyncJob = null
        lastHealthSyncAt = 0L
        lastSentKey = ""
        lastSuccessfulReportAt = 0L
        lastNotificationText = ""
        releaseWakeLock()
        if (cancelWatchdog) cancelWatchdog()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeviceTimeline:TrackingWakeLock").apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun setServiceState(text: String, logText: String = text) {
        if (text == lastNotificationText) return
        lastNotificationText = text
        updateNotificationNow(text)
        settingsStore.appendLog(logText)
    }

    private fun createWatchdogIntent(): PendingIntent {
        val intent = Intent(this, ServiceWatchdogReceiver::class.java).apply {
            action = ServiceWatchdogReceiver.ACTION_WATCHDOG
        }
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWatchdog(delayMs: Long = DEFAULT_WATCHDOG_DELAY_MS) {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + delayMs
        // setAlarmClock bypasses Doze and MIUI/HyperOS battery restrictions.
        alarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, null),
            createWatchdogIntent(),
        )
    }

    private fun cancelWatchdog() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager.cancel(createWatchdogIntent())
    }

    private fun calculateWatchdogDelay(settings: AgentSettings): Long =
        max(90_000L, settings.heartbeatSeconds * 3_000L)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hibi 日々",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "持续上报前台应用状态到你的收集器"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Hibi 日々")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPi)
            .setShowWhen(false)
            .build()
        notif.flags = notif.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
        return notif
    }

    private fun effectiveServiceType(): Int {
        // specialUse instead of dataSync: dataSync has a 6h/24h runtime cap on Android 15+
        // (ForegroundServiceDidNotStopInTimeException, then ForegroundServiceStartNotAllowed).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    private fun updateNotificationNow(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_START = "com.devicetimeline.agent.action.START"
        const val ACTION_STOP = "com.devicetimeline.agent.action.STOP"

        private const val CHANNEL_ID = "device_timeline_agent_channel"
        private const val NOTIFICATION_ID = 11031
        private const val DEFAULT_WATCHDOG_DELAY_MS = 120_000L
        private const val FORCE_REPORT_INTERVAL_MS = 50_000L
        private const val INITIAL_HEALTH_SYNC_DELAY_MS = 20_000L
        private const val HEALTH_SYNC_CHECK_INTERVAL_MS = 5 * 60_000L
    }
}
