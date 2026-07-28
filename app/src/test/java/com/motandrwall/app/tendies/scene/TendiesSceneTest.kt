package com.motandrwall.app.tendies.scene

import org.junit.Assert.assertEquals
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
