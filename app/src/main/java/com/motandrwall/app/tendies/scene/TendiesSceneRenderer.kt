package com.motandrwall.app.tendies.scene

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

class TendiesSceneRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bitmapDestination = RectF()
    private val typefaces = ConcurrentHashMap<String, Typeface>()

    fun render(canvas: Canvas, scene: TendiesScene, stateName: String = "Unlock") {
        val pose = scene.pose(stateName)
        render(canvas, scene, pose, pose, 1f)
    }

    fun render(
        canvas: Canvas,
        scene: TendiesScene,
        fromState: String,
        toState: String,
        progress: Float,
    ) {
        render(canvas, scene, scene.pose(fromState), scene.pose(toState), progress)
    }

    fun render(
        canvas: Canvas,
        scene: TendiesScene,
        fromPose: ScenePose,
        toPose: ScenePose,
        progress: Float,
    ) {
        render(canvas, scene, fromPose, toPose) { progress.coerceIn(0f, 1f) }
    }

    fun render(
        canvas: Canvas,
        scene: TendiesScene,
        fromPose: ScenePose,
        toPose: ScenePose,
        transition: SceneTransition?,
        linearProgress: Float,
    ) {
        render(canvas, scene, fromPose, toPose) { keyPath ->
            transition?.transform(linearProgress, keyPath)
                ?: TransitionCurve.SmoothStep.transform(linearProgress, 1f)
        }
    }

    private fun render(
        canvas: Canvas,
        scene: TendiesScene,
        fromPose: ScenePose,
        toPose: ScenePose,
        progress: (String) -> Float,
    ) {
        canvas.drawColor(Color.BLACK)
        scene.documents.forEachIndexed { index, document ->
            renderDocument(canvas, scene, document, fromPose, toPose, index, progress)
        }
    }

    private fun renderDocument(
        canvas: Canvas,
        scene: TendiesScene,
        document: SceneDocument,
        fromPose: ScenePose,
        toPose: ScenePose,
        documentIndex: Int,
        progress: (String) -> Float,
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
            fromPose = fromPose,
            toPose = toPose,
            documentIndex = documentIndex,
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
        fromPose: ScenePose,
        toPose: ScenePose,
        documentIndex: Int,
        progress: (String) -> Float,
        inheritedAlpha: Float,
        parentHeight: Float,
        parentCoordinatesYUp: Boolean,
    ) {
        val from = fromPose.documents.getOrNull(documentIndex)?.get(layer.id)
            ?: LayerPose(layer.positionX, layer.positionY, layer.rotationRadians, layer.opacity)
        val to = toPose.documents.getOrNull(documentIndex)?.get(layer.id) ?: from
        val positionX = lerp(from.positionX, to.positionX, progress("position.x"))
        val rawPositionY = lerp(from.positionY, to.positionY, progress("position.y"))
        val positionY = if (parentCoordinatesYUp) parentHeight - rawPositionY else rawPositionY
        val rawRotation = lerp(
            from.rotationRadians,
            to.rotationRadians,
            progress("transform.rotation.z"),
        )
        val rotation = if (parentCoordinatesYUp) -rawRotation else rawRotation
        val opacity = lerp(from.opacity, to.opacity, progress("opacity"))
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
                bitmapDestination.apply {
                    set(layer.boundsX, layer.boundsY, layer.boundsX + layer.width, layer.boundsY + layer.height)
                },
                paint,
            )
        }
        layer.text?.let { text ->
            paint.color = layer.foregroundColor
            paint.textSize = layer.fontSize
            paint.textAlign = Paint.Align.CENTER
            val fontKey = layer.fontName.orEmpty()
            paint.typeface = typefaces.getOrPut(fontKey) { Typeface.create(layer.fontName, Typeface.NORMAL) }
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
                fromPose = fromPose,
                toPose = toPose,
                documentIndex = documentIndex,
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
