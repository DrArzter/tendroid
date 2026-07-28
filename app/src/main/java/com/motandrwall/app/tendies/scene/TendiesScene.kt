package com.motandrwall.app.tendies.scene

import android.graphics.Bitmap

data class TendiesScene(
    val documents: List<SceneDocument>,
    val bitmaps: Map<String, Bitmap>,
) : AutoCloseable {
    fun transitionDurationMillis(fromState: String, toState: String): Float {
        val matching = documents.flatMap(SceneDocument::transitions)
            .filter { transition ->
                (transition.fromState == "*" || transition.fromState == fromState) &&
                    (transition.toState == "*" || transition.toState == toState)
            }
        val mostSpecific = matching.maxOfOrNull { transition ->
            (if (transition.fromState == fromState) 1 else 0) +
                (if (transition.toState == toState) 1 else 0)
        } ?: return DEFAULT_TRANSITION_MILLIS
        return matching
            .filter { transition ->
                (if (transition.fromState == fromState) 1 else 0) +
                    (if (transition.toState == toState) 1 else 0) == mostSpecific
            }
            .maxOfOrNull(SceneTransition::durationMillis)
            ?: DEFAULT_TRANSITION_MILLIS
    }

    override fun close() {
        bitmaps.values.toSet().forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private companion object {
        const val DEFAULT_TRANSITION_MILLIS = 800f
    }
}

data class SceneDocument(
    val root: SceneLayer,
    val states: Map<String, Map<String, LayerOverrides>>,
    val transitions: List<SceneTransition>,
)

data class SceneTransition(
    val fromState: String,
    val toState: String,
    val durationMillis: Float,
)

data class SceneLayer(
    val id: String,
    val name: String?,
    val boundsX: Float,
    val boundsY: Float,
    val width: Float,
    val height: Float,
    val positionX: Float,
    val positionY: Float,
    val anchorX: Float,
    val anchorY: Float,
    val rotationRadians: Float,
    val opacity: Float,
    val zPosition: Float,
    val geometryFlipped: Boolean,
    val masksToBounds: Boolean,
    val backgroundColor: Int?,
    val imagePath: String?,
    val text: String?,
    val fontName: String?,
    val fontSize: Float,
    val foregroundColor: Int,
    val children: List<SceneLayer>,
)

data class LayerOverrides(
    var positionX: Float? = null,
    var positionY: Float? = null,
    var rotationRadians: Float? = null,
    var opacity: Float? = null,
)
