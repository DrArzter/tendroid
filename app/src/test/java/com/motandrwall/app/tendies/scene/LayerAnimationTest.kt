package com.motandrwall.app.tendies.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerAnimationTest {
    @Test
    fun repeatsPositionKeyframesForever() {
        val animation = fallingSnow()

        assertTrue(animation.isAnimated)
        assertEquals(Float.POSITIVE_INFINITY, animation.endMillis)
        assertEquals(0f, animation.applyTo(origin, 0f).positionY)
        assertEquals(50f, animation.applyTo(origin, 1_000f).positionY)
        // Second cycle repeats the first rather than holding the end value.
        assertEquals(50f, animation.applyTo(origin, 3_000f).positionY)
        assertEquals(25f, animation.applyTo(origin, 2_500f).positionY)
    }

    @Test
    fun holdsFinalValueAfterTheLastRepeat() {
        val animation = fallingSnow().copy(repeatCount = 2f)

        assertEquals(4_000f, animation.endMillis)
        assertEquals(50f, animation.applyTo(origin, 3_000f).positionY)
        // `removedOnCompletion="0"` keeps the end pose instead of snapping back.
        assertEquals(100f, animation.applyTo(origin, 9_999f).positionY)
    }

    @Test
    fun mirrorsEveryOtherCycleWhenAutoreversing() {
        val animation = fallingSnow().copy(autoreverses = true)

        assertEquals(50f, animation.applyTo(origin, 1_000f).positionY)
        // The second cycle plays the same keyframes backwards.
        assertEquals(50f, animation.applyTo(origin, 3_000f).positionY)
        assertEquals(100f, animation.applyTo(origin, 2_000f).positionY)
        assertEquals(0f, animation.applyTo(origin, 4_000f).positionY)
    }

    @Test
    fun honoursKeyTimesAndDiscreteCalculationMode() {
        val animation = LayerAnimation(
            keyPath = "opacity",
            values = listOf(AnimationValue(0f), AnimationValue(1f), AnimationValue(0.5f)),
            keyTimes = listOf(0f, 0.25f, 1f),
            durationMillis = 1_000f,
            repeatCount = Float.POSITIVE_INFINITY,
        )

        assertEquals(0.5f, animation.applyTo(origin, 125f).opacity)
        assertEquals(1f, animation.applyTo(origin, 250f).opacity)
        assertEquals(0.75f, animation.applyTo(origin, 625f).opacity)

        val discrete = animation.copy(discrete = true)
        assertEquals(0f, discrete.applyTo(origin, 125f).opacity)
        assertEquals(1f, discrete.applyTo(origin, 625f).opacity)
    }

    @Test
    fun ignoresUnsupportedAndDegenerateAnimations() {
        val unsupported = fallingSnow().copy(keyPath = "cornerRadius")
        val instant = fallingSnow().copy(durationMillis = 0f)
        val single = fallingSnow().copy(values = listOf(AnimationValue(0f, 0f)))

        listOf(unsupported, instant, single).forEach { animation ->
            assertFalse(animation.isAnimated)
            assertEquals(0f, animation.endMillis)
            assertEquals(origin, animation.applyTo(origin, 1_000f))
        }
    }

    @Test
    fun animatesSizeAndScaleKeyPaths() {
        val pose = origin.copy(width = 10f, height = 20f)
        val resize = LayerAnimation(
            keyPath = "bounds.size",
            values = listOf(AnimationValue(100f, 200f), AnimationValue(300f, 400f)),
            durationMillis = 1_000f,
            repeatCount = Float.POSITIVE_INFINITY,
        )
        val grow = resize.copy(
            keyPath = "transform.scale",
            values = listOf(AnimationValue(1f), AnimationValue(3f)),
        )

        val halfway = resize.applyTo(pose, 500f)
        assertEquals(200f, halfway.width)
        assertEquals(300f, halfway.height)

        val scaled = grow.applyTo(pose, 500f)
        // A bare `transform.scale` drives both axes.
        assertEquals(2f, scaled.scaleX)
        assertEquals(2f, scaled.scaleY)
        assertEquals(1.5f, grow.copy(keyPath = "transform.scale.y").applyTo(pose, 250f).scaleY)
        assertEquals(1f, grow.copy(keyPath = "transform.scale.y").applyTo(pose, 250f).scaleX)
    }

    @Test
    fun resolvesSizeOverridesFromStates() {
        val scene = TendiesScene(
            documents = listOf(
                SceneDocument(
                    root = snowLayer(emptyList()),
                    states = mapOf(
                        "Locked" to mapOf("snow" to LayerOverrides(width = 374f, height = 624f)),
                        "Unlock" to mapOf("snow" to LayerOverrides(width = 472f, height = 787f)),
                    ),
                    transitions = emptyList(),
                ),
            ),
            bitmaps = emptyMap(),
        )

        assertEquals(374f, scene.pose("Locked").documents.single().getValue("snow").width)
        assertEquals(787f, scene.pose("Unlock").documents.single().getValue("snow").height)

        val halfway = scene
            .interpolatePose(scene.pose("Locked"), scene.pose("Unlock"), 0.5f)
            .documents.single().getValue("snow")
        assertEquals(423f, halfway.width)
        assertEquals(705.5f, halfway.height)
    }

    @Test
    fun animatedLayersOverrideTheStatePose() {
        val layer = snowLayer(listOf(fallingSnow()))
        val scene = TendiesScene(
            documents = listOf(
                SceneDocument(
                    root = layer,
                    states = mapOf("Unlock" to mapOf("snow" to LayerOverrides(positionY = 900f))),
                    transitions = emptyList(),
                ),
            ),
            bitmaps = emptyMap(),
        )

        assertTrue(scene.hasAnimations)
        assertEquals(Float.POSITIVE_INFINITY, scene.animationEndMillis)

        val posed = scene.pose("Unlock")
        assertEquals(900f, posed.documents.single().getValue("snow").positionY)
        assertEquals(50f, scene.animate(posed, 1_000f).documents.single().getValue("snow").positionY)
    }

    @Test
    fun staticScenesNeedNoFrameLoop() {
        val scene = TendiesScene(
            documents = listOf(SceneDocument(snowLayer(emptyList()), emptyMap(), emptyList())),
            bitmaps = emptyMap(),
        )

        assertFalse(scene.hasAnimations)
        assertEquals(0f, scene.animationEndMillis)
        val posed = scene.pose("Unlock")
        assertEquals(posed, scene.animate(posed, 5_000f))
    }

    private val origin = LayerPose(positionX = 0f, positionY = 0f, rotationRadians = 0f, opacity = 1f)

    private fun fallingSnow() = LayerAnimation(
        keyPath = "position",
        values = listOf(AnimationValue(0f, 0f), AnimationValue(0f, 100f)),
        durationMillis = 2_000f,
        repeatCount = Float.POSITIVE_INFINITY,
    )

    private fun snowLayer(animations: List<LayerAnimation>) = SceneLayer(
        id = "snow",
        name = null,
        boundsX = 0f,
        boundsY = 0f,
        width = 25f,
        height = 25f,
        positionX = 0f,
        positionY = 0f,
        anchorX = 0.5f,
        anchorY = 0.5f,
        rotationRadians = 0f,
        opacity = 1f,
        zPosition = 0f,
        geometryFlipped = false,
        masksToBounds = false,
        backgroundColor = null,
        imagePath = null,
        text = null,
        fontName = null,
        fontSize = 17f,
        foregroundColor = 0,
        animations = animations,
        children = emptyList(),
    )
}
