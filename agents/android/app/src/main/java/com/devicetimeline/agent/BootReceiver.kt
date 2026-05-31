package com.devicetimeline.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val accepted = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.QUICKBOOT_POWERON"
        if (!accepted) return

        val settings = SettingsStore(context).load()
        if (!(settings.autoStartOnBoot && settings.isRunningEnabled)) return

        WatchdogWorker.enqueue(context)
        val serviceIntent = Intent(context, TrackingService::class.java).apply {
            this.action = TrackingService.ACTION_START
        }
        runCatching { ContextCompat.startForegroundService(context, serviceIntent) }
    }
}
