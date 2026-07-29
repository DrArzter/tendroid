package com.motandrwall.app.gallery

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.motandrwall.app.BuildConfig
import com.motandrwall.app.R
import java.util.concurrent.Executors

class GalleryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val previewWorker = Executors.newFixedThreadPool(3)
    private val repository by lazy { GalleryRepository(this) }
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        loadCatalog()
    }

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        previewWorker.shutdownNow()
        super.onDestroy()
    }

    private fun loadCatalog() {
        setBusy(true, getString(R.string.gallery_loading))
        worker.execute {
            val result = runCatching(repository::fetchCatalog)
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false, "")
                result.fold(
                    onSuccess = ::showCatalog,
                    onFailure = { status.text = getString(R.string.gallery_unavailable, it.message ?: "") },
                )
            }
        }
    }

    private fun showCatalog(wallpapers: List<GalleryWallpaper>) {
        list.removeAllViews()
        if (wallpapers.isEmpty()) {
            status.text = getString(R.string.gallery_empty)
            return
        }
        status.text = resources.getQuantityString(
            R.plurals.gallery_count,
            wallpapers.size,
            wallpapers.size,
        )
        wallpapers.take(MAX_VISIBLE_ITEMS).forEach { wallpaper ->
            val preview = addWallpaperCard(wallpaper)
            previewWorker.execute {
                val bitmap = runCatching { decodePreview(repository.fetchPreview(wallpaper)) }.getOrNull()
                runOnUiThread {
                    if (destroyed) bitmap?.recycle() else bitmap?.let(preview::setImageBitmap)
                }
            }
        }
    }

    private fun addWallpaperCard(wallpaper: GalleryWallpaper): ImageView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(Color.rgb(19, 23, 30), dp(24), Color.rgb(38, 45, 55))
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(10, 12, 17))
            contentDescription = wallpaper.previewAlt
        }
        card.addView(preview, LinearLayout.LayoutParams(-1, dp(250)))
        card.addView(label(wallpaper.title, 20f, Color.WHITE, Typeface.BOLD), params(top = dp(16)))
        card.addView(label("by ${wallpaper.authorName}  •  ${wallpaper.license}", 13f, MUTED), params(top = dp(7)))
        card.addView(label(wallpaper.description, 14f, Color.rgb(211, 217, 224)), params(top = dp(12)))
        val details = (wallpaper.states + wallpaper.tags.take(4)).joinToString("  •  ")
        card.addView(label(details, 12f, MUTED), params(top = dp(12)))
        val supported = BuildConfig.VERSION_CODE >= wallpaper.minTendroidBuild
        val install = action(
            if (supported) getString(R.string.gallery_install) else
                getString(R.string.gallery_requires_build, wallpaper.minTendroidBuild),
        ).apply {
            isEnabled = supported
            alpha = if (supported) 1f else 0.55f
            setOnClickListener { installWallpaper(wallpaper, this) }
        }
        card.addView(install, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(16) })
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        return preview
    }

    private fun installWallpaper(wallpaper: GalleryWallpaper, button: TextView) {
        button.isEnabled = false
        setBusy(true, getString(R.string.gallery_installing, wallpaper.title))
        worker.execute {
            val result = runCatching { repository.install(wallpaper) }
            runOnUiThread {
                if (destroyed) return@runOnUiThread
                setBusy(false, "")
                result.fold(
                    onSuccess = {
                        status.text = getString(R.string.gallery_installed, wallpaper.title)
                        button.text = getString(R.string.gallery_installed_button)
                    },
                    onFailure = {
                        status.text = getString(R.string.gallery_install_failed, it.message ?: "")
                        button.isEnabled = true
                    },
                )
            }
        }
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(8, 10, 14)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(15, 19, 25), Color.rgb(7, 9, 13)),
            )
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(action("‹").apply { setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(52), dp(52)))
        bar.addView(label(getString(R.string.gallery_title), 25f, Color.WHITE, Typeface.BOLD), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        container.addView(bar)
        container.addView(label(getString(R.string.gallery_description), 14f, MUTED), params(top = dp(14)))
        progress = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Color.rgb(143, 135, 247))
        }
        container.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })
        status = label("", 14f, Color.rgb(203, 210, 219)).apply { setLineSpacing(0f, 1.16f) }
        container.addView(status, params(top = dp(14)))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list, LinearLayout.LayoutParams(-1, -2))
        container.addView(action(getString(R.string.gallery_submit)).apply {
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CONTRIBUTING_URL)))
            }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(20) })
        container.addView(action(getString(R.string.gallery_refresh)).apply {
            setOnClickListener { loadCatalog() }
        }, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) })
        return ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container)
        }
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (message.isNotEmpty()) status.text = message
    }

    private fun label(value: String, size: Float, color: Int, style: Int = Typeface.NORMAL) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

    private fun action(value: String) = label(value, 15f, Color.rgb(235, 238, 244), Typeface.BOLD).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = rounded(Color.rgb(27, 32, 41), (18 * resources.displayMetrics.density).toInt(), Color.rgb(55, 63, 77))
    }

    private fun params(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        stroke?.let { setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), it) }
    }

    private fun decodePreview(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_PREVIEW_EDGE || bounds.outHeight !in 1..MAX_PREVIEW_EDGE) return null
        var sample = 1
        while (bounds.outWidth / sample > PREVIEW_TARGET_EDGE || bounds.outHeight / sample > PREVIEW_TARGET_EDGE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val CONTRIBUTING_URL = "https://github.com/DrArzter/tendroid-gallery/blob/main/CONTRIBUTING.md"
        const val MAX_VISIBLE_ITEMS = 24
        const val MAX_PREVIEW_EDGE = 8_192
        const val PREVIEW_TARGET_EDGE = 512
        val MUTED = Color.rgb(158, 168, 181)
    }
}
