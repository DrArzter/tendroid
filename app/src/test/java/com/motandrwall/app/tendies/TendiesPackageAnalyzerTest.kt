package com.motandrwall.app.tendies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TendiesPackageAnalyzerTest {
    @Test
    fun analyzesConfiguredRealPackage() {
        val samplePath = System.getenv("TENDIES_SAMPLE") ?: return
        val report = File(samplePath).inputStream().use(TendiesPackageAnalyzer::analyze)

        assertTrue(report.isRenderable)
        assertTrue(report.imageAssets > 0)
        assertTrue(report.camlDocuments > 0)
        println("Real Tendies report: $report")
    }

    @Test
    fun recognizesLayeredCamlPackage() {
        val caml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <caml xmlns="http://www.apple.com/CoreAnimation/1.0">
              <CALayer id="root">
                <sublayers>
                  <CALayer id="hero"><contents><CGImage src="assets/hero.png"/></contents></CALayer>
                  <CATextLayer id="title" />
                </sublayers>
                <states><LKState name="Locked"/><LKState name="Unlock"/></states>
                <stateTransitions><animation type="CASpringAnimation" duration="0.8"/></stateTransitions>
              </CALayer>
            </caml>
        """.trimIndent().toByteArray()

        val report = TendiesPackageAnalyzer.analyze(
            ByteArrayInputStream(zipOf(
                "descriptors/demo/scene/main.caml" to caml,
                "descriptors/demo/scene/assets/hero.png" to byteArrayOf(1, 2, 3),
                "descriptors/demo/scene/assets/Root_Layer.js" to "// ignored".toByteArray(),
            )),
        )

        assertTrue(report.isRenderable)
        assertEquals(3, report.layers)
        assertEquals(1, report.imageLayers)
        assertEquals(1, report.textLayers)
        assertEquals(setOf("Locked", "Unlock"), report.states)
        assertEquals(setOf("CASpringAnimation"), report.animations)
        assertEquals(1, report.javascriptAssets)
    }

    @Test(expected = InvalidTendiesException::class)
    fun rejectsTraversalPaths() {
        TendiesPackageAnalyzer.analyze(
            ByteArrayInputStream(zipOf("../escape/main.caml" to "<caml/>".toByteArray())),
        )
    }

    @Test(expected = InvalidTendiesException::class)
    fun rejectsDuplicateEntries() {
        TendiesPackageAnalyzer.analyze(
            ByteArrayInputStream(
                zipOf(
                    "scene/main.caml" to "<caml/>".toByteArray(),
                    "scene/MAIN.CAML" to "<caml/>".toByteArray(),
                ),
            ),
        )
    }

    @Test(expected = InvalidTendiesException::class)
    fun rejectsDoctypeAfterLongPrefix() {
        val caml = (" ".repeat(2_048) + "<!DOCTYPE caml [<!ENTITY x 'boom'>]><caml>&x;</caml>")
            .toByteArray()
        TendiesPackageAnalyzer.analyze(
            ByteArrayInputStream(zipOf("scene/main.caml" to caml)),
        )
    }

    @Test(expected = InvalidTendiesException::class)
    fun rejectsExcessiveXmlDepth() {
        val depth = CamlSafety.MAX_XML_DEPTH + 1
        val caml = ("<caml>" + "<node>".repeat(depth) + "</node>".repeat(depth) + "</caml>")
            .toByteArray()
        TendiesPackageAnalyzer.analyze(
            ByteArrayInputStream(zipOf("scene/main.caml" to caml)),
        )
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
