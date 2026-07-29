package com.motandrwall.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDefaultMigrationTest {
    @Test
    fun refreshesTheKnownLegacyBundledRoxy() {
        val legacy = File(
            "0f91bb979a9ac39338ebec981b2d1420682d5f0f1e3653d6ddd3def333368164.tendies",
        )

        assertTrue(shouldRefreshBundledDefault(legacy))
    }

    @Test
    fun preservesUserImportedPackages() {
        assertFalse(shouldRefreshBundledDefault(File("my-wallpaper.tendies")))
        assertFalse(shouldRefreshBundledDefault(null))
    }
}
