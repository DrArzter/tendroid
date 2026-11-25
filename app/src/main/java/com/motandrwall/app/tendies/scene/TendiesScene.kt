package com.motandrwall.app.tendies.scene

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

data class TendiesScene(
    val documents: List<SceneDocument>,
    val bitmaps: Map<String, Bitmap>,
) : AutoCloseable {
    /**
     * Layers carrying their own CAAnimation, per document. Looping animations
     * run off wall-clock time rather than state transitions, so they are
     * resolved on every frame and the tree is flattened once up front.
     */
    private val animatedLayers: List<List<SceneLayer>> = documents.map { document ->
        buildList {
            fun visit(layer: SceneLayer) {
                if (layer.animations.any(LayerAnimation::isAnimated)) add(layer)
                layer.children.forEach(::visit)
            }
            visit(document.root)
        }
    }

    /**
     * Wall-clock millisecond after which no animation changes any more, so the
     * engine can stop drawing. Infinite when the scene repeats forever.
     */
    val animationEndMillis: Float = animatedLayers.flatten()
        .flatMap(SceneLayer::animations)
        .filter(LayerAnimation::isAnimated)
        .maxOfOrNull(LayerAnimation::endMillis)
        ?: 0f

    val hasAnimations: Boolean = animationEndMillis > 0f

    /**
     * Overlays layer animations on an already posed scene. Core Animation
     * presents animated key paths instead of the model value, so these win over
     * both the layer attribute and any state override.
     */
    fun animate(pose: ScenePose, elapsedMillis: Float): ScenePose {
        if (!hasAnimations) return pose
        return ScenePose(pose.documents.mapIndexed { documentIndex, layers ->
            val animated = animatedLayers.getOrElse(documentIndex) { emptyList() }
            if (animated.isEmpty()) return@mapIndexed layers
            layers.toMutableMap().apply {
                animated.forEach { layer ->
                    val base = this[layer.id] ?: return@forEach
                    this[layer.id] = layer.animations.fold(base) { pose, animation ->
                        animation.applyTo(pose, elapsedMillis)
                    }
                }
            }
        })
    }

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
                            width = overrides?.width ?: layer.width,
                            height = overrides?.height ?: layer.height,
                            scaleX = overrides?.scaleX ?: 1f,
                            scaleY = overrides?.scaleY ?: 1f,
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
                            width = lerp(start.width, end.width, progress("bounds.size.width")),
                            height = lerp(start.height, end.height, progress("bounds.size.height")),
                            scaleX = lerp(start.scaleX, end.scaleX, progress("transform.scale.x")),
                            scaleY = lerp(start.scaleY, end.scaleY, progress("transform.scale.y")),
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
    /** Resolved `bounds.size`; states and animations may resize a layer. */
    val width: Float = 0f,
    val height: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
) {
    fun interpolate(to: LayerPose, progress: Float): LayerPose = LayerPose(
        positionX = lerp(positionX, to.positionX, progress),
        positionY = lerp(positionY, to.positionY, progress),
        rotationRadians = lerp(rotationRadians, to.rotationRadians, progress),
        opacity = lerp(opacity, to.opacity, progress),
        width = lerp(width, to.width, progress),
        height = lerp(height, to.height, progress),
        scaleX = lerp(scaleX, to.scaleX, progress),
        scaleY = lerp(scaleY, to.scaleY, progress),
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

/** One value of a CAAnimation `values` list; [y] is unused for scalar key paths. */
data class AnimationValue(val x: Float, val y: Float = 0f)

/**
 * A CABasicAnimation or CAKeyframeAnimation attached to a layer. Unlike
 * [SceneTransition] these are driven by wall-clock time and usually repeat
 * forever, which is how exported packages express snow, drift and spin.
 */
data class LayerAnimation(
    val keyPath: String,
    val values: List<AnimationValue>,
    val keyTimes: List<Float> = emptyList(),
    val beginMillis: Float = 0f,
    val durationMillis: Float = 0f,
    val speed: Float = 1f,
    val repeatCount: Float = 1f,
    val autoreverses: Boolean = false,
    val discrete: Boolean = false,
    val eased: Boolean = false,
) {
    val isAnimated: Boolean =
        values.size > 1 && durationMillis > 0f && speed > 0f && SUPPORTED_KEY_PATHS.contains(keyPath)

    /** Cycles of [durationMillis] this animation plays, counting an autoreversed return as its own cycle. */
    private val totalCycles: Float =
        (if (repeatCount.isFinite()) repeatCount.coerceAtLeast(1f) else Float.POSITIVE_INFINITY) *
            (if (autoreverses) 2f else 1f)

    val endMillis: Float =
        if (!isAnimated) 0f
        else if (totalCycles.isInfinite()) Float.POSITIVE_INFINITY
        else beginMillis + totalCycles * durationMillis / speed

    fun applyTo(pose: LayerPose, elapsedMillis: Float): LayerPose {
        if (!isAnimated) return pose
        val value = valueAt(progressAt(elapsedMillis))
        return when (keyPath) {
            "position" -> pose.copy(positionX = value.x, positionY = value.y)
            "position.x" -> pose.copy(positionX = value.x)
            "position.y" -> pose.copy(positionY = value.x)
            "transform.rotation.z" -> pose.copy(rotationRadians = value.x)
            "opacity" -> pose.copy(opacity = value.x.coerceIn(0f, 1f))
            "bounds.size" -> pose.copy(width = value.x.coerceAtLeast(0f), height = value.y.coerceAtLeast(0f))
            "bounds.size.width" -> pose.copy(width = value.x.coerceAtLeast(0f))
            "bounds.size.height" -> pose.copy(height = value.x.coerceAtLeast(0f))
            // A bare `transform.scale` drives both axes from one scalar.
            "transform.scale" -> pose.copy(scaleX = value.x, scaleY = value.x)
            "transform.scale.x" -> pose.copy(scaleX = value.x)
            "transform.scale.y" -> pose.copy(scaleY = value.x)
            else -> pose
        }
    }

    /** Position within a single cycle, after repeat and autoreverse folding. */
    private fun progressAt(elapsedMillis: Float): Float {
        val local = (elapsedMillis - beginMillis) * speed
        if (local <= 0f) return 0f
        val cycles = local / durationMillis
        val finished = cycles >= totalCycles
        // `removedOnCompletion="0"` keeps the final frame on screen, so a
        // finished animation holds the end of its last cycle rather than
        // snapping back to the model value.
        val cycleIndex = if (finished) totalCycles - 1f else floor(cycles)
        val fraction = if (finished) 1f else cycles - cycleIndex
        return if (autoreverses && cycleIndex.toLong() % 2L == 1L) 1f - fraction else fraction
    }

    private fun valueAt(progress: Float): AnimationValue {
        val times = keyTimes.takeIf { it.size == values.size }
            ?: List(values.size) { it.toFloat() / (values.size - 1) }
        val index = times.indexOfLast { it <= progress }.coerceIn(0, values.size - 2)
        if (discrete) return values[index]
        val span = times[index + 1] - times[index]
        val local = if (span <= 0f) 1f else ((progress - times[index]) / span).coerceIn(0f, 1f)
        val eased = if (eased) local * local * (3f - 2f * local) else local
        val start = values[index]
        val end = values[index + 1]
        return AnimationValue(
            x = start.x + (end.x - start.x) * eased,
            y = start.y + (end.y - start.y) * eased,
        )
    }

    private companion object {
        val SUPPORTED_KEY_PATHS = setOf(
            "position",
            "position.x",
            "position.y",
            "transform.rotation.z",
            "opacity",
            "bounds.size",
            "bounds.size.width",
            "bounds.size.height",
            "transform.scale",
            "transform.scale.x",
            "transform.scale.y",
        )
    }
}

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
    val animations: List<LayerAnimation> = emptyList(),
    val children: List<SceneLayer>,
)

data class LayerOverrides(
    var positionX: Float? = null,
    var positionY: Float? = null,
    var rotationRadians: Float? = null,
    var opacity: Float? = null,
    var width: Float? = null,
    var height: Float? = null,
    var scaleX: Float? = null,
    var scaleY: Float? = null,
)
