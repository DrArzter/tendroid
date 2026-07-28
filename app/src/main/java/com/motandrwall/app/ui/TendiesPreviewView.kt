package com.motandrwall.app.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import com.motandrwall.app.tendies.scene.TendiesScene
import com.motandrwall.app.tendies.scene.TendiesSceneRenderer

class TendiesPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val renderer = TendiesSceneRenderer()
    private var scene: TendiesScene? = null

    fun show(scene: TendiesScene) {
        val previous = this.scene
        this.scene = scene
        previous?.close()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scene?.let { renderer.render(canvas, it, "Unlock") }
    }

    override fun onDetachedFromWindow() {
        scene?.close()
        scene = null
        super.onDetachedFromWindow()
    }
}
