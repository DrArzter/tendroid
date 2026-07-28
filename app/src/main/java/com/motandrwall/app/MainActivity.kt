package com.motandrwall.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.motandrwall.app.tendies.ImportedTendies
import com.motandrwall.app.tendies.TendiesImporter
import com.motandrwall.app.tendies.TendiesPackageAnalyzer
import com.motandrwall.app.tendies.TendiesSelectionStore
import com.motandrwall.app.tendies.scene.TendiesSceneLoader
import com.motandrwall.app.wallpaper.TendiesWallpaperService
import com.motandrwall.app.ui.TendiesPreviewView
import com.motandrwall.app.update.GitHubRelease
import com.motandrwall.app.update.GitHubUpdateManager
import com.motandrwall.app.update.UpdateCheckResult
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger()
    private lateinit var status: TextView
    private lateinit var importButton: TextView
    private lateinit var progress: ProgressBar
    private lateinit var preview: TendiesPreviewView
    private lateinit var updateStatus: TextView
    private lateinit var updateButton: TextView
    private var availableUpdate: GitHubRelease? = null
    private val updateManager by lazy { GitHubUpdateManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        val incomingPackage = intent?.data
        if (incomingPackage != null) importUri(incomingPackage) else loadSelectedOrDefault()
        checkForUpdates(silent = true)
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
        updateManager.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Kept for the framework file picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TENDIES && resultCode == RESULT_OK) {
            data?.data?.let(::importUri)
        }
    }

    private fun pickTendies() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(picker, REQUEST_TENDIES)
    }

    private fun importUri(uri: Uri) {
        val generation = loadGeneration.incrementAndGet()
        setBusy(true)
        status.text = "Inspecting package…"
        worker.execute {
            val result = runCatching {
                val input = contentResolver.openInputStream(uri)
                    ?: error("The selected document cannot be opened")
                val imported = TendiesImporter(filesDir.resolve("imports")).import(input)
                val scene = TendiesSceneLoader().load(imported.file)
                if (generation != loadGeneration.get()) {
                    scene.close()
                    return@runCatching null
                }
                TendiesSelectionStore(this).select(imported.file)
                imported to scene
            }
            runOnUiThread {
                if (generation != loadGeneration.get()) {
                    result.getOrNull()?.second?.close()
                    return@runOnUiThread
                }
                setBusy(false)
                result.fold(
                    onSuccess = { loaded ->
                        loaded?.let { (imported, scene) -> showImported(imported, scene) }
                    },
                    onFailure = {
                        Log.e(TAG, "Package import failed", it)
                        status.text = "Import failed\n\n${it.message ?: it.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    private fun loadSelectedOrDefault() {
        val generation = loadGeneration.incrementAndGet()
        setBusy(true)
        status.text = "Loading Roxy…"
        worker.execute {
            val result = runCatching {
                val store = TendiesSelectionStore(this)
                val existing = store.selectedFile()
                val needsSelection = existing == null
                val imported = if (!needsSelection) {
                    val report = FileInputStream(existing).use(TendiesPackageAnalyzer::analyze)
                    ImportedTendies(existing, existing.nameWithoutExtension, report)
                } else {
                    val bundled = assets.open(DEFAULT_PACKAGE_ASSET)
                    TendiesImporter(filesDir.resolve("imports")).import(bundled)
                }
                val scene = TendiesSceneLoader().load(imported.file)
                if (generation != loadGeneration.get()) {
                    scene.close()
                    return@runCatching null
                }
                if (needsSelection) store.select(imported.file)
                imported to scene
            }
            runOnUiThread {
                if (generation != loadGeneration.get()) {
                    result.getOrNull()?.second?.close()
                    return@runOnUiThread
                }
                setBusy(false)
                result.fold(
                    onSuccess = { loaded ->
                        loaded?.let { (imported, scene) -> showImported(imported, scene) }
                    },
                    onFailure = {
                        Log.e(TAG, "Default import failed", it)
                        status.text = "Default import failed\n\n${it.message ?: it.javaClass.simpleName}"
                    },
                )
            }
        }
    }

    private fun showImported(
        imported: ImportedTendies,
        scene: com.motandrwall.app.tendies.scene.TendiesScene,
    ) {
        preview.visibility = View.VISIBLE
        preview.show(scene)
        status.text = formatReport(imported)
    }

    private fun formatReport(imported: ImportedTendies): String = buildString {
        val report = imported.report
        appendLine(if (report.isRenderable) "Ready to render" else "Limited compatibility")
        appendLine()
        appendLine("${report.imageAssets} images  •  ${report.layers} layers  •  ${report.camlDocuments} scenes")
        appendLine(report.states.ifEmpty { setOf("No states") }.joinToString("  /  "))
        if (report.animations.isNotEmpty()) appendLine(report.animations.joinToString())
        appendLine("ID ${imported.sha256.take(12)}")
        if (report.warnings.isNotEmpty()) {
            appendLine()
            report.warnings.forEach { appendLine("Note · $it") }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        importButton.isEnabled = !busy
    }

    private fun checkForUpdates(silent: Boolean) {
        updateButton.isEnabled = false
        if (!silent) updateStatus.text = getString(R.string.update_checking)
        updateManager.check { result ->
            updateButton.isEnabled = true
            when (result) {
                UpdateCheckResult.Current -> {
                    availableUpdate = null
                    updateStatus.text = getString(R.string.update_current, BuildConfig.VERSION_CODE)
                    updateButton.text = getString(R.string.check_again)
                }
                is UpdateCheckResult.Available -> {
                    availableUpdate = result.release
                    updateStatus.text = getString(R.string.update_available, result.release.title)
                    updateButton.text = getString(R.string.install_update)
                }
                is UpdateCheckResult.Unavailable -> {
                    availableUpdate = null
                    updateStatus.text = if (result.reason.contains("private", ignoreCase = true)) {
                        getString(R.string.update_private_channel)
                    } else {
                        getString(R.string.update_unavailable)
                    }
                    updateButton.text = getString(R.string.check_again)
                }
            }
        }
    }

    private fun handleUpdateAction() {
        val release = availableUpdate
        if (release == null) {
            checkForUpdates(silent = false)
            return
        }
        updateButton.isEnabled = false
        updateStatus.text = getString(R.string.update_downloading)
        updateManager.downloadAndInstall(release) { message ->
            updateButton.isEnabled = true
            updateStatus.text = message
        }
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val accentValue = TypedValue()
        theme.resolveAttribute(android.R.attr.colorAccent, accentValue, true)
        val accent = accentValue.data.takeIf { it != 0 } ?: Color.rgb(144, 132, 255)
        val onAccent = if (Color.luminance(accent) > 0.45f) Color.rgb(8, 10, 14) else Color.WHITE

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.rgb(8, 10, 14)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                strokeColor?.let { setStroke(dp(1), it) }
            }

        fun textView(
            value: String,
            size: Float,
            color: Int,
            style: Int = Typeface.NORMAL,
        ) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = Typeface.create("sans-serif", style)
            includeFontPadding = false
        }

        fun action(value: String, primary: Boolean, onClick: () -> Unit) =
            TextView(this).apply {
                text = value
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (primary) onAccent else Color.rgb(232, 238, 240))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(
                    if (primary) accent else Color.rgb(26, 31, 39),
                    18,
                    if (primary) null else Color.rgb(52, 61, 72),
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(19, 23, 30), 24, Color.rgb(38, 45, 55))
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(40))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(15, 19, 25), Color.rgb(7, 9, 13)),
            )
        }

        val appBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appBar.addView(textView(
            getString(R.string.app_name),
            25f,
            Color.rgb(246, 248, 250),
            Typeface.BOLD,
        ), LinearLayout.LayoutParams(0, -2, 1f))
        appBar.addView(textView(
            getString(R.string.build_label, BuildConfig.VERSION_CODE),
            13f,
            Color.rgb(158, 168, 181),
        ))
        container.addView(appBar, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(22)
        })

        importButton = action(getString(R.string.choose_tendies), primary = true, ::pickTendies)
        container.addView(importButton, LinearLayout.LayoutParams(-1, dp(58)))

        container.addView(action(getString(R.string.open_wallpaper_preview), primary = false) {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                    putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(this@MainActivity, TendiesWallpaperService::class.java),
                    )
                },
            )
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })

        container.addView(textView(
            getString(R.string.current_wallpaper),
            12f,
            Color.rgb(128, 139, 153),
            Typeface.BOLD,
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(34) })

        preview = TendiesPreviewView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(26).toFloat())
                }
            }
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, dp(500)).apply {
            topMargin = dp(12)
        })

        progress = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
            indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        container.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(18)
        })

        val detailsCard = card()
        detailsCard.addView(textView(getString(R.string.wallpaper_details), 18f, Color.WHITE, Typeface.BOLD))
        status = textView(getString(R.string.no_package), 14f, Color.rgb(171, 181, 193)).apply {
            setLineSpacing(0f, 1.18f)
        }
        detailsCard.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        container.addView(detailsCard, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(14)
        })

        val updateCard = card()
        updateCard.addView(textView(getString(R.string.updates), 18f, Color.WHITE, Typeface.BOLD))
        updateStatus = textView(
            getString(R.string.update_initial, BuildConfig.VERSION_CODE),
            14f,
            Color.rgb(171, 181, 193),
        ).apply { setLineSpacing(0f, 1.18f) }
        updateCard.addView(updateStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        updateButton = action(getString(R.string.check_updates), primary = false, ::handleUpdateAction)
        updateCard.addView(updateButton, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(16) })
        container.addView(updateCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        return ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container)
        }
    }

    private companion object {
        const val REQUEST_TENDIES = 1001
        const val DEFAULT_PACKAGE_ASSET = "defaults/Roxy_Migurdia_.tendies"
        const val TAG = "Motandrwall"
    }
}
