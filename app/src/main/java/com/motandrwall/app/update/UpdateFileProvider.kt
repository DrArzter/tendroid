package com.motandrwall.app.update

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class UpdateFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = APK_MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "Updates are read-only" }
        val file = updateFile(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = updateFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns, 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> UPDATE_FILE_NAME
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun updateFile(uri: Uri): File {
        require(uri.authority == AUTHORITY && uri.lastPathSegment == UPDATE_FILE_NAME) {
            "Unknown update URI"
        }
        return File(requireNotNull(context).cacheDir, "updates/$UPDATE_FILE_NAME")
            .takeIf(File::isFile)
            ?: throw java.io.FileNotFoundException("Update APK is unavailable")
    }

    companion object {
        const val AUTHORITY = "com.motandrwall.app.updates"
        const val UPDATE_FILE_NAME = "tendroid-update.apk"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
