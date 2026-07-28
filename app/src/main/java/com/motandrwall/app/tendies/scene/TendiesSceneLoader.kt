package com.motandrwall.app.tendies.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.motandrwall.app.tendies.InvalidTendiesException
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max
import kotlin.math.roundToInt

class TendiesSceneLoader {
    companion object {
        private const val MAX_CAML_BYTES = 4 * 1024 * 1024
        private const val MAX_BITMAP_PIXELS = 16_000_000L
        private const val MAX_TEXTURE_EDGE = 1024
    }

    fun load(packageFile: File): TendiesScene {
        val bitmaps = linkedMapOf<String, Bitmap>()
        try {
            ZipFile(packageFile).use { zip ->
                val camlEntries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.lowercase(Locale.ROOT).endsWith("/main.caml") }
                    .sortedWith(compareBy(::sceneOrder, ZipEntry::getName))
                    .toList()
                if (camlEntries.isEmpty()) {
                    throw InvalidTendiesException("No CAML scene was found")
                }

                val documents = camlEntries.map { entry ->
                    if (entry.size > MAX_CAML_BYTES) {
                        throw InvalidTendiesException("CAML scene is too large")
                    }
                    val bytes = readLimited(zip.getInputStream(entry), MAX_CAML_BYTES)
                    val basePath = entry.name.substringBeforeLast('/', missingDelimiterValue = "")
                    parseDocument(bytes, basePath) { path ->
                        bitmaps.getOrPut(path) { decodeBitmap(zip, path) }
                    }
                }
                return TendiesScene(documents, bitmaps)
            }
        } catch (error: Exception) {
            bitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
            if (error is InvalidTendiesException) throw error
            throw InvalidTendiesException("Could not load the Tendies scene", error)
        }
    }

    private fun parseDocument(
        bytes: ByteArray,
        basePath: String,
        bitmapLoader: (String) -> Bitmap,
    ): SceneDocument {
        val prefix = bytes.decodeToString(0, minOf(bytes.size, 1024)).uppercase()
        if ("<!DOCTYPE" in prefix || "<!ENTITY" in prefix) {
            throw InvalidTendiesException("CAML with DTD or entities is not allowed")
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isXIncludeAware = false }
            runCatching { setExpandEntityReferences(false) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }
        val document = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(ByteArrayInputStream(ByteArray(0))) }
        }.parse(ByteArrayInputStream(bytes))

        val rootElement = document.documentElement.childElements()
            .firstOrNull { it.localTagName() == "CALayer" }
            ?: throw InvalidTendiesException("CAML document has no root layer")
        val imagePaths = linkedSetOf<String>()
        val root = parseLayer(rootElement, basePath, imagePaths)
        imagePaths.forEach { bitmapLoader(it) }
        return SceneDocument(root, parseStates(rootElement), parseTransitions(rootElement))
    }

    private fun parseLayer(element: Element, basePath: String, imagePaths: MutableSet<String>): SceneLayer {
        val bounds = parseNumbers(element.getAttribute("bounds"), listOf(0f, 0f, 0f, 0f))
        val position = parseNumbers(element.getAttribute("position"), listOf(bounds[2] / 2f, bounds[3] / 2f))
        val anchor = parseNumbers(element.getAttribute("anchorPoint"), listOf(0.5f, 0.5f))
        val contents = element.directChild("contents")
        val imageSource = contents?.childElements()
            ?.firstOrNull { it.localTagName() == "CGImage" }
            ?.getAttribute("src")
            ?.takeIf(String::isNotBlank)
        val imagePath = imageSource?.let { source ->
            normalizeAssetPath(if (basePath.isBlank()) source else "$basePath/$source")
        }
        imagePath?.let(imagePaths::add)

        val sublayers = element.directChild("sublayers")
            ?.childElements()
            .orEmpty()
            .filter { it.localTagName() == "CALayer" || it.localTagName() == "CATextLayer" }
            .map { parseLayer(it, basePath, imagePaths) }
            .sortedBy(SceneLayer::zPosition)

        val isText = element.localTagName() == "CATextLayer"
        val text = if (isText) {
            element.childElements().firstOrNull { it.localTagName() == "string" }
                ?.getAttribute("value")
        } else null
        val fontName = if (isText) {
            element.childElements().firstOrNull { it.localTagName() == "font" }
                ?.getAttribute("value")
        } else null

        return SceneLayer(
            id = element.getAttribute("id").ifBlank { "anonymous-${element.hashCode()}" },
            name = element.getAttribute("name").takeIf(String::isNotBlank),
            boundsX = bounds[0],
            boundsY = bounds[1],
            width = bounds[2].coerceAtLeast(0f),
            height = bounds[3].coerceAtLeast(0f),
            positionX = position[0],
            positionY = position[1],
            anchorX = anchor[0],
            anchorY = anchor[1],
            rotationRadians = element.floatAttribute("transform.rotation.z", 0f),
            opacity = element.floatAttribute("opacity", 1f).coerceIn(0f, 1f),
            zPosition = element.floatAttribute("zPosition", 0f),
            geometryFlipped = element.getAttribute("geometryFlipped") == "1",
            masksToBounds = element.getAttribute("masksToBounds") == "1",
            backgroundColor = parseColor(element.getAttribute("backgroundColor")),
            imagePath = imagePath,
            text = text,
            fontName = fontName,
            fontSize = element.floatAttribute("fontSize", 17f),
            foregroundColor = parseColor(element.getAttribute("foregroundColor")) ?: Color.WHITE,
            children = sublayers,
        )
    }

    private fun parseStates(root: Element): Map<String, Map<String, LayerOverrides>> {
        val statesElement = root.directChild("states") ?: return emptyMap()
        return statesElement.childElements()
            .filter { it.localTagName() == "LKState" }
            .associate { state ->
                val overrides = linkedMapOf<String, LayerOverrides>()
                state.directChild("elements")?.childElements()
                    .orEmpty()
                    .filter { it.localTagName() == "LKStateSetValue" }
                    .forEach { valueElement ->
                        val target = valueElement.getAttribute("targetId")
                        val keyPath = valueElement.getAttribute("keyPath")
                        val value = valueElement.childElements().firstOrNull { it.localTagName() == "value" }
                            ?.getAttribute("value")
                            ?.toFloatOrNull()
                            ?: return@forEach
                        val layer = overrides.getOrPut(target) { LayerOverrides() }
                        when (keyPath) {
                            "position.x" -> layer.positionX = value
                            "position.y" -> layer.positionY = value
                            "transform.rotation.z" -> layer.rotationRadians = value
                            "opacity" -> layer.opacity = value.coerceIn(0f, 1f)
                        }
                    }
                state.getAttribute("name") to overrides
            }
    }

    private fun parseTransitions(root: Element): List<SceneTransition> {
        val transitions = root.directChild("stateTransitions") ?: return emptyList()
        return transitions.childElements()
            .filter { it.localTagName() == "LKStateTransition" }
            .mapNotNull { transition ->
                val animations = transition.getElementsByTagNameNS("*", "animation")
                var longestDurationSeconds: Float? = null
                for (index in 0 until animations.length) {
                    val animation = animations.item(index) as? Element ?: continue
                    val duration = animation.getAttribute("duration").toFloatOrNull() ?: continue
                    longestDurationSeconds = maxOf(longestDurationSeconds ?: duration, duration)
                }
                val durationSeconds = longestDurationSeconds ?: return@mapNotNull null
                SceneTransition(
                    fromState = transition.getAttribute("fromState").ifBlank { "*" },
                    toState = transition.getAttribute("toState").ifBlank { "*" },
                    durationMillis = durationSeconds.coerceAtLeast(0f) * 1_000f,
                )
            }
    }

    private fun decodeBitmap(zip: ZipFile, path: String): Bitmap {
        val entry = zip.getEntry(path) ?: throw InvalidTendiesException("Missing image asset: $path")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw InvalidTendiesException("Invalid image asset: $path")
        }
        var sampleSize = 1
        while (
            bounds.outWidth.toLong() / sampleSize * (bounds.outHeight.toLong() / sampleSize) >
            MAX_BITMAP_PIXELS
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = zip.getInputStream(entry).use {
            BitmapFactory.decodeStream(it, null, options)
                ?: throw InvalidTendiesException("Could not decode image asset: $path")
        }
        val longestEdge = max(decoded.width, decoded.height)
        val result = if (longestEdge > MAX_TEXTURE_EDGE) {
            val scale = MAX_TEXTURE_EDGE.toFloat() / longestEdge
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true,
            ).also { scaled ->
                if (scaled !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }
        result.prepareToDraw()
        return result
    }

    private fun normalizeAssetPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) {
                    throw InvalidTendiesException("Unsafe image path: $path")
                } else {
                    parts.removeLast()
                }
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun parseNumbers(value: String, defaults: List<Float>): List<Float> {
        if (value.isBlank()) return defaults
        val parsed = value.trim().split(Regex("\\s+")).mapNotNull(String::toFloatOrNull)
        return if (parsed.size >= defaults.size) parsed.take(defaults.size) else defaults
    }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray = input.use { source ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw InvalidTendiesException("CAML scene is too large")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun parseColor(value: String): Int? {
        if (value.isBlank()) return null
        val values = value.trim().split(Regex("\\s+")).mapNotNull(String::toFloatOrNull)
        if (values.size < 3) return null
        return Color.argb(
            ((values.getOrElse(3) { 1f }).coerceIn(0f, 1f) * 255).toInt(),
            (values[0].coerceIn(0f, 1f) * 255).toInt(),
            (values[1].coerceIn(0f, 1f) * 255).toInt(),
            (values[2].coerceIn(0f, 1f) * 255).toInt(),
        )
    }

    private fun sceneOrder(entry: ZipEntry): Int = when {
        "_background-" in entry.name.lowercase(Locale.ROOT) -> 0
        "_floating-" in entry.name.lowercase(Locale.ROOT) -> 1
        "_foreground-" in entry.name.lowercase(Locale.ROOT) -> 2
        else -> 3
    }

    private fun Element.floatAttribute(name: String, default: Float): Float =
        getAttribute(name).toFloatOrNull() ?: default

    private fun Element.localTagName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.directChild(name: String): Element? =
        childElements().firstOrNull { it.localTagName() == name }

    private fun Node.childElements(): List<Element> = buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)?.let(::add)
        }
    }
}
