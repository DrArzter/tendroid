package com.motandrwall.app.tendies

import java.io.File

data class TendiesReport(
    val entries: Int,
    val uncompressedBytes: Long,
    val imageAssets: Int,
    val videoAssets: Int,
    val camlDocuments: Int,
    val javascriptAssets: Int,
    val layers: Int,
    val imageLayers: Int,
    val textLayers: Int,
    val states: Set<String>,
    val animations: Set<String>,
    val warnings: List<String>,
) {
    val isRenderable: Boolean
        get() = camlDocuments > 0 && imageAssets > 0 && imageLayers > 0
}

data class ImportedTendies(
    val file: File,
    val sha256: String,
    val report: TendiesReport,
)

class InvalidTendiesException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

