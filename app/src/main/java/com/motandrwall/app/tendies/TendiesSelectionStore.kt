package com.motandrwall.app.tendies

import android.content.Context
import android.content.SharedPreferences
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

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences().registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences().unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    companion object {
        private const val PREFERENCES = "tendies_selection"
        internal const val KEY_FILE_NAME = "selected_file_name"
    }
}
