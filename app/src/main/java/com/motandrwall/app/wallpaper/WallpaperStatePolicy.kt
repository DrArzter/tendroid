package com.motandrwall.app.wallpaper

internal object WallpaperStatePolicy {
    const val SLEEP = "Sleep"
    const val LOCKED = "Locked"
    const val UNLOCK = "Unlock"

    fun currentState(
        interactive: Boolean,
        keyguardLocked: Boolean,
        keyguardGoingAway: Boolean,
        useSleepWhileScreenOff: Boolean,
    ): String = when {
        !interactive -> screenOffState(useSleepWhileScreenOff)
        keyguardGoingAway || !keyguardLocked -> UNLOCK
        else -> LOCKED
    }

    fun screenOffState(useSleepWhileScreenOff: Boolean): String =
        if (useSleepWhileScreenOff) SLEEP else LOCKED

    fun shouldAnimateWakeToLocked(wokeFromAlwaysOnDisplay: Boolean): Boolean =
        wokeFromAlwaysOnDisplay
}
