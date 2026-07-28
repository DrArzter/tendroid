package com.motandrwall.app.tendies

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

object TendiesPackageAnalyzer {
    private const val MAX_ENTRIES = 2_048
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    private const val MAX_CAML_BYTES = 4L * 1024 * 1024
    private const val MAX_SCRIPT_INSPECTION_BYTES = 2L * 1024 * 1024

    fun analyze(input: InputStream): TendiesReport {
        var entries = 0
        var totalBytes = 0L
        var images = 0
        var videos = 0
        var camlDocuments = 0
        var scripts = 0
        var behaviorScripts = 0
        var layers = 0
        var imageLayers = 0
        var textLayers = 0
        val states = linkedSetOf<String>()
        val animations = linkedSetOf<String>()
        val warnings = linkedSetOf<String>()
        val entryNames = hashSetOf<String>()

        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > MAX_ENTRIES) {
                        throw InvalidTendiesException("Package contains too many entries")
                    }
                    validatePath(entry.name)
                    if (!entryNames.add(entry.name.lowercase(Locale.ROOT))) {
                        throw InvalidTendiesException("Package contains a duplicate ZIP entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val lowerName = entry.name.lowercase(Locale.ROOT)
                    val extension = lowerName.substringAfterLast('.', missingDelimiterValue = "")
                    val captureCaml = lowerName.endsWith(".caml") &&
                        lowerName.substringAfterLast('/').equals("main.caml", ignoreCase = true)
                    val captureScript = extension == "js"
                    val captured = if (captureCaml || captureScript) ByteArrayOutputStream() else null
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L

                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryBytes += count
                        totalBytes += count
                        if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                            throw InvalidTendiesException("Package exceeds safe size limits")
                        }
                        if (captured != null) {
                            val captureLimit = if (captureCaml) MAX_CAML_BYTES else MAX_SCRIPT_INSPECTION_BYTES
                            if (entryBytes > captureLimit) {
                                throw InvalidTendiesException(
                                    if (captureCaml) "CAML document is too large" else "JavaScript asset is too large",
                                )
                            }
                            captured.write(buffer, 0, count)
                        }
                    }

                    when (extension) {
                        "png", "jpg", "jpeg", "webp", "heic", "heif" -> images++
                        "mp4", "mov", "m4v", "webm" -> videos++
                        "js" -> scripts++
                    }

                    if (captureScript && captured != null && containsBehaviorCode(captured.toByteArray())) {
                        behaviorScripts++
                    }
                    if (captureCaml && captured != null) {
                        camlDocuments++
                        val summary = CamlSceneAnalyzer.analyze(captured.toByteArray())
                        layers += summary.layers
                        imageLayers += summary.imageLayers
                        textLayers += summary.textLayers
                        states += summary.states
                        animations += summary.animations
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: InvalidTendiesException) {
            throw error
        } catch (error: ZipException) {
            throw InvalidTendiesException("The selected file is not a valid Tendies ZIP", error)
        } catch (error: Exception) {
            throw InvalidTendiesException("Could not inspect the Tendies package", error)
        }

        if (entries == 0 || camlDocuments == 0) {
            throw InvalidTendiesException("No Tendies CAML scene was found")
        }
        if (behaviorScripts > 0) warnings += "This package contains scripted behavior that is not supported yet"
        if (videos > 0) warnings += "Video layers are not implemented yet"
        if (imageLayers == 0) warnings += "No image layers were found in CAML"

        return TendiesReport(
            entries = entries,
            uncompressedBytes = totalBytes,
            imageAssets = images,
            videoAssets = videos,
            camlDocuments = camlDocuments,
            javascriptAssets = scripts,
            layers = layers,
            imageLayers = imageLayers,
            textLayers = textLayers,
            states = states,
            animations = animations,
            warnings = warnings.toList(),
        )
    }

    private fun containsBehaviorCode(bytes: ByteArray): Boolean {
        val source = bytes.toString(Charsets.UTF_8)
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .replace(Regex("(?m)//.*$"), "")
            .replace(Regex("(?m)^\\s*(['\"])use strict\\1\\s*;?\\s*$"), "")
        return source.isNotBlank()
    }

    private fun validatePath(path: String) {
        if (
            path.isBlank() ||
            path.startsWith('/') ||
            path.startsWith('\\') ||
            '\\' in path ||
            path.split('/').any { it == ".." }
        ) {
            throw InvalidTendiesException("Unsafe ZIP entry path: $path")
        }
    }
}
