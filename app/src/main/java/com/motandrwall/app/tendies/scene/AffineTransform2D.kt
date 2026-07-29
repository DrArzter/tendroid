package com.motandrwall.app.tendies.scene

import kotlin.math.cos
import kotlin.math.sin

/** A small, allocation-free 2D transform with the same operation order as Canvas. */
internal data class AffineTransform2D(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun translated(x: Float, y: Float): AffineTransform2D = this * AffineTransform2D(tx = x, ty = y)

    fun scaled(x: Float, y: Float): AffineTransform2D = this * AffineTransform2D(a = x, d = y)

    fun rotated(radians: Float): AffineTransform2D {
        val cosine = cos(radians)
        val sine = sin(radians)
        return this * AffineTransform2D(a = cosine, b = sine, c = -sine, d = cosine)
    }

    fun map(x: Float, y: Float): Point2D = Point2D(
        x = a * x + c * y + tx,
        y = b * x + d * y + ty,
    )

    private operator fun times(other: AffineTransform2D): AffineTransform2D = AffineTransform2D(
        a = a * other.a + c * other.b,
        b = b * other.a + d * other.b,
        c = a * other.c + c * other.d,
        d = b * other.c + d * other.d,
        tx = a * other.tx + c * other.ty + tx,
        ty = b * other.tx + d * other.ty + ty,
    )
}

internal data class Point2D(val x: Float, val y: Float)
