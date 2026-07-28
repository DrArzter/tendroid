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
        CamlSafety.rejectForbiddenMarkup(xml)

        val result = CamlSummary()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Android XML implementations do not all recognize the same feature set.
            // CamlSafety rejects DTD/entity declarations across the complete input;
            // these flags are an additional defense where the parser supports them.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        }

        val handler = object : DefaultHandler() {
            private var depth = 0

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String?,
                attributes: Attributes,
            ) {
                depth++
                if (depth > CamlSafety.MAX_XML_DEPTH) {
                    throw InvalidTendiesException("CAML nesting is too deep")
                }
                val name = localName?.ifBlank { qName } ?: qName.orEmpty()
                when (name) {
                    "CALayer" -> incrementLayers(result)
                    "CGImage" -> result.imageLayers++
                    "CATextLayer" -> {
                        incrementLayers(result)
                        result.textLayers++
                    }
                    "LKState" -> attributes.getValue("name")?.let(result.states::add)
                    "animation" -> attributes.getValue("type")?.let(result.animations::add)
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                depth--
            }

            private fun incrementLayers(summary: CamlSummary) {
                summary.layers++
                if (summary.layers > CamlSafety.MAX_LAYERS) {
                    throw InvalidTendiesException("CAML contains too many layers")
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
