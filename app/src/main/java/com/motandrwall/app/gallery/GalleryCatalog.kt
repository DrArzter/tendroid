package com.motandrwall.app.gallery

import org.json.JSONObject
import java.net.URI

data class GalleryWallpaper(
    val id: String,
    val version: Int,
    val title: String,
    val description: String,
    val authorName: String,
    val authorUrl: String,
    val license: String,
    val tags: List<String>,
    val states: List<String>,
    val minTendroidBuild: Int,
    val packageUrl: String,
    val packageSha256: String,
    val packageSizeBytes: Long,
    val previewUrl: String,
    val previewAlt: String,
)

internal fun parseGalleryCatalog(content: String): List<GalleryWallpaper> {
    require(content.length <= MAX_CATALOG_CHARS) { "Gallery catalog is unexpectedly large" }
    val root = JSONObject(content)
    require(root.getInt("schemaVersion") == 1) { "Unsupported gallery catalog version" }
    val entries = root.getJSONArray("wallpapers")
    require(entries.length() <= MAX_CATALOG_ITEMS) { "Gallery catalog has too many entries" }
    val ids = HashSet<String>()
    return List(entries.length()) { index ->
        val item = entries.getJSONObject(index)
        val id = item.requiredText("id", 100)
        require(ID_PATTERN.matches(id) && ids.add(id)) { "Invalid or duplicate gallery ID" }
        val author = item.getJSONObject("author")
        val packageInfo = item.getJSONObject("package")
        val preview = item.getJSONObject("preview")
        val sha256 = packageInfo.requiredText("sha256", 64).lowercase()
        require(SHA256_PATTERN.matches(sha256)) { "Invalid gallery package checksum" }
        val packageSize = packageInfo.getLong("sizeBytes")
        require(packageSize in 1..MAX_PACKAGE_BYTES) { "Invalid gallery package size" }
        val states = item.stringList("states", 3)
        require(states.isNotEmpty() && states.toSet().size == states.size && states.all { it in SUPPORTED_STATES }) {
            "Unsupported gallery wallpaper state"
        }
        GalleryWallpaper(
            id = id,
            version = item.positiveInt("version"),
            title = item.requiredText("title", 80),
            description = item.requiredText("description", 500),
            authorName = author.requiredText("name", 80),
            authorUrl = validateGalleryLink(author.requiredText("url", 500)),
            license = item.getJSONObject("license").requiredText("spdx", 80),
            tags = item.stringList("tags", 12),
            states = states,
            minTendroidBuild = item.positiveInt("minTendroidBuild"),
            packageUrl = validateGalleryAssetUrl(
                packageInfo.requiredText("url", 500),
                setOf("tendies"),
            ),
            packageSha256 = sha256,
            packageSizeBytes = packageSize,
            previewUrl = validateGalleryAssetUrl(
                preview.requiredText("url", 500),
                setOf("png", "jpg", "jpeg", "webp"),
            ),
            previewAlt = preview.requiredText("alt", 200),
        )
    }
}

internal fun validateGalleryAssetUrl(value: String, extensions: Set<String>): String {
    val uri = URI(value)
    require(
        uri.scheme == "https" &&
            uri.host == GALLERY_ASSET_HOST &&
            uri.port == -1 &&
            uri.userInfo == null &&
            uri.query == null &&
            uri.fragment == null &&
            uri.rawPath.startsWith(GALLERY_ASSET_PATH) &&
            uri.rawPath.split('/').none { it == ".." || it.equals("%2e%2e", ignoreCase = true) },
    ) { "Unsafe gallery asset URL" }
    val extension = uri.path.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    require(extension in extensions) { "Unexpected gallery asset type" }
    return uri.toASCIIString()
}

private fun validateGalleryLink(value: String): String {
    val uri = URI(value)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "Unsafe gallery link"
    }
    return uri.toASCIIString()
}

private fun JSONObject.requiredText(name: String, maximum: Int): String =
    getString(name).trim().also { require(it.isNotEmpty() && it.length <= maximum) }

private fun JSONObject.positiveInt(name: String): Int =
    getInt(name).also { require(it > 0) }

private fun JSONObject.stringList(name: String, maximum: Int): List<String> {
    val values = getJSONArray(name)
    require(values.length() <= maximum)
    return List(values.length()) { index ->
        values.getString(index).trim().also { require(it.isNotEmpty() && it.length <= 80) }
    }
}

internal const val GALLERY_CATALOG_URL =
    "https://raw.githubusercontent.com/DrArzter/tendroid-gallery/main/catalog/catalog-v1.json"
internal const val MAX_PACKAGE_BYTES = 25L * 1024 * 1024
internal const val MAX_PREVIEW_BYTES = 2L * 1024 * 1024
private const val MAX_CATALOG_CHARS = 2 * 1024 * 1024
private const val MAX_CATALOG_ITEMS = 1_000
private const val GALLERY_ASSET_HOST = "raw.githubusercontent.com"
private const val GALLERY_ASSET_PATH = "/DrArzter/tendroid-gallery/main/wallpapers/"
private val ID_PATTERN = Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)+$")
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val SUPPORTED_STATES = setOf("Sleep", "Locked", "Unlock")
