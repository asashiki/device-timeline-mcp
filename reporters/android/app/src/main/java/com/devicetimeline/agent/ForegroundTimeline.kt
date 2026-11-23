package com.devicetimeline.agent

/** Replays package activity lifecycle events. A delayed STOP from an earlier
 * activity cannot close its replacement. Current polls are separate observations. */
internal class ForegroundTimeline {
    data class Point(val pkg: String?, val at: Long, val source: String)
    private var pkg: String? = null
    private var activity: String? = null
    private var screenOff = false
    private var keyguard = false

    fun resume(next: String, name: String?, at: Long): List<Point> {
        if (screenOff || keyguard) return emptyList()
        val previous = pkg
        pkg = next
        activity = name
        if (previous == next) return emptyList()
        return listOfNotNull(previous?.let { Point(it, at, "android_next_resume") },
            Point(next, at, "android_resume"))
    }

    fun pause(ended: String, name: String?, at: Long): List<Point> {
        if (pkg != ended || (activity != null && name != null && activity != name)) return emptyList()
        pkg = null
        activity = null
        return listOf(Point(ended, at, "android_pause"), Point(null, at, "android_no_foreground"))
    }

    fun inactive(at: Long, isKeyguard: Boolean): List<Point> {
        val previouslyOff = screenOff || keyguard
        if (isKeyguard) keyguard = true else screenOff = true
        val previous = pkg
        pkg = null
        activity = null
        if (previouslyOff) return emptyList()
        return listOfNotNull(previous?.let { Point(it, at, "android_screen_end") },
            Point("android.idle", at, "android_screen_inactive"))
    }

    fun wake(isKeyguard: Boolean) {
        if (isKeyguard) keyguard = false else screenOff = false
    }

    fun now(at: Long, interactive: Boolean): Point {
        if (!interactive) {
            pkg = null
            activity = null
            return Point("android.idle", at, "android_locked_observation")
        }
        screenOff = false
        keyguard = false
        return Point(pkg, at, if (pkg == null) "android_no_foreground" else "android_foreground_observation")
    }
}
