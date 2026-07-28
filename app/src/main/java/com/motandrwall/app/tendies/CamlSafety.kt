package com.motandrwall.app.tendies

import java.nio.charset.StandardCharsets
import java.util.Locale

internal object CamlSafety {
    const val MAX_XML_DEPTH = 256
    const val MAX_LAYERS = 10_000

    fun rejectForbiddenMarkup(xml: ByteArray) {
        val decoded = listOf(
            String(xml, StandardCharsets.UTF_8),
            String(xml, StandardCharsets.UTF_16LE),
            String(xml, StandardCharsets.UTF_16BE),
        )
        if (decoded.any { text ->
                val upper = text.uppercase(Locale.ROOT)
                "<!DOCTYPE" in upper || "<!ENTITY" in upper
            }
        ) {
            throw InvalidTendiesException("CAML with DTD or entities is not allowed")
        }
    }
}
