package com.motandrwall.app.update

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubUpdateManagerTest {
    @Test
    fun selectsHighestBuildWhenGitHubReturnsReleasesOutOfOrder() {
        val digest = "a".repeat(64)
        val content = """
            [
              {
                "tag_name": "build-9",
                "name": "Tendroid build 9",
                "draft": false,
                "assets": [{
                  "name": "tendroid-debug.apk",
                  "browser_download_url": "https://github.com/DrArzter/tendroid/releases/download/build-9/tendroid-debug.apk",
                  "digest": "sha256:$digest"
                }]
              },
              {
                "tag_name": "build-11",
                "name": "Tendroid build 11",
                "draft": false,
                "assets": [{
                  "name": "tendroid-debug.apk",
                  "browser_download_url": "https://github.com/DrArzter/tendroid/releases/download/build-11/tendroid-debug.apk",
                  "digest": "sha256:$digest"
                }]
              }
            ]
        """.trimIndent()

        assertEquals(11, parseLatestGitHubRelease(content).buildNumber)
    }

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
