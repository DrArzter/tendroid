package com.motandrwall.app.gallery

import android.content.Context
import com.motandrwall.app.BuildConfig
import com.motandrwall.app.tendies.ImportedTendies
import com.motandrwall.app.tendies.TendiesImporter
import com.motandrwall.app.tendies.TendiesSelectionStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class GalleryRepository(private val context: Context) {
    fun fetchCatalog(): List<GalleryWallpaper> {
        val bytes = downloadBytes(GALLERY_CATALOG_URL, MAX_CATALOG_BYTES)
        return parseGalleryCatalog(bytes.toString(Charsets.UTF_8))
    }

    fun fetchPreview(wallpaper: GalleryWallpaper): ByteArray =
        downloadBytes(wallpaper.previewUrl, MAX_PREVIEW_BYTES)

    fun install(wallpaper: GalleryWallpaper): ImportedTendies {
        require(BuildConfig.VERSION_CODE >= wallpaper.minTendroidBuild) {
            "Requires Tendroid build ${wallpaper.minTendroidBuild} or newer"
        }
        val directory = File(context.cacheDir, "gallery").apply { mkdirs() }
        val partial = File(directory, "${wallpaper.id}-${wallpaper.version}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            openConnection(wallpaper.packageUrl).also { connection ->
                val status = connection.responseCode
                if (status !in 200..299) error("Gallery download returned HTTP $status")
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > wallpaper.packageSizeBytes || total > MAX_PACKAGE_BYTES) {
                                error("Gallery package is larger than declared")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                        check(total == wallpaper.packageSizeBytes) { "Gallery package size does not match" }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual.equals(wallpaper.packageSha256, ignoreCase = true)) {
                "Gallery package checksum does not match"
            }
            val imported = FileInputStream(partial).use {
                TendiesImporter(File(context.filesDir, "imports")).import(it)
            }
            check(imported.sha256.equals(wallpaper.packageSha256, ignoreCase = true)) {
                "Imported package checksum changed"
            }
            TendiesSelectionStore(context).select(imported.file)
            return imported
        } finally {
            partial.delete()
        }
    }

    private fun downloadBytes(url: String, maximum: Long): ByteArray {
        val connection = openConnection(url)
        val status = connection.responseCode
        if (status !in 200..299) error("Gallery returned HTTP $status")
        val declared = connection.contentLengthLong
        if (declared > maximum) error("Gallery response is unexpectedly large")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maximum) error("Gallery response is unexpectedly large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "Tendroid/${BuildConfig.VERSION_NAME}")
        }

    private companion object {
        const val MAX_CATALOG_BYTES = 2L * 1024 * 1024
    }
}
