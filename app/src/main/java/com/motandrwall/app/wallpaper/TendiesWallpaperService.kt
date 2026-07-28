package com.motandrwall.app.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import com.motandrwall.app.tendies.TendiesSelectionStore
import com.motandrwall.app.tendies.scene.TendiesScene
import com.motandrwall.app.tendies.scene.TendiesSceneLoader
import com.motandrwall.app.tendies.scene.TendiesSceneRenderer
import java.util.concurrent.Executors
import kotlin.math.roundToLong

class TendiesWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = TendiesEngine()

    private inner class TendiesEngine : Engine() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val renderer = TendiesSceneRenderer()
        private val loader = Executors.newSingleThreadExecutor()
        private val choreographer = Choreographer.getInstance()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val sceneLock = Any()
        private var scene: TendiesScene? = null
        private var destroyed = false
        private var engineVisible = false
        private var displayedState = "Unlock"
        private var animationFrom = "Unlock"
        private var animationTo = "Unlock"
        private var animationStartedAtNanos = 0L
        private var animationDurationMillis = DEFAULT_TRANSITION_MILLIS
        private var animationUsesTimer = false
        private var animationTickDelayMillis = DEFAULT_FRAME_DELAY_MILLIS
        private var animationFrameCount = 0
        private var animationDrawNanos = 0L
        private var animationMaxDrawNanos = 0L
        private var canvasBackendLogged = false
        private val keyguardCheck = object : Runnable {
            override fun run() {
                if (destroyed || !engineVisible || !isDeviceInteractive()) return
                if (isKeyguardLocked()) {
                    if (animationTo != "Locked") transitionTo("Locked")
                    mainHandler.postDelayed(this, KEYGUARD_CHECK_MILLIS)
                } else {
                    handleUnlocked()
                }
            }
        }
        private val animationFrame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                renderAnimationFrame(frameTimeNanos)
            }
        }
        private val animationTimerFrame = object : Runnable {
            override fun run() {
                renderAnimationFrame(System.nanoTime())
            }
        }
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleGoingToSleep()
                    Intent.ACTION_SCREEN_ON -> handleWakingUp()
                    Intent.ACTION_USER_PRESENT -> handleUnlocked()
                    else -> showStateImmediately(currentSystemState())
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            displayedState = currentSystemState()
            animationFrom = displayedState
            animationTo = displayedState
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
            logState("engine-created")
            loadSelectedScene()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            engineVisible = visible
            logState("visibility=$visible")
            if (!visible) {
                cancelAnimationCallbacks()
                if (animationTo == "Sleep") prepareState("Sleep")
                return
            }
            requestAnimationFrameRate(surfaceHolder)
            val state = currentSystemState()
            if (displayedState == state) {
                showStateImmediately(state)
            } else if (animationTo == state) {
                scheduleAnimation()
            } else {
                transitionTo(state)
            }
            if (state == "Locked") startKeyguardChecks()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestAnimationFrameRate(holder)
            drawFrame()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            requestAnimationFrameRate(holder)
        }

        override fun onWallpaperFlagsChanged(which: Int) {
            super.onWallpaperFlagsChanged(which)
            logState("wallpaper-flags=$which")
            // `which` describes which wallpaper destination this Engine belongs to;
            // it is not the current lock-screen state. Samsung can keep reporting
            // FLAG_LOCK after USER_PRESENT, so always ask Keyguard for live state.
            val state = currentSystemState()
            if (state == animationTo) return
            transitionTo(state)
        }

        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            logState("command=$action")
            when (action) {
                COMMAND_WAKING_UP -> handleWakingUp()
                COMMAND_GOING_TO_SLEEP -> handleGoingToSleep()
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        override fun onDestroy() {
            destroyed = true
            cancelAnimationCallbacks()
            mainHandler.removeCallbacks(keyguardCheck)
            runCatching { unregisterReceiver(screenReceiver) }
            loader.shutdownNow()
            synchronized(sceneLock) {
                scene?.close()
                scene = null
            }
            super.onDestroy()
        }

        private fun loadSelectedScene() {
            val selected = TendiesSelectionStore(this@TendiesWallpaperService).selectedFile()
                ?: return
            loader.execute {
                val loaded = runCatching { TendiesSceneLoader().load(selected) }.getOrNull() ?: return@execute
                if (destroyed) {
                    loaded.close()
                    return@execute
                }
                mainHandler.post {
                    if (destroyed) {
                        loaded.close()
                        return@post
                    }
                    synchronized(sceneLock) {
                        scene?.close()
                        scene = loaded
                    }
                    val state = currentSystemState()
                    showStateImmediately(state)
                    logState("scene-loaded states=${loaded.documents.flatMap { it.states.keys }.distinct()}")
                }
            }
        }

        private fun transitionTo(targetState: String) {
            if (targetState == animationTo && targetState != displayedState) return
            cancelAnimationCallbacks()
            if (targetState == displayedState) {
                animationFrom = targetState
                animationTo = targetState
                if (engineVisible) drawFrame()
                return
            }
            animationFrom = displayedState
            animationTo = targetState
            animationStartedAtNanos = 0L
            animationDurationMillis = synchronized(sceneLock) {
                scene?.transitionDurationMillis(animationFrom, animationTo)
            } ?: DEFAULT_TRANSITION_MILLIS
            animationUsesTimer = targetState == "Sleep" && !isDeviceInteractive()
            animationFrameCount = 0
            animationDrawNanos = 0L
            animationMaxDrawNanos = 0L
            logState("transition=$animationFrom->$animationTo durationMs=$animationDurationMillis")
            if (engineVisible) postAnimationFrame()
        }

        private fun scheduleAnimation() {
            cancelAnimationCallbacks()
            animationStartedAtNanos = 0L
            animationUsesTimer = animationTo == "Sleep" && !isDeviceInteractive()
            animationFrameCount = 0
            animationDrawNanos = 0L
            animationMaxDrawNanos = 0L
            postAnimationFrame()
        }

        private fun prepareState(state: String) {
            cancelAnimationCallbacks()
            displayedState = state
            animationFrom = state
            animationTo = state
            animationStartedAtNanos = 0L
        }

        private fun showStateImmediately(state: String) {
            prepareState(state)
            logState("show=$state")
            if (engineVisible) drawFrame()
        }

        private fun handleGoingToSleep() {
            mainHandler.removeCallbacks(keyguardCheck)
            transitionTo("Sleep")
        }

        private fun handleWakingUp() {
            requestAnimationFrameRate(surfaceHolder)
            val state = currentSystemState()
            if (state != animationTo) transitionTo(state)
            startKeyguardChecks()
        }

        private fun handleUnlocked() {
            mainHandler.removeCallbacks(keyguardCheck)
            requestAnimationFrameRate(surfaceHolder)
            transitionTo("Unlock")
        }

        private fun startKeyguardChecks() {
            mainHandler.removeCallbacks(keyguardCheck)
            mainHandler.post(keyguardCheck)
        }

        private fun drawFrame(
            fromState: String = displayedState,
            toState: String = displayedState,
            progress: Float = 1f,
        ) {
            var canvas: Canvas? = null
            try {
                canvas = lockRenderCanvas() ?: return
                synchronized(sceneLock) {
                    val loaded = scene
                    if (loaded != null) {
                        renderer.render(canvas, loaded, fromState, toState, progress)
                    } else {
                        drawPlaceholder(canvas)
                    }
                }
            } finally {
                canvas?.let(surfaceHolder::unlockCanvasAndPost)
            }
        }

        private fun renderAnimationFrame(frameTimeNanos: Long) {
            if (destroyed || !engineVisible) return
            if (animationStartedAtNanos == 0L) animationStartedAtNanos = frameTimeNanos
            val elapsedMillis = (frameTimeNanos - animationStartedAtNanos) / 1_000_000f
            val linear = (elapsedMillis / animationDurationMillis.coerceAtLeast(1f)).coerceIn(0f, 1f)
            val eased = linear * linear * (3f - 2f * linear)
            val drawStartedAt = System.nanoTime()
            drawFrame(animationFrom, animationTo, eased)
            val drawNanos = System.nanoTime() - drawStartedAt
            animationFrameCount += 1
            animationDrawNanos += drawNanos
            animationMaxDrawNanos = maxOf(animationMaxDrawNanos, drawNanos)
            if (linear < 1f) {
                postAnimationFrame()
            } else {
                displayedState = animationTo
                val averageMillis = animationDrawNanos / animationFrameCount.coerceAtLeast(1) / 1_000_000f
                val maxMillis = animationMaxDrawNanos / 1_000_000f
                Log.i(LOG_TAG, "transition-complete frames=$animationFrameCount avgMs=$averageMillis maxMs=$maxMillis")
            }
        }

        private fun postAnimationFrame() {
            if (animationUsesTimer) {
                mainHandler.postDelayed(animationTimerFrame, animationTickDelayMillis)
            } else {
                choreographer.postFrameCallback(animationFrame)
            }
        }

        private fun cancelAnimationCallbacks() {
            choreographer.removeFrameCallback(animationFrame)
            mainHandler.removeCallbacks(animationTimerFrame)
        }

        private fun requestAnimationFrameRate(holder: SurfaceHolder) {
            if (Build.VERSION.SDK_INT < 30) return
            runCatching {
                val activeDisplay = displayContext?.display
                val currentFrameRate = activeDisplay
                    ?.refreshRate
                    ?.takeIf { it > 0f }
                    ?: DEFAULT_FRAME_RATE
                val samsungRefreshMode = Settings.Secure.getInt(
                    contentResolver,
                    SAMSUNG_REFRESH_RATE_MODE_SETTING,
                    UNKNOWN_REFRESH_MODE,
                )
                val supportedRates = activeDisplay?.supportedModes
                    ?.map { it.refreshRate }
                    .orEmpty()
                val requestedFrameRate = when {
                    !isDeviceInteractive() -> currentFrameRate
                    isPowerSaveMode() -> currentFrameRate.coerceAtMost(DEFAULT_FRAME_RATE)
                    samsungRefreshMode == ADAPTIVE_REFRESH_MODE ->
                        supportedRates.maxOrNull() ?: currentFrameRate
                    samsungRefreshMode == STANDARD_REFRESH_MODE ->
                        supportedRates.filter { it <= DEFAULT_FRAME_RATE + 0.5f }.maxOrNull()
                            ?: DEFAULT_FRAME_RATE
                    else -> currentFrameRate
                }
                holder.surface.setFrameRate(
                    requestedFrameRate,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                )
                animationTickDelayMillis = (1_000f / requestedFrameRate)
                    .roundToLong()
                    .coerceIn(MIN_FRAME_DELAY_MILLIS, MAX_FRAME_DELAY_MILLIS)
                Log.i(
                    LOG_TAG,
                    "requested-frame-rate=$requestedFrameRate current=$currentFrameRate " +
                        "samsungMode=$samsungRefreshMode",
                )
            }.onFailure { Log.w(LOG_TAG, "Could not request current frame rate", it) }
        }

        private fun lockRenderCanvas(): Canvas? = try {
            surfaceHolder.lockHardwareCanvas().also {
                if (!canvasBackendLogged) {
                    canvasBackendLogged = true
                    Log.i(LOG_TAG, "canvas-backend=hardware")
                }
            }
        } catch (error: Exception) {
            if (!canvasBackendLogged) {
                canvasBackendLogged = true
                Log.w(LOG_TAG, "canvas-backend=software", error)
            }
            surfaceHolder.lockCanvas()
        }

        private fun currentSystemState(): String {
            return if (isKeyguardLocked()) "Locked" else "Unlock"
        }

        private fun isKeyguardLocked(): Boolean {
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            return keyguard.isKeyguardLocked
        }

        private fun isDeviceInteractive(): Boolean {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            return power.isInteractive
        }

        private fun isPowerSaveMode(): Boolean {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            return power.isPowerSaveMode
        }

        private fun drawPlaceholder(canvas: Canvas) {
            canvas.drawColor(Color.rgb(14, 17, 22))
            paint.color = Color.rgb(116, 224, 193)
            paint.textSize = 44f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("Import a .tendies package", canvas.width / 2f, canvas.height / 2f, paint)
        }

        private fun logState(event: String) {
            Log.i(
                LOG_TAG,
                "$event displayed=$displayedState target=$animationTo " +
                    "keyguard=${isKeyguardLocked()} interactive=${isDeviceInteractive()}",
            )
        }

    }

    private companion object {
        const val DEFAULT_TRANSITION_MILLIS = 800f
        const val KEYGUARD_CHECK_MILLIS = 100L
        const val LOG_TAG = "Motandrwall"
        const val COMMAND_WAKING_UP = "android.wallpaper.wakingup"
        const val COMMAND_GOING_TO_SLEEP = "android.wallpaper.goingtosleep"
        const val DEFAULT_FRAME_RATE = 60f
        const val DEFAULT_FRAME_DELAY_MILLIS = 16L
        const val MIN_FRAME_DELAY_MILLIS = 8L
        const val MAX_FRAME_DELAY_MILLIS = 34L
        const val SAMSUNG_REFRESH_RATE_MODE_SETTING = "refresh_rate_mode"
        const val UNKNOWN_REFRESH_MODE = -1
        const val STANDARD_REFRESH_MODE = 0
        const val ADAPTIVE_REFRESH_MODE = 1
    }
}
