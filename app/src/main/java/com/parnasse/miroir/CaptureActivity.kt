package com.parnasse.miroir

import android.app.Activity
import android.app.AlertDialog
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
    private val uiHandler = Handler(Looper.getMainLooper())
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "miroir-capture-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ── Timer d'inference par groupe ────────────────────────────────────
    private val inferenceRunnable = Runnable { runGroupInference() }
    private var inferenceTimerArmed = false
    private var lastGroupId: String? = null

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
        inferenceTimerArmed = false
        val gm = engine.groupManager ?: return
        val groups = gm.allGroupsFull()
        if (groups.isEmpty()) return

        val targetGroup = if (lastGroupId != null) {
            groups.find { it.id == lastGroupId } ?: groups.last()
        } else {
            groups.last()
        }
        val indices = targetGroup.strokeIds.mapNotNull { engine.inkStrokeIdToRegistryIndex[it] }
        if (indices.isEmpty()) return

        val rec = recognizer ?: return
        if (!rec.isLoaded) return

        inferExecutor.submit {
            val result = rec.recognize(engine.strokeRegistry.toList(), indices)
            if (!result.isNullOrBlank()) {
                uiHandler.post {
                    val firstIdx = indices.firstOrNull() ?: return@post
                    engine.groupLabels[firstIdx] = result
                    val anchor = engine.strokeRegistry.getOrNull(firstIdx)?.points?.firstOrNull()
                    if (anchor != null) {
                        engine.groupAnchor[firstIdx] = anchor
                    }
                    Log.i(TAG, "Reconnu: '$result' (groupe ${targetGroup.id.take(8)})")
                    captureView?.invalidate()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE BLOC
    // ═══════════════════════════════════════════════════════════════════

    private fun buildBlockView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        captureView = CaptureSurfaceView(this, engine).also { cv ->
            cv.onStrokeFinished = { _ -> scheduleInference() }
            cv.onGroupSelected = { gid, label -> onGroupSelected(gid, label) }
            cv.onGroupDeselected = { onGroupDeselected() }
            root.addView(cv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
        }

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

        correctionBtn = makeBtn("\u270E", Color.argb(180, 80, 80, 80)) {
            showCorrectionPopup()
        }
        deleteBtn = makeBtn("\uD83D\uDDD1", Color.argb(180, 180, 60, 40)) {
            captureView?.deleteSelectedGroup()
        }
        toolbar.addView(correctionBtn)
        toolbar.addView(deleteBtn)

        toolbar.addView(makeBtn("\uD83D\uDCBE", Color.argb(200, 0, 100, 50)) {
            engine.savePageFull()
            Toast.makeText(this, "\uD83D\uDCBE Page sauvegardee", Toast.LENGTH_SHORT).show()
        })

        root.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        setContentView(root)
    }

    // ── État d'edition ────────────────────────────────────────────────
    private var correctionBtn: TextView? = null
    private var deleteBtn: TextView? = null
    private var selectedGroupLabel: String? = null

    private fun onGroupSelected(gid: String, label: String?) {
        selectedGroupLabel = label
        correctionBtn?.setBackgroundColor(Color.argb(200, 80, 140, 255))
        deleteBtn?.setBackgroundColor(Color.argb(200, 220, 60, 40))
    }

    private fun onGroupDeselected() {
        selectedGroupLabel = null
        correctionBtn?.setBackgroundColor(Color.argb(180, 80, 80, 80))
        deleteBtn?.setBackgroundColor(Color.argb(180, 180, 60, 40))
    }

    private fun showCorrectionPopup() {
        val currentLabel = selectedGroupLabel ?: ""
        val input = EditText(this).apply {
            setText(currentLabel)
            setSelection(currentLabel.length)
            textSize = 20f
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Corriger le label")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                captureView?.correctSelectedLabel(input.text.toString().trim())
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onResume() {
        super.onResume()
        captureView?.initTouchHelper()
    }

    override fun onDestroy() {
        engine.savePageFull(); engine.closeBlock()
        captureView?.releaseTouchHelper(); recognizer?.close()
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
        val gm = engine.groupManager ?: return
        val groups = gm.allGroupsFull()
        if (groups.isEmpty()) return
        val lastGroup = groups.last()

        uiHandler.removeCallbacks(inferenceRunnable)
        inferenceTimerArmed = true
        lastGroupId = lastGroup.id
        uiHandler.postDelayed(inferenceRunnable, 1500L)
    }
}
