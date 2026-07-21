package com.parnasse.miroir

import android.app.Activity
import android.content.Intent
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
    private var pageCounter: TextView? = null
    private var eyeButton: TextView? = null
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
            engine.openBlockDir(this, "standalone")
        }
        engine.initGroupManager(this)
        engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)

        when (invocMode) {
            else -> buildBlockView()
        }

        if (isContextual && invocPageN > 0 && engine.countPages() > invocPageN) {
            engine.goToPageFull(invocPageN)
        } else if (engine.countPages() > 0) {
            engine.currentPageIndex = engine.countPages() - 1
            engine.loadPageFull()
        }
        updatePageCounter()
        captureView?.post { captureView?.invalidate() }
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
            cv.onReturnToWriting = { returnToWriting() }
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

        captureView?.fontaineOverlay = fontaineOverlay

        // ═══ BARRE D'OUTILS ═══
        // [✕]    [◄ 1/5 ►]    [+]
        //        [👁 ouvert]
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 38, 12, 6)
            setBackgroundColor(Color.argb(220, 255, 255, 255))
        }

        // ── Bouton ✕ (fermer) ──
        toolbar.addView(makeToolBtn("\u2715", Color.argb(200, 150, 0, 0)) { view ->
            showCloseMenu(view)
        })

        // ── Espace pousseur gauche ──
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.15f))

        // ── ◄ Navigation gauche ──
        toolbar.addView(makeToolBtn("\u25C0", Color.argb(200, 80, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex > 0) {
                engine.goToPageFull(engine.currentPageIndex - 1)
                captureView?.invalidate()
                updatePageCounter()
            }
        })

        // ── Compteur de page ──
        pageCounter = TextView(this).apply {
            text = pageLabel()
            textSize = 28f; setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER; setPadding(8, 0, 8, 0)
        }
        toolbar.addView(pageCounter)

        // ── ► Navigation droite ──
        toolbar.addView(makeToolBtn("\u25B6", Color.argb(200, 0, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex < total - 1) {
                engine.goToPageFull(engine.currentPageIndex + 1)
                captureView?.invalidate()
                updatePageCounter()
            } else if (total == 0 || engine.currentPageIndex >= total - 1) {
                engine.newPage()
                captureView?.clearCanvas()
                updatePageCounter()
            }
        })

        // ── Espace pousseur droit ──
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 0.15f))

        // ── Bouton + (nouvelle page) ──
        toolbar.addView(makeToolBtn("+", Color.argb(200, 0, 100, 50)) { view ->
            showPlusMenu(view)
        })

        root.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        // ═══ LIGNE 2 : Œil de calibration ═══
        val eyeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 4)
        }
        eyeButton = TextView(this).apply {
            text = if (captureView?.showLabels != false) "\uD83D\uDC41" else "\uD83D\uDC41\u200D\uD83D\uDDE8"  // 👁 / 👁‍🗨
            textSize = 24f
            setPadding(16, 4, 16, 4)
            setOnClickListener {
                val cv = captureView ?: return@setOnClickListener
                cv.showLabels = !cv.showLabels
                text = if (cv.showLabels) "\uD83D\uDC41" else "\uD83D\uDC41\u200D\uD83D\uDDE8"
                cv.invalidate()
            }
        }
        eyeBar.addView(eyeButton)
        root.addView(eyeBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            topMargin = 78  // en dessous de la toolbar
        })

        setContentView(root)
    }

    // ═══════════════════════════════════════════════════════════════════
    // MENUS FLOTTANTS
    // ═══════════════════════════════════════════════════════════════════

    private fun showCloseMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Vider page").setOnMenuItemClickListener {
            engine.clearPage(); captureView?.clearCanvas(); updatePageCounter(); true
        }
        popup.menu.add("Paramètres").setOnMenuItemClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java)); true
        }
        popup.menu.add("Fermer le miroir").setOnMenuItemClickListener {
            engine.savePageFull(); engine.closeBlock()
            val newBlockId = java.util.UUID.randomUUID().toString()
            engine.openBlockDir(this, newBlockId)
            engine.initGroupManager(this)
            engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.show()
    }

    private fun showPlusMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Nouvelle page (après)").setOnMenuItemClickListener {
            val total = engine.countPages()
            if (total == 0 || engine.currentPageIndex >= total - 1) {
                engine.newPage()
            } else {
                // Insérer après la page courante
                engine.savePageFull()
                val bd = engine.blockDir ?: return@setOnMenuItemClickListener true
                val insertAt = engine.currentPageIndex + 1
                for (i in total - 1 downTo insertAt) {
                    java.io.File(bd, "page_$i").renameTo(java.io.File(bd, "page_${i + 1}"))
                }
                engine.clearPage()
                engine.currentPageIndex = insertAt
            }
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.menu.add("Nouvelle page (début)").setOnMenuItemClickListener {
            engine.savePageFull()
            val bd = engine.blockDir ?: return@setOnMenuItemClickListener true
            val total = engine.countPages()
            for (i in total - 1 downTo 0) {
                java.io.File(bd, "page_$i").renameTo(java.io.File(bd, "page_${i + 1}"))
            }
            engine.clearPage()
            engine.currentPageIndex = 0
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.menu.add("Nouvelle page (fin)").setOnMenuItemClickListener {
            engine.savePageFull()
            engine.newPage()
            captureView?.clearCanvas()
            updatePageCounter()
            captureView?.invalidate()
            true
        }
        popup.show()
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
    }

    override fun onDestroy() {
        engine.savePageFull(); engine.closeBlock()
        recognizer?.close()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun makeToolBtn(text: String, bg: Int, onClick: (View) -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 36f; setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10); setBackgroundColor(bg)
            gravity = Gravity.CENTER
            setOnClickListener { onClick(this) }
        }

    private fun pageLabel(): String {
        val total = engine.countPages()
        val current = if (total > 0) engine.currentPageIndex + 1 else 0
        return "$current / $total"
    }

    private fun updatePageCounter() {
        pageCounter?.text = pageLabel()
    }

    private fun scheduleInference() {
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.postDelayed(inferenceRunnable, 350L)
        uiHandler.removeCallbacks(displayRefreshRunnable)
        uiHandler.postDelayed(displayRefreshRunnable, 700L)
    }

    /** Rafraîchit l'affichage : désactive la fontaine, synchronise le bitmap, réactive. */
    private fun refreshDisplay() {
        if (fontaineOverlay?.modeInteraction == true) return
        engine.groupManager?.evictInactive()
        // 🔬 SÉMATOGRAMME CACHE
        val gm = engine.groupManager
        val cacheGroups = gm?.cacheSize() ?: 0
        val allGroups = gm?.allGroupsFull()?.size ?: 0
        val blobs = engine.groupBlobs.size
        val labels = engine.groupLabels.size
        val strokes = engine.strokeRegistry.count { !it.isDeleted && it.points.isNotEmpty() }
        val totalStrokes = engine.strokeRegistry.size
        val inkMappings = engine.inkStrokeIdToRegistryIndex.size
        Log.i(TAG, "📊 CACHE refresh: strokes=$strokes/$totalStrokes inkMap=$inkMappings " +
            "blobs=$blobs labels=$labels groupes=cache$cacheGroups/all$allGroups")
        fontaineOverlay?.desactiver()
        captureView?.invalidate()
        fontaineOverlay?.activer()
        Log.d(TAG, "Display refresh — éviction groupes inactifs")
    }

    private fun cancelTimers() {
        uiHandler.removeCallbacks(inferenceRunnable)
        uiHandler.removeCallbacks(displayRefreshRunnable)
    }

    private fun handleLongPress(x: Float, y: Float) {
        cancelTimers()
        captureView?.selectGroupAt(x, y)
        fontaineOverlay?.modeInteraction = true
        fontaineOverlay?.desactiver()
        captureView?.armLongPressGesture(x, y)
        fontaineOverlay?.touchForwardTarget = captureView
        Log.d(TAG, "Long-press → bascule franche, blob visible, attente geste")
    }

    private fun returnToWriting() {
        fontaineOverlay?.modeInteraction = false
        fontaineOverlay?.touchForwardTarget = null
        fontaineOverlay?.effacerSurface()
        captureView?.invalidate()
        fontaineOverlay?.reactiver()
        Log.d(TAG, "Retour écriture — groupe SELECTED préservé, fontaine réactivée")
    }
}
