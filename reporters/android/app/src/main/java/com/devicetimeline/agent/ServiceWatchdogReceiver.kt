package com.devicetimeline.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// Fired by the AlarmManager alarm scheduled in TrackingService. If the service
// died (MIUI/HyperOS kill, Doze, OOM) but the user still wants tracking, bring it back.
class ServiceWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return
        if (!SettingsStore(context).load().isRunningEnabled) return

        val serviceIntent = Intent(context, TrackingService::class.java).apply {
            action = TrackingService.ACTION_START
        }
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }

    companion object {
        const val ACTION_WATCHDOG = "com.devicetimeline.agent.action.WATCHDOG"
    }
}
