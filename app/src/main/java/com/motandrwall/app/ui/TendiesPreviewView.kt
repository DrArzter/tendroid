package com.motandrwall.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import com.motandrwall.app.tendies.scene.ScenePose
import com.motandrwall.app.tendies.scene.TendiesScene
import com.motandrwall.app.tendies.scene.TendiesSceneRenderer

class TendiesPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val renderer = TendiesSceneRenderer()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var pendingScene: TendiesScene? = null
    private var snapshot: Bitmap? = null
    /** Kept alive only for scenes whose layers animate; others render once into [snapshot]. */
    private var animatedScene: TendiesScene? = null
    private var animatedBasePose: ScenePose? = null
    private var sceneEpochMillis = 0L

    fun show(scene: TendiesScene) {
        releaseScenes()
        pendingScene = scene
        if (width > 0 && height > 0) renderPendingScene() else post(::renderPendingScene)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scene = animatedScene
        val basePose = animatedBasePose
        if (scene != null && basePose != null) {
            if (sceneEpochMillis == 0L) sceneEpochMillis = drawingTime
            val elapsed = (drawingTime - sceneEpochMillis).toFloat()
            val pose = scene.animate(basePose, elapsed)
            renderer.render(canvas, scene, pose, pose, 1f)
            if (elapsed < scene.animationEndMillis) postInvalidateOnAnimation()
            return
        }
        snapshot?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
        }
    }

    override fun onDetachedFromWindow() {
        releaseScenes()
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }

    private fun releaseScenes() {
        pendingScene?.close()
        pendingScene = null
        animatedScene?.close()
        animatedScene = null
        animatedBasePose = null
        sceneEpochMillis = 0L
    }

    private fun renderPendingScene() {
        val scene = pendingScene ?: return
        pendingScene = null
        if (width <= 0 || height <= 0) {
            scene.close()
            return
        }
        if (scene.hasAnimations) {
            animatedScene = scene
            animatedBasePose = scene.pose(PREVIEW_STATE)
            sceneEpochMillis = 0L
            snapshot?.recycle()
            snapshot = null
            invalidate()
            return
        }
        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            renderer.render(Canvas(next), scene, PREVIEW_STATE)
        } catch (error: Exception) {
            next.recycle()
            throw error
        } finally {
            scene.close()
        }
        val previous = snapshot
        snapshot = next
        previous?.recycle()
        invalidate()
    }

    private companion object {
        const val PREVIEW_STATE = "Unlock"
    }
}
