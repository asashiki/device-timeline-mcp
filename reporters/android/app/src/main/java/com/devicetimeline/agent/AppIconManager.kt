package com.devicetimeline.agent

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

// Duolingo-style dynamic launcher icon. Exactly one of three activity-aliases is
// enabled at a time; we flip between them with setComponentEnabledSetting using
// DONT_KILL_APP so our own foreground service is never killed by the change.
//
// Limitation (by design): if the app is force-stopped / never opened, no code of
// ours runs, so we cannot change the icon for that case. This only reflects
// "service still alive but hasn't managed to report for a while".
object AppIconManager {

    enum class IconState { HEALTHY, STALE, GONE }

    private const val DEFAULT = ".LauncherDefault"
    private const val POUT = ".LauncherPout"
    private const val CRY = ".LauncherCry"
    private val ALL = listOf(DEFAULT, POUT, CRY)

    fun apply(context: Context, state: IconState) {
        val want = when (state) {
            IconState.HEALTHY -> DEFAULT
            IconState.STALE -> POUT
            IconState.GONE -> CRY
        }
        val pm = context.packageManager
        val pkg = context.packageName
        // Enable the target first so there is never a moment with zero launcher
        // entries, then disable the others.
        setState(pm, ComponentName(pkg, pkg + want), enable = true)
        for (alias in ALL) {
            if (alias == want) continue
            setState(pm, ComponentName(pkg, pkg + alias), enable = false)
        }
    }

    private fun setState(pm: PackageManager, comp: ComponentName, enable: Boolean) {
        // Map the implicit "DEFAULT" state to the manifest default (LauncherDefault
        // ships enabled, the others disabled) so a fresh start that's already in the
        // right state doesn't needlessly churn the launcher icon.
        val manifestDefaultEnabled = comp.className.endsWith(DEFAULT)
        val current = when (pm.getComponentEnabledSetting(comp)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> manifestDefaultEnabled
        }
        if (current == enable) return
        runCatching {
            pm.setComponentEnabledSetting(
                comp,
                if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }
}
