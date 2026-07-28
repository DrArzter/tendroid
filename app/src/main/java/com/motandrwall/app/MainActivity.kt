package com.motandrwall.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger()
    private lateinit var status: TextView
    private lateinit var importButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var preview: TendiesPreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        val incomingPackage = intent?.data
        if (incomingPackage != null) importUri(incomingPackage) else loadSelectedOrDefault()
    }

    override fun onDestroy() {
        loadGeneration.incrementAndGet()
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
        appendLine("Package imported")
        appendLine()
        appendLine("Compatibility: ${if (report.isRenderable) "renderable" else "unsupported"}")
        appendLine("Images: ${report.imageAssets}")
        appendLine("CAML scenes: ${report.camlDocuments}")
        appendLine("Layers: ${report.layers} (${report.imageLayers} image, ${report.textLayers} text)")
        appendLine("States: ${report.states.ifEmpty { setOf("none") }.joinToString()}")
        appendLine("Animations: ${report.animations.ifEmpty { setOf("none") }.joinToString()}")
        appendLine("Package ID: ${imported.sha256.take(12)}")
        if (report.warnings.isNotEmpty()) {
            appendLine()
            appendLine("Warnings:")
            report.warnings.forEach { appendLine("• $it") }
        }
        appendLine()
        append("Ready for the Android live wallpaper preview.")
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) ProgressBar.VISIBLE else ProgressBar.GONE
        importButton.isEnabled = !busy
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
            setBackgroundColor(Color.rgb(14, 17, 22))
        }

        container.addView(TextView(this).apply {
            text = "Tendroid"
            textSize = 40f
            setTextColor(Color.rgb(244, 246, 248))
            gravity = Gravity.CENTER
        }, ViewGroup.LayoutParams(-1, -2))

        container.addView(TextView(this).apply {
            text = "Import an iOS PosterBoard package, inspect its scene, and prepare it for the Android live wallpaper engine."
            textSize = 16f
            setTextColor(Color.rgb(170, 178, 191))
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(28))
        }, ViewGroup.LayoutParams(-1, -2))

        importButton = Button(this).apply {
            text = "Choose .tendies"
            setOnClickListener { pickTendies() }
        }
        container.addView(importButton, LinearLayout.LayoutParams(-1, dp(56)))

        preview = TendiesPreviewView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        container.addView(preview, LinearLayout.LayoutParams(-1, dp(520)).apply {
            topMargin = dp(18)
        })

        container.addView(Button(this).apply {
            text = "Open live wallpaper preview"
            setOnClickListener {
                startActivity(
                    Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(
                            WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, TendiesWallpaperService::class.java),
                        )
                    },
                )
            }
        }, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(12) })

        progress = ProgressBar(this).apply {
            visibility = ProgressBar.GONE
        }
        container.addView(progress, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            topMargin = dp(24)
        })

        status = TextView(this).apply {
            text = "No package imported yet."
            textSize = 15f
            setTextColor(Color.rgb(244, 246, 248))
            setBackgroundColor(Color.rgb(23, 27, 34))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        container.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(24)
        })

        return ScrollView(this).apply { addView(container) }
    }

    private companion object {
        const val REQUEST_TENDIES = 1001
        const val DEFAULT_PACKAGE_ASSET = "defaults/Roxy_Migurdia_.tendies"
        const val TAG = "Motandrwall"
    }
}
