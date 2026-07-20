package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * CaptureSurfaceView — Vue de capture et rendu autonome.
 *
 * Utilisee par le standalone (CaptureActivity).
 * Reference un MiroirEngine pour toutes les donnees.
 *
 * Rendu (ordre z) :
 *   1. Fond blanc
 *   2. Blobs des groupes (zones d'absorption elliptiques)
 *   3. Bitmap rasterise (strokes scelles)
 *   4. Template (lignes MDM)
 *   5. Labels reconnus (tries par ligne + X)
 *   6. Stroke en cours (currentPath)
 *
 * Edition :
 *   - Tap sur un blob/label → selection du groupe
 *   - Double-tap ou bouton → popup de correction
 */
class CaptureSurfaceView(context: Context, val engine: MiroirEngine) : View(context) {

    companion object {
        private const val TAG = "Miroir/CaptureView"
        private const val TAP_THRESHOLD_PX = 20f  // deplacement max pour un tap
        private const val HIT_RADIUS = 60f         // rayon de hit-test autour des ancres
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
    private val selectedBlobPaint = Paint().apply {
        color = Color.argb(60, 80, 160, 255); style = Paint.Style.FILL; isAntiAlias = true
    }
    private val selectedBlobBorderPaint = Paint().apply {
        color = Color.argb(200, 60, 140, 255); style = Paint.Style.STROKE
        strokeWidth = 2.5f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(200, 80, 80, 180); textSize = 30f; isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }
    private val selectedLabelPaint = Paint().apply {
        color = Color.argb(255, 40, 100, 220); textSize = 30f; isAntiAlias = true
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    // ── État ──────────────────────────────────────────────────────────
    private var isStylusDown = false
    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    var showLabels: Boolean = true
    private var tapStartX = 0f
    private var tapStartY = 0f
    private var selectedGroupId: String? = null

    // ── Callbacks ─────────────────────────────────────────────────────
    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null
    var onGroupSelected: ((groupId: String, label: String?) -> Unit)? = null
    var onGroupDeselected: (() -> Unit)? = null

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
                tapStartX = event.x; tapStartY = event.y
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
                    // Vrai stroke (2+ points)
                    onStrokeFinished?.invoke(ri)
                } else {
                    // Tap (0-1 point) → hit-test sur les groupes
                    val dx = abs(event.x - tapStartX)
                    val dy = abs(event.y - tapStartY)
                    if (dx < TAP_THRESHOLD_PX && dy < TAP_THRESHOLD_PX) {
                        handleTap(event.x, event.y)
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val gm = engine.groupManager ?: return

        // 1. Hit-test : blob (path)
        for ((gid, blob) in engine.groupBlobs) {
            if ((blob.bounds as android.graphics.RectF).contains(x, y)) {
                selectGroup(gid)
                return
            }
        }

        // 2. Hit-test : proximite d'une ancre de label
        for ((firstIdx, anchor) in engine.groupAnchor) {
            val adx = abs(x - anchor.first)
            val ady = abs(y - anchor.second)
            if (adx < HIT_RADIUS && ady < HIT_RADIUS) {
                // Trouver le groupe correspondant
                for (g in gm.allGroupsFull()) {
                    val firstSid = g.strokeIds.firstOrNull() ?: continue
                    val firstRI = engine.inkStrokeIdToRegistryIndex[firstSid]
                    if (firstRI == firstIdx) {
                        selectGroup(g.id)
                        return
                    }
                }
            }
        }

        // 3. Clic dans le vide → deselect
        if (selectedGroupId != null) {
            deselectGroup()
        }
    }

    private fun selectGroup(gid: String) {
        val gm = engine.groupManager ?: return
        // Deselectionner l'ancien
        if (selectedGroupId != null) {
            gm.deselectGroup(selectedGroupId!!)
        }
        selectedGroupId = gid
        gm.selectGroup(gid)

        // Trouver le label associe
        val group = gm.allGroupsFull().find { it.id == gid }
        val firstSid = group?.strokeIds?.firstOrNull()
        val firstRI = firstSid?.let { engine.inkStrokeIdToRegistryIndex[it] }
        val label = firstRI?.let { engine.groupLabels[it] }

        Log.i(TAG, "Groupe selectionne: ${gid.take(8)} label='$label'")
        onGroupSelected?.invoke(gid, label)
        invalidate()
    }

    fun deselectGroup() {
        val gm = engine.groupManager
        selectedGroupId?.let { gm?.deselectGroup(it) }
        selectedGroupId = null
        onGroupDeselected?.invoke()
        invalidate()
    }

    /** Corrige le label du groupe selectionne. */
    fun correctSelectedLabel(newLabel: String) {
        val gid = selectedGroupId ?: return
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return
        val firstSid = group.strokeIds.firstOrNull() ?: return
        val firstRI = engine.inkStrokeIdToRegistryIndex[firstSid] ?: return
        engine.groupLabels[firstRI] = newLabel
        Log.i(TAG, "Label corrige: '$newLabel' (groupe ${gid.take(8)})")
        invalidate()
    }

    /** Efface le groupe selectionne (marque les strokes comme deleted). */
    fun deleteSelectedGroup() {
        val gid = selectedGroupId ?: return
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return

        // Marquer les strokes comme deleted
        for (sid in group.strokeIds) {
            val ri = engine.inkStrokeIdToRegistryIndex[sid]
            if (ri != null && ri < engine.strokeRegistry.size) {
                engine.strokeRegistry[ri].isDeleted = true
            }
        }

        // Nettoyer le groupe et les metadonnees
        gm.removeGroup(gid)
        engine.groupBlobs.remove(gid)
        val firstSid = group.strokeIds.firstOrNull()
        val firstRI = firstSid?.let { engine.inkStrokeIdToRegistryIndex[it] }
        if (firstRI != null) {
            engine.groupLabels.remove(firstRI)
            engine.groupAnchor.remove(firstRI)
        }

        deselectGroup()
        Log.i(TAG, "Groupe efface: ${gid.take(8)}")
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
            for (g in gm.allGroupsFull()) {
                val blob = engine.groupBlobs[g.id] ?: continue
                val isSelected = g.id == selectedGroupId
                if (isSelected) {
                    canvas.drawPath(blob.path, selectedBlobPaint)
                    canvas.drawPath(blob.path, selectedBlobBorderPaint)
                } else {
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

        // 5. Labels reconnus — tries par ligne puis X (ordre de lecture)
        if (showLabels && engine.groupLabels.isNotEmpty()) {
            data class LabelEntry(val firstIdx: Int, val label: String, val anchor: Pair<Float, Float>, val snapY: Float, val isSelected: Boolean)
            val entries = mutableListOf<LabelEntry>()
            for ((firstIdx, label) in engine.groupLabels) {
                val anchor = engine.groupAnchor[firstIdx] ?: continue
                val snapY = engine.snapToLine(anchor.second)
                // Verifier si ce label appartient au groupe selectionne
                var isSel = false
                if (selectedGroupId != null && gm != null) {
                    val selGroup = gm.allGroupsFull().find { it.id == selectedGroupId }
                    val selFirstSid = selGroup?.strokeIds?.firstOrNull()
                    val selFirstRI = selFirstSid?.let { engine.inkStrokeIdToRegistryIndex[it] }
                    isSel = selFirstRI == firstIdx
                }
                entries.add(LabelEntry(firstIdx, label, anchor, snapY, isSel))
            }
            entries.sortWith(compareBy<LabelEntry> { it.snapY }.thenBy { it.anchor.first })

            for (entry in entries) {
                val (_, label, anchor, snapY, isSel) = entry
                val textW = labelPaint.measureText(label)
                val labelY = snapY - 10f

                // Fond semi-transparent
                val bgRect = android.graphics.RectF(
                    anchor.first - textW / 2f - 6f,
                    labelY - 22f,
                    anchor.first + textW / 2f + 6f,
                    labelY + 6f
                )
                val bgColor = if (isSel) Color.argb(220, 220, 235, 255)
                              else Color.argb(180, 255, 255, 255)
                canvas.drawRoundRect(bgRect, 6f, 6f,
                    Paint().apply { color = bgColor; style = Paint.Style.FILL }
                )
                // Ancre
                canvas.drawCircle(anchor.first, anchor.second, 3f,
                    Paint().apply {
                        color = if (isSel) Color.argb(200, 40, 100, 255)
                                else Color.argb(150, 80, 80, 180)
                        style = Paint.Style.FILL
                    }
                )
                // Label
                val lp = if (isSel) selectedLabelPaint else labelPaint
                canvas.drawText(label, anchor.first, labelY, lp)
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
        deselectGroup()
        invalidate()
    }
}
