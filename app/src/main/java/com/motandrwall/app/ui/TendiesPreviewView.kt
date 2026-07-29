package com.motandrwall.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
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

    fun show(scene: TendiesScene) {
        pendingScene?.close()
        pendingScene = scene
        if (width > 0 && height > 0) renderPendingScene() else post(::renderPendingScene)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        snapshot?.let { bitmap ->
            canvas.drawBitmap(bitmap, null, Rect(0, 0, width, height), paint)
        }
    }

    override fun onDetachedFromWindow() {
        pendingScene?.close()
        pendingScene = null
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }

    private fun renderPendingScene() {
        val scene = pendingScene ?: return
        pendingScene = null
        if (width <= 0 || height <= 0) {
            scene.close()
            return
        }
        val next = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            renderer.render(Canvas(next), scene, "Unlock")
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
}
