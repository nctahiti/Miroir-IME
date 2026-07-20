package com.parnasse.miroir

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * CaptureActivity — surface d'ecriture contextuelle.
 *
 * Deux modes d'invocation :
 *   MODE STANDALONE (sans extras) : bloc auto-genere, surface de capture libre.
 *   MODE CONTEXTUEL (avec extras)  : bloc/page fournis par Parnasse/Flutter.
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "Miroir/Capture"

        const val EXTRA_LIBRARY_ID      = "library_id"
        const val EXTRA_SHELF_ID        = "shelf_id"
        const val EXTRA_BLOCK_ID        = "block_id"
        const val EXTRA_NOTE_ID         = "note_id"
        const val EXTRA_PAGE_N          = "page_n"
        const val EXTRA_MODE            = "mode"
        const val EXTRA_OFFSET_LEFT     = "offset_left"
        const val EXTRA_OFFSET_TOP      = "offset_top"
        const val EXTRA_SCREENSHOT_PATH = "screenshot_path"
        const val EXTRA_SESSION_TEXT    = "session_text"
        const val EXTRA_SESSION_ID      = "session_id"
        const val EXTRA_SAMPLE_COUNT    = "sample_count"
        const val EXTRA_TOTAL_COUNT     = "total_count"
        const val EXTRA_PREDICTION      = "prediction"
        const val EXTRA_FLIP_LAYOUT     = "flip_layout"
        const val EXTRA_COEUR_URL       = "coeur_url"
    }

    private val engine = MiroirEngine()
    private var recognizer: DigitalInkWrapper? = null
    private var captureView: CaptureSurfaceView? = null
    private var fontaineOverlay: FontaineOverlay? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "miroir-capture-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ── Timers ──────────────────────────────────────────────────────────
    private val inferenceRunnable = Runnable { runGroupInference() }
    private val displayRefreshRunnable = Runnable { refreshDisplay() }

    // ── Contexte d'invocation ──────────────────────────────────────────
    private var invocBlockId: String? = null
    private var invocPageN: Int = 0
    private var invocMode: String = "bloc"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        invocBlockId = intent.getStringExtra(EXTRA_BLOCK_ID)
        invocPageN   = intent.getIntExtra(EXTRA_PAGE_N, 0)
        invocMode    = intent.getStringExtra(EXTRA_MODE) ?: "bloc"

        val isContextual = invocBlockId != null
        Log.i(TAG, "=== CAPTURE ACTIVITY === mode=$invocMode contextual=$isContextual blockId=$invocBlockId page=$invocPageN")

        recognizer = DigitalInkWrapper(this).also { it.load() }

        if (isContextual) {
            engine.openBlockDir(this, invocBlockId!!)
        } else {
            engine.ensureBlockDir(this, "capture", System.currentTimeMillis())
        }
        engine.initGroupManager(this)
        engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)

        // Charger la page demandee
        if (isContextual && invocPageN > 0 && engine.countPages() > invocPageN) {
            engine.goToPageFull(invocPageN)
        } else if (engine.countPages() > 0) {
            engine.loadPageFull()
        }

        when (invocMode) {
            else -> buildBlockView()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INFERENCE PAR GROUPE (timer)
    // ═══════════════════════════════════════════════════════════════════

    private fun runGroupInference() {
        val gm = engine.groupManager ?: return
        val groups = gm.allGroupsFull()
        if (groups.isEmpty()) return
        val rec = recognizer ?: return
        if (!rec.isLoaded) return

        // Inférer tous les groupes sans label (clôturés OU actif)
        // Capturer un snapshot sur le thread UI pour éviter les race conditions
        val registrySnapshot = engine.strokeRegistry.toList()
        for (group in groups) {
            val firstIdx = group.strokeIds.firstOrNull()
                ?.let { engine.inkStrokeIdToRegistryIndex[it] } ?: continue
            if (engine.groupLabels.containsKey(firstIdx)) continue

            val indices = group.strokeIds.mapNotNull { engine.inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) continue

            val groupId = group.id
            inferExecutor.submit {
                val result = rec.recognize(registrySnapshot, indices)
                if (!result.isNullOrBlank()) {
                    uiHandler.post {
                        if (!engine.groupLabels.containsKey(firstIdx)) {
                            engine.groupLabels[firstIdx] = result
                            val anchor = registrySnapshot.getOrNull(firstIdx)?.points?.firstOrNull()
                            if (anchor != null) engine.groupAnchor[firstIdx] = anchor
                            Log.i(TAG, "Reconnu: '$result' (groupe ${groupId.take(8)}, ${indices.size} strokes)")
                        }
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE BLOC
    // ═══════════════════════════════════════════════════════════════════

    private fun buildBlockView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        // Couche 1 : View standard — blobs, labels, template, strokes
        captureView = CaptureSurfaceView(this, engine).also { cv ->
            root.addView(cv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // Couche 2 : SurfaceView FONTAINE — capture + rendu plume
        fontaineOverlay = FontaineOverlay(this, engine).also { fo ->
            fo.onStrokeFinished = { _ -> scheduleInference() }
            fo.onStrokeBegin = { cancelTimers() }
            fo.onLongPressDetected = { x, y -> handleLongPress(x, y) }
            root.addView(fo, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

        // Pont : la View standard peut désactiver la fontaine en mode interaction
        captureView?.fontaineOverlay = fontaineOverlay

        // Barre d'outils flottante
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 38, 12, 6)
            setBackgroundColor(Color.argb(220, 255, 255, 255))
        }

        toolbar.addView(makeBtn("\u2715", Color.argb(200, 150, 0, 0)) { finish() })
        toolbar.addView(makeBtn("\u21BA", Color.argb(200, 120, 80, 0)) {
            engine.clearPage(); captureView?.clearCanvas()
        })
        toolbar.addView(makeBtn("\u2B05", Color.argb(200, 80, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex > 0) {
                engine.goToPageFull(engine.currentPageIndex - 1)
                captureView?.invalidate()
            }
        })
        toolbar.addView(makeBtn("\u27A1", Color.argb(200, 0, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex < total - 1) {
                engine.goToPageFull(engine.currentPageIndex + 1)
                captureView?.invalidate()
            } else if (total == 0 || engine.currentPageIndex >= total - 1) {
                engine.newPage()
                captureView?.clearCanvas()
            }
        })
        // Espace pousseur
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        toolbar.addView(makeBtn("\uD83D\uDCBE", Color.argb(200, 0, 100, 50)) {
            engine.savePageFull()
            Toast.makeText(this, "\uD83D\uDCBE Page sauvegardee", Toast.LENGTH_SHORT).show()
        })

        root.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        setContentView(root)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        // TouchHelper de CaptureSurfaceView désactivé — la FontaineOverlay capture tout
    }

    override fun onDestroy() {
        engine.savePageFull(); engine.closeBlock()
        recognizer?.close()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun makeBtn(text: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8); setBackgroundColor(bg)
            gravity = Gravity.CENTER; setOnClickListener { onClick() }
        }

    private fun scheduleInference() {
        // Inférence : 350ms après le dernier stroke
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.postDelayed(inferenceRunnable, 350L)

        // Affichage : 700ms après le dernier stroke
        uiHandler.removeCallbacks(displayRefreshRunnable)
        uiHandler.postDelayed(displayRefreshRunnable, 700L)
    }

    /** Rafraîchit l'affichage : désactive la fontaine, affiche les labels, réactive. */
    private fun refreshDisplay() {
        fontaineOverlay?.desactiver()
        captureView?.invalidate()
        fontaineOverlay?.activer()
        Log.d(TAG, "Display refresh")
    }

    /** Annule les timers (appelé au début de chaque stroke). */
    private fun cancelTimers() {
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.removeCallbacks(displayRefreshRunnable)
    }

    /** Long-press détecté par la fontaine → passer en mode interaction. */
    private fun handleLongPress(x: Float, y: Float) {
        // 1. Passer en mode interaction (selectGroup fera le desactiver())
        fontaineOverlay?.modeInteraction = true
        // 2. Chercher un blob sous le stylet et le sélectionner
        captureView?.selectGroupAt(x, y)
        Log.d(TAG, "Long-press → mode interaction à ($x, $y)")
    }
}
