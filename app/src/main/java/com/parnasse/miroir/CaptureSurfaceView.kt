package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * CaptureSurfaceView — Vue de capture et rendu autonome.
 *
 * Utilisee par le standalone (CaptureActivity) et potentiellement par l'IME.
 * Reference un MiroirEngine pour toutes les donnees (strokes, groupes, bitmap).
 *
 * Rendu (ordre z) :
 *   1. Fond blanc
 *   2. Blobs des groupes (zones d'absorption elliptiques)
 *   3. Bitmap rasterise (strokes scelles)
 *   4. Template (lignes MDM)
 *   5. Labels reconnus
 *   6. Stroke en cours (currentPath)
 */
class CaptureSurfaceView(context: Context, val engine: MiroirEngine) : View(context) {

    companion object {
        private const val TAG = "Miroir/CaptureView"
    }

    // ── Pinceaux ──────────────────────────────────────────────────────
    private val strokePaint = Paint().apply {
        color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    private val blobPaint = Paint().apply {
        color = Color.argb(25, 100, 150, 255); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val blobBorderPaint = Paint().apply {
        color = Color.argb(80, 100, 130, 200); style = Paint.Style.STROKE
        strokeWidth = 1.5f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(200, 80, 80, 180); textSize = 30f; isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // ── État ──────────────────────────────────────────────────────────
    private var isStylusDown = false
    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    var showLabels: Boolean = true

    // ── Callback d'inference ──────────────────────────────────────────
    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH HELPER ONYX
    // ═══════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            engine.bitmap?.recycle()
            engine.bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            engine.bitmapCanvas = Canvas(engine.bitmap!!)
            engine.bitmap?.eraseColor(Color.WHITE)
            engine.updateTemplateSpacing(context, h)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH
    // ═══════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isStylusDown = true
                engine.beginStroke(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isStylusDown) return true
                for (i in 0 until event.historySize) {
                    engine.addStrokePoint(
                        event.getHistoricalX(i), event.getHistoricalY(i),
                        event.getHistoricalPressure(i)
                    )
                }
                engine.addStrokePoint(event.x, event.y, event.pressure)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!isStylusDown) return true
                isStylusDown = false
                for (i in 0 until event.historySize) {
                    engine.addStrokePoint(
                        event.getHistoricalX(i), event.getHistoricalY(i),
                        event.getHistoricalPressure(i)
                    )
                }
                engine.addStrokePoint(event.x, event.y, event.pressure)
                val ri = engine.endStroke()
                if (ri >= 0) {
                    onStrokeFinished?.invoke(ri)
                }
                invalidate()
            }
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // RENDU
    // ═══════════════════════════════════════════════════════════════════

    private var drawCount = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCount++

        // 1. Fond
        canvas.drawColor(Color.WHITE)

        // 2. Blobs (zones d'absorption des groupes)
        val gm = engine.groupManager
        if (gm != null) {
            // Groupe selectionne (temoignage visuel)
            val selected = gm.groupsInState(GroupState.SELECTED).firstOrNull()
            if (selected != null) {
                engine.groupBlobs[selected.id]?.let { canvas.drawPath(it.path, blobPaint) }
                engine.groupBlobs[selected.id]?.let { canvas.drawPath(it.path, blobBorderPaint) }
            }
            // Tous les groupes actifs (blobs discrets)
            for (g in gm.allGroupsFull()) {
                if (g.id == selected?.id) continue  // deja dessine
                engine.groupBlobs[g.id]?.let { blob ->
                    val p = Paint().apply {
                        color = Color.argb(12, 100, 150, 255)
                        style = Paint.Style.FILL; isAntiAlias = true
                    }
                    canvas.drawPath(blob.path, p)
                }
            }
        }

        // 3. Bitmap rasterise (strokes scelles)
        engine.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 4. Template (lignes MDM)
        for (ly in engine.cachedTemplateLines) {
            canvas.drawLine(0f, ly, width.toFloat(), ly, Template.GUIDE_PAINT)
        }

        // 5. Labels reconnus
        if (showLabels) {
            for ((firstIdx, label) in engine.groupLabels) {
                val anchor = engine.groupAnchor[firstIdx] ?: continue
                // Fond semi-transparent pour lisibilite
                val textW = labelPaint.measureText(label)
                val bgRect = android.graphics.RectF(
                    anchor.first - textW / 2f - 6f,
                    anchor.second - 36f,
                    anchor.first + textW / 2f + 6f,
                    anchor.second - 4f
                )
                canvas.drawRoundRect(bgRect, 6f, 6f,
                    Paint().apply { color = Color.argb(180, 255, 255, 255); style = Paint.Style.FILL }
                )
                canvas.drawText(label, anchor.first, anchor.second - 10f, labelPaint)
            }
        }

        // 6. Stroke en cours
        if (isStylusDown && engine.currentStrokeRecord != null) {
            canvas.drawPath(engine.currentPath, strokePaint)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    fun clearCanvas() {
        engine.bitmap?.eraseColor(Color.WHITE)
        engine.bitmapCanvas?.drawColor(Color.WHITE)
        invalidate()
    }
}
