package com.motandrwall.app.tendies

import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

internal data class CamlSummary(
    var layers: Int = 0,
    var imageLayers: Int = 0,
    var textLayers: Int = 0,
    val states: MutableSet<String> = linkedSetOf(),
    val animations: MutableSet<String> = linkedSetOf(),
)

internal object CamlSceneAnalyzer {
    fun analyze(xml: ByteArray): CamlSummary {
        val prefix = xml.decodeToString(0, minOf(xml.size, 1024)).uppercase()
        if ("<!DOCTYPE" in prefix || "<!ENTITY" in prefix) {
            throw InvalidTendiesException("CAML with DTD or entities is not allowed")
        }

        val result = CamlSummary()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

        val handler = object : DefaultHandler() {
            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                val name = localName?.ifBlank { qName } ?: qName.orEmpty()
                when (name) {
                    "CALayer" -> result.layers++
                    "CGImage" -> result.imageLayers++
                    "CATextLayer" -> {
                        result.layers++
                        result.textLayers++
                    }
                    "LKState" -> attributes.getValue("name")?.let(result.states::add)
                    "animation" -> attributes.getValue("type")?.let(result.animations::add)
                }
            }
        }

        try {
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = object : EntityResolver {
                override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
                    InputSource(ByteArrayInputStream(ByteArray(0)))
            }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(xml)))
        } catch (error: InvalidTendiesException) {
            throw error
        } catch (error: Exception) {
            throw InvalidTendiesException("Invalid CAML document", error)
        }
        return result
    }
}
