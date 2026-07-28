package com.motandrwall.app.tendies.scene

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

data class TendiesScene(
    val documents: List<SceneDocument>,
    val bitmaps: Map<String, Bitmap>,
) : AutoCloseable {
    fun pose(stateName: String): ScenePose = ScenePose(
        documents.map { document ->
            buildMap {
                fun visit(layer: SceneLayer) {
                    val overrides = document.states[stateName]?.get(layer.id)
                    put(
                        layer.id,
                        LayerPose(
                            positionX = overrides?.positionX ?: layer.positionX,
                            positionY = overrides?.positionY ?: layer.positionY,
                            rotationRadians = overrides?.rotationRadians ?: layer.rotationRadians,
                            opacity = overrides?.opacity ?: layer.opacity,
                        ),
                    )
                    layer.children.forEach(::visit)
                }
                visit(document.root)
            }
        },
    )

    fun interpolatePose(from: ScenePose, to: ScenePose, progress: Float): ScenePose {
        return interpolatePose(from, to) { progress.coerceIn(0f, 1f) }
    }

    fun interpolatePose(
        from: ScenePose,
        to: ScenePose,
        transition: SceneTransition?,
        linearProgress: Float,
    ): ScenePose = interpolatePose(from, to) { keyPath ->
        transition?.transform(linearProgress, keyPath)
            ?: TransitionCurve.SmoothStep.transform(linearProgress, 1f)
    }

    private fun interpolatePose(
        from: ScenePose,
        to: ScenePose,
        progress: (String) -> Float,
    ): ScenePose {
        return ScenePose(documents.indices.map { documentIndex ->
            val fromLayers = from.documents.getOrElse(documentIndex) { emptyMap() }
            val toLayers = to.documents.getOrElse(documentIndex) { emptyMap() }
            buildMap {
                (fromLayers.keys + toLayers.keys).forEach { id ->
                    val start = fromLayers[id] ?: toLayers.getValue(id)
                    val end = toLayers[id] ?: start
                    put(
                        id,
                        LayerPose(
                            positionX = lerp(start.positionX, end.positionX, progress("position.x")),
                            positionY = lerp(start.positionY, end.positionY, progress("position.y")),
                            rotationRadians = lerp(
                                start.rotationRadians,
                                end.rotationRadians,
                                progress("transform.rotation.z"),
                            ),
                            opacity = lerp(start.opacity, end.opacity, progress("opacity")),
                        ),
                    )
                }
            }
        })
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    fun transitionDurationMillis(fromState: String, toState: String): Float {
        return transition(fromState, toState)?.durationMillis ?: DEFAULT_TRANSITION_MILLIS
    }

    fun transition(fromState: String, toState: String): SceneTransition? {
        val matching = documents.flatMap(SceneDocument::transitions)
            .filter { transition ->
                (transition.fromState == "*" || transition.fromState == fromState) &&
                    (transition.toState == "*" || transition.toState == toState)
            }
        val mostSpecific = matching.maxOfOrNull { transition ->
            (if (transition.fromState == fromState) 1 else 0) +
                (if (transition.toState == toState) 1 else 0)
        } ?: return null
        return matching
            .filter { transition ->
                (if (transition.fromState == fromState) 1 else 0) +
                    (if (transition.toState == toState) 1 else 0) == mostSpecific
            }
            .maxByOrNull(SceneTransition::durationMillis)
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

data class ScenePose(val documents: List<Map<String, LayerPose>>)

data class LayerPose(
    val positionX: Float,
    val positionY: Float,
    val rotationRadians: Float,
    val opacity: Float,
) {
    fun interpolate(to: LayerPose, progress: Float): LayerPose = LayerPose(
        positionX = lerp(positionX, to.positionX, progress),
        positionY = lerp(positionY, to.positionY, progress),
        rotationRadians = lerp(rotationRadians, to.rotationRadians, progress),
        opacity = lerp(opacity, to.opacity, progress),
    )

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress
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
    val curve: TransitionCurve = TransitionCurve.SmoothStep,
    val animations: List<SceneAnimation> = emptyList(),
) {
    fun transform(progress: Float): Float = curve.transform(progress, durationMillis)

    fun transform(progress: Float, keyPath: String): Float {
        val animation = animations
            .filter { it.keyPath.isBlank() || it.keyPath == keyPath }
            .maxByOrNull { it.beginMillis + it.durationMillis }
            ?: return transform(progress)
        val elapsed = progress.coerceIn(0f, 1f) * durationMillis
        val localProgress = ((elapsed - animation.beginMillis) / animation.durationMillis.coerceAtLeast(1f))
            .coerceIn(0f, 1f)
        return animation.curve.transform(localProgress, animation.durationMillis)
    }
}

data class SceneAnimation(
    val keyPath: String,
    val beginMillis: Float,
    val durationMillis: Float,
    val curve: TransitionCurve,
)

sealed interface TransitionCurve {
    fun transform(progress: Float, durationMillis: Float): Float

    data object SmoothStep : TransitionCurve {
        override fun transform(progress: Float, durationMillis: Float): Float {
            val value = progress.coerceIn(0f, 1f)
            return value * value * (3f - 2f * value)
        }
    }

    data class Spring(
        val damping: Float,
        val mass: Float,
        val stiffness: Float,
        val initialVelocity: Float,
    ) : TransitionCurve {
        override fun transform(progress: Float, durationMillis: Float): Float {
            val fraction = progress.coerceIn(0f, 1f)
            if (fraction >= 1f) return 1f
            val safeMass = mass.coerceAtLeast(0.001f).toDouble()
            val safeStiffness = stiffness.coerceAtLeast(0.001f).toDouble()
            val dampingValue = damping.coerceAtLeast(0f).toDouble()
            val velocity = initialVelocity.toDouble()
            val time = durationMillis.coerceAtLeast(1f) / 1_000.0 * fraction
            val omega = sqrt(safeStiffness / safeMass)
            val ratio = dampingValue / (2.0 * sqrt(safeStiffness * safeMass))
            val displacement = when {
                ratio < 1.0 - 1e-4 -> {
                    val damped = omega * sqrt(1.0 - ratio * ratio)
                    val coefficient = (velocity - ratio * omega) / damped
                    exp(-ratio * omega * time) *
                        (-cos(damped * time) + coefficient * sin(damped * time))
                }
                abs(ratio - 1.0) <= 1e-4 -> {
                    val coefficient = velocity - omega
                    (-1.0 + coefficient * time) * exp(-omega * time)
                }
                else -> {
                    val root = omega * sqrt(ratio * ratio - 1.0)
                    val firstRoot = -ratio * omega + root
                    val secondRoot = -ratio * omega - root
                    val first = (velocity + secondRoot) / (firstRoot - secondRoot)
                    val second = -1.0 - first
                    first * exp(firstRoot * time) + second * exp(secondRoot * time)
                }
            }
            return (1.0 + displacement).toFloat().coerceIn(0f, 1f)
        }
    }
}

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
