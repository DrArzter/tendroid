package com.motandrwall.app.tendies.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class AffineTransform2DTest {
    @Test
    fun operationsMatchCanvasOrder() {
        val transform = AffineTransform2D()
            .translated(10f, 20f)
            .scaled(2f, 3f)
            .translated(4f, 5f)

        val point = transform.map(1f, 2f)

        assertEquals(20f, point.x, 0.0001f)
        assertEquals(41f, point.y, 0.0001f)
    }

    @Test
    fun rotationUsesAndroidsClockwiseScreenCoordinates() {
        val point = AffineTransform2D()
            .rotated((Math.PI / 2.0).toFloat())
            .map(10f, 0f)

        assertEquals(0f, point.x, 0.0001f)
        assertEquals(10f, point.y, 0.0001f)
    }
}
