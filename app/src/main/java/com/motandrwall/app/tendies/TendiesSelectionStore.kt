package com.motandrwall.app.tendies

import android.content.Context
import java.io.File

class TendiesSelectionStore(private val context: Context) {
    private val importsDirectory: File
        get() = File(context.filesDir, "imports")

    fun select(file: File) {
        require(file.parentFile?.canonicalFile == importsDirectory.canonicalFile) {
            "Selected package is outside app-private imports"
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FILE_NAME, file.name)
            .apply()
    }

    fun selectedFile(): File? {
        val name = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_FILE_NAME, null)
            ?: return null
        if ('/' in name || '\\' in name) return null
        return File(importsDirectory, name).takeIf(File::isFile)
    }

    private companion object {
        const val PREFERENCES = "tendies_selection"
        const val KEY_FILE_NAME = "selected_file_name"
    }
}

