package com.motandrwall.app.update

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.motandrwall.app.BuildConfig
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

class GitHubUpdateManager(private val activity: Activity) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()

    fun check(onResult: (UpdateCheckResult) -> Unit) {
        executor.execute {
            val result = runCatching(::fetchLatestRelease).fold(
                onSuccess = { release ->
                    if (release.buildNumber > BuildConfig.VERSION_CODE) {
                        UpdateCheckResult.Available(release)
                    } else {
                        UpdateCheckResult.Current
                    }
                },
                onFailure = { UpdateCheckResult.Unavailable(it.message ?: "Update check failed") },
            )
            activity.runOnUiThread { if (!activity.isDestroyed) onResult(result) }
        }
    }

    fun downloadAndInstall(release: GitHubRelease, onResult: (String) -> Unit) {
        executor.execute {
            val result = runCatching { download(release) }
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = {
                        if (requestInstallPermissionIfNeeded()) {
                            onResult("Allow installs from Tendroid, then tap Update again.")
                        } else {
                            launchInstaller()
                            onResult("Android installer opened.")
                        }
                    },
                    onFailure = { onResult(it.message ?: "Update download failed") },
                )
            }
        }
    }

    override fun close() {
        executor.shutdownNow()
    }

    private fun fetchLatestRelease(): GitHubRelease {
        val connection = openConnection(RELEASES_API)
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            throw IllegalStateException("The GitHub release channel is private.")
        }
        if (status !in 200..299) throw IllegalStateException("GitHub returned HTTP $status")
        val releases = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        for (releaseIndex in 0 until releases.length()) {
            val release = releases.getJSONObject(releaseIndex)
            if (release.optBoolean("draft")) continue
            val buildNumber = release.optString("tag_name").removePrefix("build-").toIntOrNull() ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.getJSONObject(assetIndex)
                if (asset.optString("name") != RELEASE_ASSET_NAME) continue
                return GitHubRelease(
                    buildNumber = buildNumber,
                    title = release.optString("name").ifBlank { "Tendroid build $buildNumber" },
                    downloadUrl = asset.getString("browser_download_url"),
                    sha256 = asset.optString("digest").removePrefix("sha256:").takeIf(String::isNotBlank),
                )
            }
        }
        throw IllegalStateException("No Tendroid APK was found in GitHub Releases.")
    }

    private fun download(release: GitHubRelease) {
        require(release.downloadUrl.startsWith(RELEASE_DOWNLOAD_PREFIX)) { "Unsafe update URL" }
        val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "${UpdateFileProvider.UPDATE_FILE_NAME}.partial")
        val target = File(directory, UpdateFileProvider.UPDATE_FILE_NAME)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val connection = openConnection(release.downloadUrl)
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Download returned HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_APK_BYTES) throw IllegalStateException("Update APK is unexpectedly large")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !actual.equals(release.sha256, ignoreCase = true)) {
                throw IllegalStateException("Update checksum does not match GitHub")
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace old update")
            if (!partial.renameTo(target)) throw IllegalStateException("Could not store update APK")
        } finally {
            partial.delete()
        }
    }

    private fun requestInstallPermissionIfNeeded(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()) return false
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            ),
        )
        return true
    }

    private fun launchInstaller() {
        val uri = Uri.Builder()
            .scheme("content")
            .authority(UpdateFileProvider.AUTHORITY)
            .appendPath(UpdateFileProvider.UPDATE_FILE_NAME)
            .build()
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, UpdateFileProvider.APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Tendroid/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }

    private companion object {
        const val RELEASES_API = "https://api.github.com/repos/DrArzter/tendroid/releases?per_page=10"
        const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/DrArzter/tendroid/releases/download/"
        const val RELEASE_ASSET_NAME = "tendroid-debug.apk"
        const val MAX_APK_BYTES = 150L * 1024 * 1024
    }
}

data class GitHubRelease(
    val buildNumber: Int,
    val title: String,
    val downloadUrl: String,
    val sha256: String?,
)

sealed interface UpdateCheckResult {
    data object Current : UpdateCheckResult
    data class Available(val release: GitHubRelease) : UpdateCheckResult
    data class Unavailable(val reason: String) : UpdateCheckResult
}
