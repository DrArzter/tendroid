package com.motandrwall.app.tendies

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

class TendiesImporter(private val importsDirectory: File) {
    companion object {
        private const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
    }

    fun import(input: InputStream): ImportedTendies {
        importsDirectory.mkdirs()
        val partial = File.createTempFile("import-", ".partial", importsDirectory)
        val digest = MessageDigest.getInstance("SHA-256")

        try {
            input.use { source ->
                partial.outputStream().buffered().use { destination ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_PACKAGE_BYTES) {
                            throw InvalidTendiesException("Package is larger than 512 MB")
                        }
                        digest.update(buffer, 0, count)
                        destination.write(buffer, 0, count)
                    }
                }
            }

            val report = FileInputStream(partial).use(TendiesPackageAnalyzer::analyze)
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            val stored = File(importsDirectory, "$sha256.tendies")
            if (stored.exists()) {
                partial.delete()
            } else if (!partial.renameTo(stored)) {
                throw InvalidTendiesException("Could not store the imported package")
            }
            return ImportedTendies(stored, sha256, report)
        } catch (error: Exception) {
            partial.delete()
            if (error is InvalidTendiesException) throw error
            throw InvalidTendiesException("Could not import the selected file", error)
        }
    }
}

