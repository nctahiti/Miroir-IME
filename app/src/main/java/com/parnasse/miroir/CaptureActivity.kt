package com.parnasse.miroir

import android.app.Activity
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*

/**
 * CaptureActivity — surface d'écriture contextuelle.
 *
 * Deux modes d'invocation :
 *   MODE STANDALONE (sans extras) : bloc auto-généré, surface de capture libre.
 *   MODE CONTEXTUEL (avec extras)  : bloc/page fournis par Parnasse/Flutter.
 *
 * Extras supportés :
 *   EXTRA_BLOCK_ID   — UUID du bloc Parnasse à ouvrir
 *   EXTRA_PAGE_N     — page à afficher (défaut 0)
 *   EXTRA_MODE        — "bloc" (capture) ou "note" (mise en forme MDM)
 *   EXTRA_NOTE_ID     — note cible (mode "note")
 *   EXTRA_SCREENSHOT_PATH — fond d'écran (screenshot Flutter)
 *   EXTRA_COEUR_URL   — URL du Cœur Parnasse
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "Miroir/Capture"

        // ── Extras d'invocation ────────────────────────────────────────
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
    private var captureView: CaptureSurface? = null
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
    private var invocMode: String = "bloc"   // "bloc" | "note"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ═══ Lire le contexte d'invocation ═══
        invocBlockId = intent.getStringExtra(EXTRA_BLOCK_ID)
        invocPageN   = intent.getIntExtra(EXTRA_PAGE_N, 0)
        invocMode    = intent.getStringExtra(EXTRA_MODE) ?: "bloc"

        val isContextual = invocBlockId != null
        Log.i(TAG, "=== CAPTURE ACTIVITY === mode=$invocMode contextual=$isContextual blockId=$invocBlockId page=$invocPageN")

        recognizer = DigitalInkWrapper(this).also { it.load() }

        // ═══ Initialiser le moteur selon le contexte ═══
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

        // ═══ Construire l'interface selon le mode ═══
        when (invocMode) {
            else -> buildBlockView()  // toujours le mode capture pour l'Activity standalone
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // INFERENCE PAR GROUPE (timer)
    // ═══════════════════════════════════════════════════════════════════

    private fun runGroupInference() {
        inferenceTimerArmed = false
        val eng = engine
        val gm = eng.groupManager ?: return
        val groups = gm.allGroupsFull()
        if (groups.isEmpty()) return

        // Prendre le dernier groupe (ou celui qui a declenche le timer)
        val targetGroup = if (lastGroupId != null) {
            groups.find { it.id == lastGroupId } ?: groups.last()
        } else {
            groups.last()
        }
        val indices = targetGroup.strokeIds.mapNotNull { eng.inkStrokeIdToRegistryIndex[it] }
        if (indices.isEmpty()) return

        val rec = recognizer ?: return
        if (!rec.isLoaded) return

        inferExecutor.submit {
            val result = rec.recognize(eng.strokeRegistry.toList(), indices)
            if (!result.isNullOrBlank()) {
                uiHandler.post {
                    val firstIdx = indices.firstOrNull() ?: return@post
                    eng.groupLabels[firstIdx] = result
                    // Ancre du groupe
                    val anchor = eng.strokeRegistry.getOrNull(firstIdx)?.points?.firstOrNull()
                    if (anchor != null) {
                        eng.groupAnchor[firstIdx] = anchor
                    }
                    Log.i(TAG, "Reconnu: '$result' (groupe ${targetGroup.id.take(8)})")
                    captureView?.invalidate()
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE BLOC — surface de capture manuscrite
    // ═══════════════════════════════════════════════════════════════════

    private fun buildBlockView() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }

        captureView = CaptureSurface(this).also { cv ->
            cv.engine = engine
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

        toolbar.addView(makeBtn("✕", Color.argb(200, 150, 0, 0)) { finish() })
        toolbar.addView(makeBtn("⚙", Color.argb(180, 80, 80, 80)) {
            startActivity(android.content.Intent(this, CalibrationActivity::class.java))
        })
        toolbar.addView(makeBtn("↺", Color.argb(200, 120, 80, 0)) {
            engine.clearPage(); captureView?.clearCanvas(); captureView?.invalidate()
        })
        toolbar.addView(makeBtn("⬅", Color.argb(200, 80, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex > 0) {
                engine.goToPageFull(engine.currentPageIndex - 1)
                captureView?.invalidate()
            }
        })
        toolbar.addView(makeBtn("➡", Color.argb(200, 0, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex < total - 1) {
                engine.goToPageFull(engine.currentPageIndex + 1)
                captureView?.invalidate()
            } else if (total == 0 || engine.currentPageIndex >= total - 1) {
                engine.newPage()
                captureView?.clearCanvas()
                captureView?.invalidate()
            }
        })
        // Espace pousseur
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(makeBtn("💾", Color.argb(200, 0, 100, 50)) {
            engine.savePageFull()
            Toast.makeText(this, "💾 Page sauvegardée", Toast.LENGTH_SHORT).show()
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

    // ═══════════════════════════════════════════════════════════════════
    // SURFACE DE CAPTURE
    // ═══════════════════════════════════════════════════════════════════

    inner class CaptureSurface(context: android.content.Context) : View(context) {
        var engine: MiroirEngine? = null
        private val templatePaint = Paint().apply {
            color = Color.argb(60, 180, 180, 200); strokeWidth = 1f; style = Paint.Style.STROKE
        }
        private val labelPaint = Paint().apply {
            color = Color.argb(180, 80, 80, 180); textSize = 28f; isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
        private var isStylusDown = false
        private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null

        fun clearCanvas() {
            engine?.bitmap?.eraseColor(Color.WHITE)
            engine?.bitmapCanvas?.drawColor(Color.WHITE)
            invalidate()
        }

        fun initTouchHelper() {
            if (touchHelper != null) return
            try {
                touchHelper = com.onyx.android.sdk.pen.TouchHelper.create(this,
                    com.onyx.android.sdk.pen.TouchHelper.FEATURE_APP_TOUCH_RENDER,
                    object : com.onyx.android.sdk.pen.RawInputCallback() {
                        override fun onBeginRawDrawing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                        override fun onRawDrawingTouchPointMoveReceived(p0: com.onyx.android.sdk.data.note.TouchPoint?) {}
                        override fun onRawDrawingTouchPointListReceived(p0: com.onyx.android.sdk.pen.data.TouchPointList?) {}
                        override fun onEndRawDrawing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                        override fun onBeginRawErasing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                        override fun onEndRawErasing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                        override fun onRawErasingTouchPointMoveReceived(p0: com.onyx.android.sdk.data.note.TouchPoint) {}
                        override fun onRawErasingTouchPointListReceived(p0: com.onyx.android.sdk.pen.data.TouchPointList) {}
                    })
                touchHelper!!.setRawInputReaderEnable(true)
                touchHelper!!.setBrushRawDrawingEnabled(true)
                touchHelper!!.setRawDrawingEnabled(true)
                touchHelper!!.openRawDrawing()
                touchHelper!!.setPostInputEvent(true)
                com.onyx.android.sdk.api.device.epd.EpdController.setScreenHandWritingPenState(this, 1)
                com.onyx.android.sdk.api.device.epd.EpdController.setViewDefaultUpdateMode(this, com.onyx.android.sdk.api.device.epd.UpdateMode.DU)
            } catch (e: Exception) {
                touchHelper = null
                Log.w(TAG, "TouchHelper: ${e.message}")
            }
        }

        fun releaseTouchHelper() {
            try {
                touchHelper?.closeRawDrawing()
                touchHelper?.setRawDrawingEnabled(false)
                com.onyx.android.sdk.api.device.epd.EpdController.setViewDefaultUpdateMode(this, com.onyx.android.sdk.api.device.epd.UpdateMode.GU)
                com.onyx.android.sdk.api.device.epd.EpdController.setScreenHandWritingPenState(this, 0)
            } catch (_: Exception) {}
            touchHelper = null
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                engine?.bitmap?.recycle()
                engine?.bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                engine?.bitmapCanvas = Canvas(engine?.bitmap!!)
                engine?.bitmap?.eraseColor(Color.WHITE)
                engine?.updateTemplateSpacing(context, h)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
            val eng = engine ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isStylusDown = true
                    eng.beginStroke(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isStylusDown) return true
                    // Capturer tous les points historiques + le point courant
                    for (i in 0 until event.historySize) {
                        eng.addStrokePoint(event.getHistoricalX(i), event.getHistoricalY(i),
                            event.getHistoricalPressure(i))
                    }
                    eng.addStrokePoint(event.x, event.y, event.pressure)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (!isStylusDown) return true
                    isStylusDown = false
                    // Dernier point
                    for (i in 0 until event.historySize) {
                        eng.addStrokePoint(event.getHistoricalX(i), event.getHistoricalY(i),
                            event.getHistoricalPressure(i))
                    }
                    eng.addStrokePoint(event.x, event.y, event.pressure)
                    val ri = eng.endStroke()
                    if (ri >= 0) {
                        scheduleInference(ri)
                    }
                    invalidate()
                }
            }
            return true
        }

        private fun scheduleInference(strokeRegistryIndex: Int) {
            val eng = engine ?: return
            val gm = eng.groupManager ?: return
            val groups = gm.allGroupsFull()
            if (groups.isEmpty()) return
            val lastGroup = groups.last()

            // Rearmer le timer : annuler l'ancien, programmer le nouveau
            uiHandler.removeCallbacks(inferenceRunnable)
            inferenceTimerArmed = true
            lastGroupId = lastGroup.id
            uiHandler.postDelayed(inferenceRunnable, 1500L)  // 1.5s apres le dernier stroke
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val eng = engine ?: return
            // Fond
            canvas.drawColor(Color.WHITE)
            // Bitmap rasterise
            eng.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            // Template
            for (ly in eng.cachedTemplateLines) {
                canvas.drawLine(0f, ly, width.toFloat(), ly, templatePaint)
            }
            // Labels
            for ((firstIdx, label) in eng.groupLabels) {
                val anchor = eng.groupAnchor[firstIdx] ?: continue
                canvas.drawText(label, anchor.first, anchor.second - 12f, labelPaint)
            }
            // Stroke en cours
            if (isStylusDown && eng.currentStrokeRecord != null) {
                val paint = Paint().apply {
                    color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
                }
                canvas.drawPath(eng.currentPath, paint)
            }
        }
    }
}
