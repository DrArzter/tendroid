package com.motandrwall.app.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

internal class WallpaperFrameCache(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)

    fun read(selected: File): Bitmap? {
        val file = fileFor(selected)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun contains(selected: File): Boolean = fileFor(selected).isFile

    fun write(selected: File, bitmap: Bitmap) {
        check(directory.exists() || directory.mkdirs()) { "Could not create wallpaper frame cache" }
        val destination = fileFor(selected)
        val temporary = File(directory, "${destination.name}.tmp")
        temporary.outputStream().buffered().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                "Could not encode wallpaper frame cache"
            }
        }
        check(temporary.renameTo(destination)) { "Could not publish wallpaper frame cache" }
    }

    private fun fileFor(selected: File): File {
        val safeName = selected.name.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return File(directory, "$safeName-sleep.jpg")
    }

    private companion object {
        const val DIRECTORY_NAME = "wallpaper_frames"
        const val JPEG_QUALITY = 95
    }
}
