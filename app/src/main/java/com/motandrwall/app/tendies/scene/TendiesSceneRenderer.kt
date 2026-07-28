package com.motandrwall.app.tendies.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

class TendiesSceneRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(canvas: Canvas, scene: TendiesScene, stateName: String = "Unlock") {
        render(canvas, scene, stateName, stateName, 1f)
    }

    fun render(
        canvas: Canvas,
        scene: TendiesScene,
        fromState: String,
        toState: String,
        progress: Float,
    ) {
        canvas.drawColor(Color.BLACK)
        scene.documents.forEach { document ->
            renderDocument(canvas, scene, document, fromState, toState, progress.coerceIn(0f, 1f))
        }
    }

    private fun renderDocument(
        canvas: Canvas,
        scene: TendiesScene,
        document: SceneDocument,
        fromState: String,
        toState: String,
        progress: Float,
    ) {
        val root = document.root
        if (root.width <= 0f || root.height <= 0f) return
        val scale = max(canvas.width / root.width, canvas.height / root.height)
        val left = (canvas.width - root.width * scale) / 2f
        val top = (canvas.height - root.height * scale) / 2f
        val save = canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        renderLayer(
            canvas = canvas,
            scene = scene,
            document = document,
            layer = root,
            fromState = fromState,
            toState = toState,
            progress = progress,
            inheritedAlpha = 1f,
            parentHeight = root.height,
            parentCoordinatesYUp = false,
        )
        canvas.restoreToCount(save)
    }

    private fun renderLayer(
        canvas: Canvas,
        scene: TendiesScene,
        document: SceneDocument,
        layer: SceneLayer,
        fromState: String,
        toState: String,
        progress: Float,
        inheritedAlpha: Float,
        parentHeight: Float,
        parentCoordinatesYUp: Boolean,
    ) {
        val from = document.states[fromState]?.get(layer.id)
        val to = document.states[toState]?.get(layer.id)
        val positionX = lerp(from?.positionX ?: layer.positionX, to?.positionX ?: layer.positionX, progress)
        val rawPositionY = lerp(from?.positionY ?: layer.positionY, to?.positionY ?: layer.positionY, progress)
        val positionY = if (parentCoordinatesYUp) parentHeight - rawPositionY else rawPositionY
        val rawRotation = lerp(
            from?.rotationRadians ?: layer.rotationRadians,
            to?.rotationRadians ?: layer.rotationRadians,
            progress,
        )
        val rotation = if (parentCoordinatesYUp) -rawRotation else rawRotation
        val opacity = lerp(from?.opacity ?: layer.opacity, to?.opacity ?: layer.opacity, progress)
        val alpha = (inheritedAlpha * opacity).coerceIn(0f, 1f)
        if (alpha <= 0f) return

        val save = canvas.save()
        canvas.translate(positionX, positionY)
        canvas.rotate(Math.toDegrees(rotation.toDouble()).toFloat())
        canvas.translate(
            -layer.anchorX * layer.width - layer.boundsX,
            -layer.anchorY * layer.height - layer.boundsY,
        )
        if (layer.masksToBounds) {
            canvas.clipRect(layer.boundsX, layer.boundsY, layer.boundsX + layer.width, layer.boundsY + layer.height)
        }

        paint.alpha = (alpha * 255).toInt()
        layer.backgroundColor?.let { color ->
            paint.color = color
            canvas.drawRect(
                layer.boundsX,
                layer.boundsY,
                layer.boundsX + layer.width,
                layer.boundsY + layer.height,
                paint,
            )
        }
        layer.imagePath?.let(scene.bitmaps::get)?.let { bitmap ->
            canvas.drawBitmap(
                bitmap,
                null,
                RectF(
                    layer.boundsX,
                    layer.boundsY,
                    layer.boundsX + layer.width,
                    layer.boundsY + layer.height,
                ),
                paint,
            )
        }
        layer.text?.let { text ->
            paint.color = layer.foregroundColor
            paint.textSize = layer.fontSize
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create(layer.fontName, Typeface.NORMAL)
            val metrics = paint.fontMetrics
            val baseline = layer.boundsY + layer.height / 2f - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(text, layer.boundsX + layer.width / 2f, baseline, paint)
        }

        layer.children.forEach { child ->
            renderLayer(
                canvas = canvas,
                scene = scene,
                document = document,
                layer = child,
                fromState = fromState,
                toState = toState,
                progress = progress,
                inheritedAlpha = alpha,
                parentHeight = layer.height,
                parentCoordinatesYUp = !layer.geometryFlipped,
            )
        }
        canvas.restoreToCount(save)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress
}
