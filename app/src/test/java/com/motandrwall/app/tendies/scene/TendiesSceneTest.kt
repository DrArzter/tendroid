package com.motandrwall.app.tendies.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TendiesSceneTest {
    @Test
    fun usesMostSpecificCamlTransitionDuration() {
        val document = SceneDocument(
            root = emptyLayer(),
            states = emptyMap(),
            transitions = listOf(
                SceneTransition("*", "Sleep", 800f),
                SceneTransition("Unlock", "Sleep", 650f),
                SceneTransition("Locked", "*", 900f),
            ),
        )
        val scene = TendiesScene(listOf(document), emptyMap())

        assertEquals(650f, scene.transitionDurationMillis("Unlock", "Sleep"))
        assertEquals(900f, scene.transitionDurationMillis("Locked", "Unlock"))
        assertEquals(800f, scene.transitionDurationMillis("Unlock", "Locked"))
    }

    @Test
    fun capturesAndInterpolatesResolvedLayerPose() {
        val document = SceneDocument(
            root = emptyLayer(),
            states = mapOf(
                "Locked" to mapOf("root" to LayerOverrides(positionY = 100f, opacity = 0.2f)),
                "Unlock" to mapOf("root" to LayerOverrides(positionY = 200f, opacity = 1f)),
            ),
            transitions = emptyList(),
        )
        val scene = TendiesScene(listOf(document), emptyMap())

        val halfway = scene.interpolatePose(scene.pose("Locked"), scene.pose("Unlock"), 0.5f)
            .documents.single().getValue("root")

        assertEquals(150f, halfway.positionY)
        assertEquals(0.6f, halfway.opacity)
    }

    @Test
    fun springCurveStartsAndFinishesAtTransitionEndpoints() {
        val curve = TransitionCurve.Spring(damping = 50f, mass = 2f, stiffness = 300f, initialVelocity = 0f)

        assertEquals(0f, curve.transform(0f, 800f))
        assertEquals(1f, curve.transform(1f, 800f))
        assertTrue(curve.transform(0.5f, 800f) in 0f..1f)
    }

    @Test
    fun appliesAnimationDelayPerKeyPath() {
        val transition = SceneTransition(
            fromState = "Locked",
            toState = "Unlock",
            durationMillis = 800f,
            animations = listOf(
                SceneAnimation("opacity", beginMillis = 400f, durationMillis = 400f, TransitionCurve.SmoothStep),
            ),
        )

        assertEquals(0f, transition.transform(0.25f, "opacity"))
        assertEquals(0.5f, transition.transform(0.75f, "opacity"))
        assertEquals(1f, transition.transform(1f, "opacity"))
    }

    private fun emptyLayer() = SceneLayer(
        id = "root",
        name = null,
        boundsX = 0f,
        boundsY = 0f,
        width = 1f,
        height = 1f,
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
        children = emptyList(),
    )
}
