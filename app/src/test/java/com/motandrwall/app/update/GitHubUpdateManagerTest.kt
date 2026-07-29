package com.motandrwall.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubUpdateManagerTest {
    @Test
    fun parsesPublishedSha256Digest() {
        val digest = "A".repeat(64)

        assertEquals("a".repeat(64), parseSha256Digest("sha256:$digest"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingDigest() {
        parseSha256Digest("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedDigest() {
        parseSha256Digest("sha256:${"z".repeat(64)}")
    }
}
