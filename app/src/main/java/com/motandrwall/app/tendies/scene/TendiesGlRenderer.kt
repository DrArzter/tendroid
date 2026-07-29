package com.motandrwall.app.tendies.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * OpenGL ES 2.0 scene renderer. Images are uploaded once per EGL context and
 * every animation frame only updates four vertices and alpha per visible layer.
 */
internal class TendiesGlRenderer {
    private val bitmapTextures = IdentityHashMap<Bitmap, Int>()
    private val textTextures = IdentityHashMap<SceneLayer, Int>()
    private val typefaces = mutableMapOf<String, Typeface>()
    private val placeholderLayer = SceneLayer(
        id = "gpu-placeholder",
        name = null,
        boundsX = 0f,
        boundsY = 0f,
        width = 640f,
        height = 80f,
        positionX = 0f,
        positionY = 0f,
        anchorX = 0f,
        anchorY = 0f,
        rotationRadians = 0f,
        opacity = 1f,
        zPosition = 0f,
        geometryFlipped = false,
        masksToBounds = false,
        backgroundColor = null,
        imagePath = null,
        text = "Import a .tendies package",
        fontName = null,
        fontSize = 44f,
        foregroundColor = Color.rgb(116, 224, 193),
        children = emptyList(),
    )
    private val vertexData = FloatArray(VERTEX_FLOATS)
    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(VERTEX_FLOATS * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private var program = 0
    private var positionLocation = -1
    private var textureCoordinateLocation = -1
    private var resolutionLocation = -1
    private var colorLocation = -1
    private var useTextureLocation = -1
    private var samplerLocation = -1
    private var currentScene: TendiesScene? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var stencilDepth = 0

    fun onContextCreated() {
        onContextLost()
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        check(linkStatus[0] == GLES20.GL_TRUE) {
            "Could not link scene shader: ${GLES20.glGetProgramInfoLog(program)}"
        }
        positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoordinateLocation = GLES20.glGetAttribLocation(program, "aTextureCoordinate")
        resolutionLocation = GLES20.glGetUniformLocation(program, "uResolution")
        colorLocation = GLES20.glGetUniformLocation(program, "uColor")
        useTextureLocation = GLES20.glGetUniformLocation(program, "uUseTexture")
        samplerLocation = GLES20.glGetUniformLocation(program, "uTexture")
        checkGl("initialize")
    }

    fun onContextLost() {
        bitmapTextures.clear()
        textTextures.clear()
        currentScene = null
        program = 0
        positionLocation = -1
        textureCoordinateLocation = -1
        resolutionLocation = -1
        colorLocation = -1
        useTextureLocation = -1
        samplerLocation = -1
    }

    fun render(
        scene: TendiesScene,
        fromPose: ScenePose,
        toPose: ScenePose,
        transition: SceneTransition?,
        linearProgress: Float,
        width: Int,
        height: Int,
    ) {
        check(program != 0) { "OpenGL renderer has no current context" }
        if (currentScene !== scene) {
            releaseTextures()
            currentScene = scene
            // Upload in one batch instead of stalling unpredictably halfway
            // through the first animated transition.
            scene.bitmaps.values.toSet().forEach(::textureFor)
        }
        outputWidth = width
        outputHeight = height
        stencilDepth = 0

        prepareFrame(width, height, Color.BLACK)

        val progress: (String) -> Float = { keyPath ->
            transition?.transform(linearProgress, keyPath)
                ?: TransitionCurve.SmoothStep.transform(linearProgress, 1f)
        }
        scene.documents.forEachIndexed { index, document ->
            renderDocument(scene, document, fromPose, toPose, index, progress)
        }
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        checkGl("render")
    }

    fun renderFallback(bitmap: Bitmap?, width: Int, height: Int) {
        check(program != 0) { "OpenGL renderer has no current context" }
        if (currentScene != null) {
            releaseTextures()
            currentScene = null
        }
        outputWidth = width
        outputHeight = height
        stencilDepth = 0
        prepareFrame(width, height, Color.rgb(14, 17, 22))
        if (bitmap != null && !bitmap.isRecycled) {
            val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val left = (width - drawnWidth) / 2f
            val top = (height - drawnHeight) / 2f
            drawQuad(
                transform = AffineTransform2D(),
                left = left,
                top = top,
                right = left + drawnWidth,
                bottom = top + drawnHeight,
                texture = textureFor(bitmap),
                red = 1f,
                green = 1f,
                blue = 1f,
                alpha = 1f,
            )
        } else {
            val left = (width - placeholderLayer.width) / 2f
            val top = (height - placeholderLayer.height) / 2f
            drawQuad(
                transform = AffineTransform2D(),
                left = left,
                top = top,
                right = left + placeholderLayer.width,
                bottom = top + placeholderLayer.height,
                texture = textTextureFor(placeholderLayer),
                red = 1f,
                green = 1f,
                blue = 1f,
                alpha = 1f,
            )
        }
        checkGl("render fallback")
    }

    fun release() {
        releaseTextures()
        if (program != 0) GLES20.glDeleteProgram(program)
        onContextLost()
    }

    private fun prepareFrame(width: Int, height: Int, clearColor: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glClearColor(
            Color.red(clearColor) / 255f,
            Color.green(clearColor) / 255f,
            Color.blue(clearColor) / 255f,
            1f,
        )
        GLES20.glClearStencil(0)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glUniform2f(resolutionLocation, width.toFloat(), height.toFloat())
        GLES20.glUniform1i(samplerLocation, 0)
    }

    private fun renderDocument(
        scene: TendiesScene,
        document: SceneDocument,
        fromPose: ScenePose,
        toPose: ScenePose,
        documentIndex: Int,
        progress: (String) -> Float,
    ) {
        val root = document.root
        if (root.width <= 0f || root.height <= 0f) return
        val scale = max(outputWidth / root.width, outputHeight / root.height)
        val transform = AffineTransform2D()
            .translated((outputWidth - root.width * scale) / 2f, (outputHeight - root.height * scale) / 2f)
            .scaled(scale, scale)
        renderLayer(
            scene = scene,
            layer = root,
            fromPose = fromPose,
            toPose = toPose,
            documentIndex = documentIndex,
            progress = progress,
            inheritedAlpha = 1f,
            parentHeight = root.height,
            parentCoordinatesYUp = false,
            parentTransform = transform,
        )
    }

    private fun renderLayer(
        scene: TendiesScene,
        layer: SceneLayer,
        fromPose: ScenePose,
        toPose: ScenePose,
        documentIndex: Int,
        progress: (String) -> Float,
        inheritedAlpha: Float,
        parentHeight: Float,
        parentCoordinatesYUp: Boolean,
        parentTransform: AffineTransform2D,
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

        val transform = parentTransform
            .translated(positionX, positionY)
            .rotated(rotation)
            .translated(
                -layer.anchorX * layer.width - layer.boundsX,
                -layer.anchorY * layer.height - layer.boundsY,
            )
        val clipPushed = layer.masksToBounds
        if (clipPushed) pushClip(transform, layer)

        layer.backgroundColor?.let { drawSolid(transform, layer, it, alpha) }
        layer.imagePath?.let(scene.bitmaps::get)?.let { bitmap ->
            drawTextured(transform, layer, textureFor(bitmap), alpha)
        }
        layer.text?.let {
            drawTextured(transform, layer, textTextureFor(layer), alpha)
        }
        layer.children.forEach { child ->
            renderLayer(
                scene = scene,
                layer = child,
                fromPose = fromPose,
                toPose = toPose,
                documentIndex = documentIndex,
                progress = progress,
                inheritedAlpha = alpha,
                parentHeight = layer.height,
                parentCoordinatesYUp = !layer.geometryFlipped,
                parentTransform = transform,
            )
        }
        if (clipPushed) popClip(transform, layer)
    }

    private fun pushClip(transform: AffineTransform2D, layer: SceneLayer) {
        GLES20.glEnable(GLES20.GL_STENCIL_TEST)
        GLES20.glColorMask(false, false, false, false)
        GLES20.glStencilFunc(GLES20.GL_EQUAL, stencilDepth, STENCIL_MASK)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR)
        drawQuad(transform, layer, texture = 0, red = 0f, green = 0f, blue = 0f, alpha = 0f)
        GLES20.glColorMask(true, true, true, true)
        stencilDepth += 1
        GLES20.glStencilFunc(GLES20.GL_EQUAL, stencilDepth, STENCIL_MASK)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
    }

    private fun popClip(transform: AffineTransform2D, layer: SceneLayer) {
        GLES20.glColorMask(false, false, false, false)
        GLES20.glStencilFunc(GLES20.GL_EQUAL, stencilDepth, STENCIL_MASK)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_DECR)
        drawQuad(transform, layer, texture = 0, red = 0f, green = 0f, blue = 0f, alpha = 0f)
        GLES20.glColorMask(true, true, true, true)
        stencilDepth -= 1
        if (stencilDepth == 0) {
            GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        } else {
            GLES20.glStencilFunc(GLES20.GL_EQUAL, stencilDepth, STENCIL_MASK)
            GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP)
        }
    }

    private fun drawSolid(transform: AffineTransform2D, layer: SceneLayer, color: Int, inheritedAlpha: Float) {
        val alpha = Color.alpha(color) / 255f * inheritedAlpha
        drawQuad(
            transform = transform,
            layer = layer,
            texture = 0,
            red = Color.red(color) / 255f * alpha,
            green = Color.green(color) / 255f * alpha,
            blue = Color.blue(color) / 255f * alpha,
            alpha = alpha,
        )
    }

    private fun drawTextured(
        transform: AffineTransform2D,
        layer: SceneLayer,
        texture: Int,
        alpha: Float,
    ) {
        drawQuad(transform, layer, texture, alpha, alpha, alpha, alpha)
    }

    private fun drawQuad(
        transform: AffineTransform2D,
        layer: SceneLayer,
        texture: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) = drawQuad(
        transform = transform,
        left = layer.boundsX,
        top = layer.boundsY,
        right = layer.boundsX + layer.width,
        bottom = layer.boundsY + layer.height,
        texture = texture,
        red = red,
        green = green,
        blue = blue,
        alpha = alpha,
    )

    private fun drawQuad(
        transform: AffineTransform2D,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        texture: Int,
        red: Float,
        green: Float,
        blue: Float,
        alpha: Float,
    ) {
        val topLeft = transform.map(left, top)
        val bottomLeft = transform.map(left, bottom)
        val topRight = transform.map(right, top)
        val bottomRight = transform.map(right, bottom)
        putVertex(0, topLeft, 0f, 0f)
        putVertex(1, bottomLeft, 0f, 1f)
        putVertex(2, topRight, 1f, 0f)
        putVertex(3, bottomRight, 1f, 1f)
        vertexBuffer.clear()
        vertexBuffer.put(vertexData)
        vertexBuffer.position(0)

        GLES20.glUniform4f(colorLocation, red, green, blue, alpha)
        GLES20.glUniform1f(useTextureLocation, if (texture == 0) 0f else 1f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(
            positionLocation,
            POSITION_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer,
        )
        vertexBuffer.position(POSITION_COMPONENTS)
        GLES20.glEnableVertexAttribArray(textureCoordinateLocation)
        GLES20.glVertexAttribPointer(
            textureCoordinateLocation,
            TEXTURE_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            VERTEX_STRIDE_BYTES,
            vertexBuffer,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
    }

    private fun putVertex(index: Int, point: Point2D, textureX: Float, textureY: Float) {
        val offset = index * FLOATS_PER_VERTEX
        vertexData[offset] = point.x
        vertexData[offset + 1] = point.y
        vertexData[offset + 2] = textureX
        vertexData[offset + 3] = textureY
    }

    private fun textureFor(bitmap: Bitmap): Int = bitmapTextures[bitmap] ?: createTexture(bitmap).also {
        bitmapTextures[bitmap] = it
    }

    private fun textTextureFor(layer: SceneLayer): Int = textTextures[layer] ?: run {
        val width = ceil(layer.width).toInt().coerceIn(1, MAX_TEXT_TEXTURE_EDGE)
        val height = ceil(layer.height).toInt().coerceIn(1, MAX_TEXT_TEXTURE_EDGE)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.foregroundColor
            textSize = layer.fontSize
            textAlign = Paint.Align.CENTER
            val key = layer.fontName.orEmpty()
            typeface = typefaces.getOrPut(key) { Typeface.create(layer.fontName, Typeface.NORMAL) }
        }
        val metrics = paint.fontMetrics
        val baseline = layer.height / 2f - (metrics.ascent + metrics.descent) / 2f
        Canvas(bitmap).apply {
            scale(width / layer.width.coerceAtLeast(1f), height / layer.height.coerceAtLeast(1f))
            drawText(layer.text.orEmpty(), layer.width / 2f, baseline, paint)
        }
        createTexture(bitmap).also {
            bitmap.recycle()
            textTextures[layer] = it
        }
    }

    private fun createTexture(bitmap: Bitmap): Int {
        check(!bitmap.isRecycled) { "Cannot upload a recycled scene bitmap" }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "Could not allocate an OpenGL texture" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        checkGl("upload texture")
        return ids[0]
    }

    private fun releaseTextures() {
        val ids = (bitmapTextures.values + textTextures.values).distinct().toIntArray()
        if (ids.isNotEmpty()) GLES20.glDeleteTextures(ids.size, ids, 0)
        bitmapTextures.clear()
        textTextures.clear()
        currentScene = null
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) {
            "Could not compile scene shader: ${GLES20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$operation failed with OpenGL error 0x${error.toString(16)}" }
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress

    private companion object {
        const val VERTEX_COUNT = 4
        const val POSITION_COMPONENTS = 2
        const val TEXTURE_COMPONENTS = 2
        const val FLOATS_PER_VERTEX = POSITION_COMPONENTS + TEXTURE_COMPONENTS
        const val VERTEX_FLOATS = VERTEX_COUNT * FLOATS_PER_VERTEX
        const val VERTEX_STRIDE_BYTES = FLOATS_PER_VERTEX * Float.SIZE_BYTES
        const val STENCIL_MASK = 0xFF
        const val MAX_TEXT_TEXTURE_EDGE = 2048

        const val VERTEX_SHADER = """
            attribute vec2 aPosition;
            attribute vec2 aTextureCoordinate;
            uniform vec2 uResolution;
            varying vec2 vTextureCoordinate;
            void main() {
                vec2 zeroToOne = aPosition / uResolution;
                vec2 clip = zeroToOne * 2.0 - 1.0;
                gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
                vTextureCoordinate = aTextureCoordinate;
            }
        """

        const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec4 uColor;
            uniform float uUseTexture;
            varying vec2 vTextureCoordinate;
            void main() {
                vec4 source = texture2D(uTexture, vTextureCoordinate);
                gl_FragColor = mix(uColor, source * uColor, uUseTexture);
            }
        """
    }
}
