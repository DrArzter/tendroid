package com.motandrwall.app.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperStatePolicyTest {
    @Test
    fun screenOffWithoutAlwaysOnDisplayStaysLocked() {
        assertEquals(WallpaperStatePolicy.LOCKED, WallpaperStatePolicy.screenOffState(false))
        assertEquals(
            WallpaperStatePolicy.LOCKED,
            WallpaperStatePolicy.currentState(
                interactive = false,
                keyguardLocked = true,
                keyguardGoingAway = false,
                useSleepWhileScreenOff = false,
            ),
        )
    }

    @Test
    fun screenOffWithAlwaysOnDisplayUsesSleep() {
        assertEquals(WallpaperStatePolicy.SLEEP, WallpaperStatePolicy.screenOffState(true))
    }

    @Test
    fun wakeOnlyAnimatesWhenAlwaysOnDisplayWasActuallyVisible() {
        assertFalse(WallpaperStatePolicy.shouldAnimateWakeToLocked(false))
        assertTrue(WallpaperStatePolicy.shouldAnimateWakeToLocked(true))
    }

    @Test
    fun interactiveStateStillFollowsKeyguard() {
        assertEquals(
            WallpaperStatePolicy.LOCKED,
            WallpaperStatePolicy.currentState(true, true, false, false),
        )
        assertEquals(
            WallpaperStatePolicy.UNLOCK,
            WallpaperStatePolicy.currentState(true, false, false, false),
        )
        assertEquals(
            WallpaperStatePolicy.UNLOCK,
            WallpaperStatePolicy.currentState(true, true, true, false),
        )
    }
}
