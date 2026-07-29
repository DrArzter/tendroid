package com.motandrwall.app.wallpaper

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.motandrwall.app.tendies.scene.ScenePose
import com.motandrwall.app.tendies.scene.SceneTransition
import com.motandrwall.app.tendies.scene.TendiesGlRenderer
import com.motandrwall.app.tendies.scene.TendiesScene

/** Owns the EGL window surface used by a single WallpaperService.Engine. */
internal class GpuWallpaperRenderer(private val logTag: String) {
    private val egl = EglWindow()
    private val renderer = TendiesGlRenderer()
    private var failedForCurrentSurface = false
    private var backendLogged = false

    fun onSurfaceCreated() {
        failedForCurrentSurface = false
        backendLogged = false
    }

    fun onSurfaceDestroyed() {
        release()
        failedForCurrentSurface = false
    }

    fun render(
        surface: Surface,
        width: Int,
        height: Int,
        scene: TendiesScene,
        fromPose: ScenePose,
        toPose: ScenePose,
        transition: SceneTransition?,
        progress: Float,
        frameTimeNanos: Long,
    ): Boolean = draw(surface, width, height, frameTimeNanos) {
            renderer.render(scene, fromPose, toPose, transition, progress, width, height)
        }

    fun renderFallback(
        surface: Surface,
        width: Int,
        height: Int,
        bitmap: Bitmap?,
        frameTimeNanos: Long,
    ): Boolean = draw(surface, width, height, frameTimeNanos) {
        renderer.renderFallback(bitmap, width, height)
    }

    fun release() {
        if (egl.isReady && egl.makeCurrent()) runCatching(renderer::release)
        egl.release()
        renderer.onContextLost()
    }

    private inline fun draw(
        surface: Surface,
        width: Int,
        height: Int,
        frameTimeNanos: Long,
        renderFrame: () -> Unit,
    ): Boolean {
        if (failedForCurrentSurface || !surface.isValid || width <= 0 || height <= 0) return false
        return runCatching {
            ensureContext(surface)
            renderFrame()
            EGLExt.eglPresentationTimeANDROID(egl.display, egl.surface, frameTimeNanos)
            check(egl.swapBuffers()) { "eglSwapBuffers failed: ${egl.error()}" }
            true
        }.getOrElse { error ->
            Log.e(logTag, "OpenGL renderer failed; using hardware Canvas for this surface", error)
            release()
            failedForCurrentSurface = true
            false
        }
    }

    private fun ensureContext(surface: Surface) {
        if (!egl.isReady) {
            egl.create(surface)
            renderer.onContextCreated()
            if (!backendLogged) {
                backendLogged = true
                Log.i(
                    logTag,
                    "renderer-backend=opengl-es vendor=${GLES20.glGetString(GLES20.GL_VENDOR)} " +
                        "gpu=${GLES20.glGetString(GLES20.GL_RENDERER)} " +
                        "version=${GLES20.glGetString(GLES20.GL_VERSION)}",
                )
            }
        } else {
            check(egl.makeCurrent()) { "eglMakeCurrent failed: ${egl.error()}" }
        }
    }

    private class EglWindow {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
            private set
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
            private set
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
            private set

        val isReady: Boolean
            get() = display != EGL14.EGL_NO_DISPLAY &&
                context != EGL14.EGL_NO_CONTEXT && surface != EGL14.EGL_NO_SURFACE

        fun create(nativeSurface: Surface) {
            release()
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed: ${error()}" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) {
                "eglInitialize failed: ${error()}"
            }
            val config = chooseConfig(recordable = true) ?: chooseConfig(recordable = false)
            check(config != null) { "No RGBA8888 EGL config with stencil buffer" }
            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${error()}" }
            surface = EGL14.eglCreateWindowSurface(
                display,
                config,
                nativeSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${error()}" }
            check(makeCurrent()) { "eglMakeCurrent failed: ${error()}" }
            EGL14.eglSwapInterval(display, 0)
        }

        fun makeCurrent(): Boolean = isReady && EGL14.eglMakeCurrent(display, surface, surface, context)

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(display, surface)

        fun release() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            context = EGL14.EGL_NO_CONTEXT
            surface = EGL14.EGL_NO_SURFACE
        }

        fun error(): String = "0x${EGL14.eglGetError().toString(16)}"

        private fun chooseConfig(recordable: Boolean): EGLConfig? {
            val attributes = buildList {
                add(EGL14.EGL_RED_SIZE)
                add(8)
                add(EGL14.EGL_GREEN_SIZE)
                add(8)
                add(EGL14.EGL_BLUE_SIZE)
                add(8)
                add(EGL14.EGL_ALPHA_SIZE)
                add(8)
                add(EGL14.EGL_STENCIL_SIZE)
                add(8)
                add(EGL14.EGL_RENDERABLE_TYPE)
                add(EGL14.EGL_OPENGL_ES2_BIT)
                if (recordable) {
                    add(EGL_RECORDABLE_ANDROID)
                    add(1)
                }
                add(EGL14.EGL_NONE)
            }.toIntArray()
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val success = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
            return if (success && count[0] > 0) configs[0] else null
        }
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
