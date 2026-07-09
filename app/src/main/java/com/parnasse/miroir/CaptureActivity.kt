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
 * CaptureActivity — surface d'écriture légère (pas l'IME).
 * Utilise MiroirEngine pour les strokes/groupes/pages.
 * L'IME reste le clavier pour les autres apps.
 */
class CaptureActivity : Activity() {

    companion object {
        private const val TAG = "Miroir/Capture"
    }

    private val engine = MiroirEngine()
    private var recognizer: DigitalInkWrapper? = null
    private var captureView: CaptureSurface? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "miroir-capture-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(TAG, "=== CAPTURE ACTIVITY (unified) ===")

        recognizer = DigitalInkWrapper(this).also { it.load() }
        engine.ensureBlockDir(this, "capture", System.currentTimeMillis())
        engine.initGroupManager(this)
        engine.updateTemplateSpacing(this, resources.displayMetrics.heightPixels)

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
                engine.savePage()
                engine.goToPage(engine.currentPageIndex - 1)
                captureView?.invalidate()
            }
        })
        toolbar.addView(makeBtn("➡", Color.argb(200, 0, 80, 160)) {
            val total = engine.countPages()
            if (total > 0 && engine.currentPageIndex < total - 1) {
                engine.savePage()
                engine.goToPage(engine.currentPageIndex + 1)
                captureView?.invalidate()
            } else if (total == 0 || engine.currentPageIndex >= total - 1) {
                engine.newPage()
                captureView?.clearCanvas()
                captureView?.invalidate()
            }
        })
        // Espace
        toolbar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        toolbar.addView(makeBtn("💾", Color.argb(200, 0, 100, 50)) {
            engine.savePage()
            Toast.makeText(this, "💾 Page sauvegardée", Toast.LENGTH_SHORT).show()
        })

        root.addView(toolbar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        captureView?.initTouchHelper()
    }

    override fun onDestroy() {
        engine.savePage(); engine.closeBlock()
        captureView?.releaseTouchHelper(); recognizer?.close()
        super.onDestroy()
    }

    private fun makeBtn(text: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text; textSize = 22f; setTextColor(Color.WHITE)
            setPadding(16, 8, 16, 8); setBackgroundColor(bg)
            gravity = Gravity.CENTER; setOnClickListener { onClick() }
        }

    // ── Surface de capture ──────────────────────────────────────────
    inner class CaptureSurface(context: android.content.Context) : View(context) {
        var engine: MiroirEngine? = null
        private val strokePaint = Paint().apply {
            color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
        }
        private val templatePaint = Paint().apply {
            color = Color.argb(60, 180, 180, 200); strokeWidth = 1f; style = Paint.Style.STROKE
        }
        private var currentPath = Path()
        private var isStylusDown = false
        private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
        private var bitmap: Bitmap? = null
        private var bitmapCanvas: Canvas? = null

        fun clearCanvas() { bitmap?.eraseColor(Color.WHITE); invalidate() }

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
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
                bitmap?.eraseColor(Color.WHITE)
                engine?.updateTemplateSpacing(context, h)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isStylusDown = true; currentPath.reset()
                    currentPath.moveTo(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isStylusDown) return true
                    for (i in 0 until event.historySize) {
                        currentPath.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    currentPath.lineTo(event.x, event.y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (!isStylusDown) return true
                    isStylusDown = false
                    // Rastériser le stroke dans le bitmap
                    bitmapCanvas?.drawPath(currentPath, strokePaint)
                    // Créer StrokeRecord
                    val points = mutableListOf<Pair<Float, Float>>()
                    // Approximer les points depuis le path (simplifié)
                    val pathPoints = floatArrayOf(0f, 0f)
                    // On va utiliser une approche simplifiée : enregistrer via engine
                    val sr = StrokeRecord()
                    // Collect points from touch history
                    sr.points.add(Pair(event.x, event.y))
                    engine?.strokeRegistry?.add(sr)
                    // Schedule inference
                    scheduleInference()
                    currentPath.reset()
                    invalidate()
                }
            }
            return true
        }

        private fun scheduleInference() {
            val eng = engine ?: return
            val gm = eng.groupManager ?: return
            val groups = gm.allGroupsFull()
            if (groups.isEmpty()) return
            val lastGroup = groups.last()
            val indices = lastGroup.strokeIds.mapNotNull { eng.inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) return
            inferExecutor.submit {
                val rec = recognizer ?: return@submit
                if (!rec.isLoaded) return@submit
                val result = rec.recognize(eng.strokeRegistry.toList(), indices)
                if (!result.isNullOrBlank()) {
                    uiHandler.post {
                        val firstIdx = indices.firstOrNull() ?: return@post
                        eng.groupLabels[firstIdx] = result
                        Log.i(TAG, "Reconnu: '$result'")
                    }
                }
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Fond
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            // Template
            val eng = engine ?: return
            for (ly in eng.cachedTemplateLines) {
                canvas.drawLine(0f, ly, width.toFloat(), ly, templatePaint)
            }
            // Stroke en cours
            if (isStylusDown) {
                canvas.drawPath(currentPath, strokePaint)
            }
        }
    }
}
