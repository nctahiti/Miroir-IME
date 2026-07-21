package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.graphics.RectF
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * CaptureSurfaceView — Vue de capture avec UxK Miroir (identique IME).
 *
 * Modes :
 *   - Ecriture : strokes normaux, groupement spatial, inference ML Kit
 *   - Selection : tap sur blob → SELECTED (visuel)
 *   - Correction : long-press ou tap sur label → cadre + puces +/−/🔒/📌
 *   - Deplacement : drag du groupe selectionne
 *   - Effacement : mode gomme (stylet retourne)
 *
 * Reference un MiroirEngine pour les donnees.
 */
class CaptureSurfaceView(context: Context, val engine: MiroirEngine) : View(context) {

    companion object {
        private const val TAG = "Miroir/CaptureView"
        private const val TAP_THRESHOLD_PX = 30f
        private const val HIT_RADIUS = 70f
        private const val SWIPE_THRESHOLD = 30f
    }

    // ── Modes ──────────────────────────────────────────────────────────
    enum class EditMode { NONE, CORRECT_TRANSCRIPTION, ERASE, MOVE }
    private var editMode = EditMode.NONE

    // ── Pinceaux ──────────────────────────────────────────────────────
    private val strokePaint = Paint().apply {
        color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
    }
    // ── Blob : contour seul (comme l'IME) — pas de FILL, pas d'alpha ──
    // Le FILL avec alpha est très coûteux sur EPD (fusion alpha pixel par pixel).
    // STROKE noir → rendu instantané, même sur page pleine.
    private val blobPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = 1.5f; isAntiAlias = false
    }
    private val selectedBlobPaint = Paint().apply {
        color = Color.BLACK; style = Paint.Style.STROKE
        strokeWidth = 3.5f; isAntiAlias = false  // plus épais = visuellement sélectionné
    }
    private val labelPaint = Paint().apply {
        color = Color.argb(200, 80, 80, 180); textSize = 40f; isAntiAlias = false
        textAlign = Paint.Align.LEFT
    }

    // ── État ──────────────────────────────────────────────────────────
    private var isStylusDown = false
    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    var showLabels: Boolean = true

    // ── Tap / selection ───────────────────────────────────────────────
    private var tapStartX = 0f; private var tapStartY = 0f
    private var tapStartTime = 0L
    private var tapMoved = false
    private var selectedGroupId: String? = null
    private var selectedGroupLabel: String? = null
    private var longPressArmed = false  // true après un long-press → en attente de swipe

    // ── Correction ────────────────────────────────────────────────────
    private var correctionGroupId: String? = null
    private var correctionGroupFirstIdx: Int = -1
    private var correctionLabel: String = ""
    private var correctLetterIndex: Int = -1
    private var insertAtIndex: Int = -1
    private val correctionPaths = mutableListOf<Path>()

    /** Callbacks */
    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null
    /** Appelé quand le mode édition se termine → la fontaine doit se réactiver. */
    var onReturnToWriting: (() -> Unit)? = null

    /** Référence à la FontaineOverlay — pour activer/désactiver le mode interaction. */
    var fontaineOverlay: FontaineOverlay? = null

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
    // TOUCH — UxK Miroir (taps/sélections UNIQUEMENT, strokes → FontaineOverlay)
    // ═══════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
        // Si la fontaine est en train d'écrire, ignorer les taps
        if (fontaineOverlay?.isStylusDown == true && !longPressArmed) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapStartX = event.x; tapStartY = event.y
                tapStartTime = System.currentTimeMillis()
                tapMoved = false
                if (longPressArmed) {
                    // Déjà en mode long-press, le DOWN est le début du geste
                    return true
                }
                if (editMode == EditMode.CORRECT_TRANSCRIPTION) {
                    val minusIdx = hitTestMinus(event.x, event.y)
                    if (minusIdx >= 0 && minusIdx < correctionLabel.length) {
                        correctionLabel = correctionLabel.removeRange(minusIdx, minusIdx + 1)
                        correctLetterIndex = -1; insertAtIndex = -1
                        invalidate(); return true
                    }
                    val plusIdx = hitTestPlus(event.x, event.y)
                    if (plusIdx >= 0 && plusIdx <= correctionLabel.length) {
                        insertAtIndex = plusIdx; correctLetterIndex = -1
                        invalidate(); return true
                    }
                    val letterIdx = hitTestLetter(event.x, event.y)
                    if (letterIdx >= 0 && letterIdx < correctionLabel.length) {
                        correctLetterIndex = letterIdx; insertAtIndex = -1
                        invalidate(); return true
                    }
                    exitEditMode()
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (longPressArmed) {
                    handleLongPressMove(event.x, event.y)
                    return true
                }
                val dx = abs(event.x - tapStartX)
                val dy = abs(event.y - tapStartY)
                if (dx > TAP_THRESHOLD_PX || dy > TAP_THRESHOLD_PX) tapMoved = true
            }
            MotionEvent.ACTION_UP -> {
                if (longPressArmed) {
                    handleLongPressUp()
                    return true
                }
                if (!tapMoved) {
                    handleTap(event.x, event.y)
                }
                invalidate()
            }
        }
        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // HIT-TEST PUCES (correction)
    // ═══════════════════════════════════════════════════════════════════

    private fun correctionFrame(): RectF? {
        val anchor = engine.groupAnchor[correctionGroupFirstIdx] ?: return null
        if (correctionLabel.isEmpty()) return null
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val letterW = spacing * 0.7f
        val totalW = letterW * correctionLabel.length
        val snapY = engine.snapToLine(anchor.second)
        val startX = anchor.first - totalW / 2f
        val startY = snapY - spacing * 0.8f
        return RectF(startX - 20f, startY - 10f, startX + totalW + 20f, startY + letterW + 10f)
    }

    private fun hitTestMinus(x: Float, y: Float): Int {
        val anchor = engine.groupAnchor[correctionGroupFirstIdx] ?: return -1
        if (correctionLabel.isEmpty()) return -1
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val letterW = spacing * 0.7f
        val totalW = letterW * correctionLabel.length
        val snapY = engine.snapToLine(anchor.second)
        val startX = anchor.first - totalW / 2f
        val startY = snapY - spacing * 0.8f
        val chipRadius = maxOf(letterW * 0.3f, 14f)
        for (i in correctionLabel.indices) {
            val cx = startX + letterW * i + letterW / 2f
            val cy = startY + letterW + chipRadius + 4f
            val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (d < chipRadius + 8f) return i
        }
        return -1
    }

    private fun hitTestPlus(x: Float, y: Float): Int {
        val anchor = engine.groupAnchor[correctionGroupFirstIdx] ?: return -1
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val letterW = spacing * 0.7f
        val totalW = letterW * correctionLabel.length
        val snapY = engine.snapToLine(anchor.second)
        val startX = anchor.first - totalW / 2f
        val startY = snapY - spacing * 0.8f
        val chipRadius = maxOf(letterW * 0.3f, 14f)
        for (i in 0..correctionLabel.length) {
            val cx = startX + letterW * i
            val cy = startY - chipRadius - 4f
            val d = Math.hypot((x - cx).toDouble(), (y - cy).toDouble())
            if (d < chipRadius + 8f) return i
        }
        return -1
    }

    private fun hitTestLetter(x: Float, y: Float): Int {
        val frame = correctionFrame() ?: return -1
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val letterW = spacing * 0.7f
        val startX = frame.left + 20f
        val startY = frame.top + 10f
        if (x < startX || x > frame.right - 20f || y < startY || y > frame.bottom - 10f) return -1
        val idx = ((x - startX) / letterW).toInt()
        return if (idx in correctionLabel.indices) idx else -1
    }

    /** Hit-test blob elliptique (path, pas juste bounding box). */
    private fun hitTestBlob(x: Float, y: Float): String? {
        for ((gid, blob) in engine.groupBlobs) {
            // Filtre rapide : bounding box rectangulaire
            val b = blob.bounds
            if (x < b.left || x > b.right || y < b.top || y > b.bottom) continue
            // Test précis : point dans le path elliptique
            val r = android.graphics.RectF(b.left, b.top, b.right, b.bottom)
            val region = android.graphics.Region()
            region.setPath(blob.path, android.graphics.Region(
                r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()
            ))
            if (region.contains(x.toInt(), y.toInt())) return gid
        }
        return null
    }

    private fun hitTestAnchor(x: Float, y: Float): String? {
        for ((firstIdx, anchor) in engine.groupAnchor) {
            val adx = abs(x - anchor.first)
            val ady = abs(y - anchor.second)
            if (adx < HIT_RADIUS && ady < HIT_RADIUS) {
                for (g in engine.groupManager?.allGroupsFull() ?: emptyList()) {
                    val firstSid = g.strokeIds.firstOrNull() ?: continue
                    val firstRI = engine.inkStrokeIdToRegistryIndex[firstSid]
                    if (firstRI == firstIdx) return g.id
                }
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════
    // GESTES
    // ═══════════════════════════════════════════════════════════════════

    /** MOVE pendant un long-press armé → détecter la direction ou continuer le geste. */
    private fun handleLongPressMove(x: Float, y: Float) {
        if (editMode == EditMode.NONE) {
            val dx = x - gestureStartX
            val dy = y - gestureStartY
            if (dx < -SWIPE_THRESHOLD) {
                enterEraseMode(x)
                Log.i(TAG, "→ Mode EFFACEMENT (←)")
            } else if (dy > SWIPE_THRESHOLD) {
                enterMoveMode(x, y)
                Log.i(TAG, "→ Mode DÉPLACEMENT (↓)")
            } else if (dx > SWIPE_THRESHOLD) {
                // → absorption : annuler le long-press, réactiver la fontaine
                exitGestureMode()
                longPressArmed = false
                onReturnToWriting?.invoke()
                Log.i(TAG, "→ Absorption (→) — retour écriture")
            } else if (dy < -SWIPE_THRESHOLD) {
                enterCorrectionMode()
                Log.i(TAG, "→ Mode CORRECTION (↑)")
            }
        } else if (editMode == EditMode.ERASE) {
            scrubGroup(x)
        } else if (editMode == EditMode.MOVE) {
            moveGroup(x - gestureStartX, y - gestureStartY)
            gestureStartX = x; gestureStartY = y
        }
    }

    /** UP après un long-press → appliquer la coupe scrub, sortir du mode édition. */
    private fun handleLongPressUp() {
        // Appliquer la coupe scrub si active (PEN_UP en mode ERASE)
        if (editMode == EditMode.ERASE) applyScrubCut()
        val wasCorrecting = editMode == EditMode.CORRECT_TRANSCRIPTION
        exitGestureMode()
        longPressArmed = false
        if (!wasCorrecting) {
            onReturnToWriting?.invoke()
        }
        invalidate()
    }

    private fun handleTap(x: Float, y: Float) {
        // 1. Hit-test blob
        val blobGid = hitTestBlob(x, y)
        if (blobGid != null) {
            if (selectedGroupId == blobGid) {
                // Double-tap → entrer en mode correction
                enterCorrectionMode(blobGid)
            } else {
                selectGroup(blobGid)
            }
            return
        }

        // 2. Hit-test ancre
        val anchorGid = hitTestAnchor(x, y)
        if (anchorGid != null) {
            if (selectedGroupId == anchorGid) {
                enterCorrectionMode(anchorGid)
            } else {
                selectGroup(anchorGid)
            }
            return
        }

        // 3. Clic dans le vide → deselect + retour écriture
        if (selectedGroupId != null) {
            deselectGroup()
            onReturnToWriting?.invoke()
        }
    }

    private fun selectGroup(gid: String) {
        val gm = engine.groupManager ?: return
        selectedGroupId?.let { gm.deselectGroup(it) }
        selectedGroupId = gid
        gm.selectGroup(gid)
        // ⚠️ Ne pas désactiver la fontaine ici — les appelants gèrent

        val group = gm.allGroupsFull().find { it.id == gid }
        val firstSid = group?.strokeIds?.firstOrNull()
        val firstRI = firstSid?.let { engine.inkStrokeIdToRegistryIndex[it] }
        selectedGroupLabel = firstRI?.let { engine.groupLabels[it] }

        Log.i(TAG, "Groupe selectionne: ${gid.take(8)} label='$selectedGroupLabel'")
        invalidate()
    }

    /** Long-press → trouver un blob à (x,y) et le sélectionner. */
    internal fun selectGroupAt(x: Float, y: Float) {
        val blobGid = hitTestBlob(x, y)
        if (blobGid != null) selectGroup(blobGid)
    }

    /** Appelé après un long-press → arme la détection de swipe dans onTouchEvent. */
    fun armLongPressGesture(startX: Float, startY: Float) {
        longPressArmed = true
        gestureStartX = startX
        gestureStartY = startY
        tapStartX = startX
        tapStartY = startY
        tapMoved = false
    }

    fun deselectGroup() {
        val gm = engine.groupManager
        selectedGroupId?.let { gm?.deselectGroup(it) }
        selectedGroupId = null
        selectedGroupLabel = null
        // Sortir du mode interaction (la réactivation est gérée par l'appelant)
        fontaineOverlay?.modeInteraction = false
        invalidate()
    }

    /** Dessine le blob sélectionné dans la SurfaceView (pour le garder visible pendant l'écriture). */
    fun drawSelectedBlobOn(fontaine: FontaineOverlay?) {
        val gid = selectedGroupId ?: return
        val blob = engine.groupBlobs[gid] ?: return
        fontaine?.dessinerBlob(blob.path, blob.bounds, selectedBlobPaint, selectedBlobPaint)
    }

    private fun enterCorrectionMode(gid: String) {
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return
        val firstSid = group.strokeIds.firstOrNull() ?: return
        val firstRI = engine.inkStrokeIdToRegistryIndex[firstSid] ?: return
        val label = engine.groupLabels[firstRI] ?: ""

        editMode = EditMode.CORRECT_TRANSCRIPTION
        correctionGroupId = gid
        correctionGroupFirstIdx = firstRI
        correctionLabel = label
        correctLetterIndex = -1
        insertAtIndex = -1
        correctionPaths.clear()

        Log.i(TAG, "Mode correction: '$label' (groupe ${gid.take(8)})")
        invalidate()
    }

    /** Entre en mode correction pour le groupe sélectionné (appelé par swipe ↑). */
    private fun enterCorrectionMode() {
        val gid = selectedGroupId ?: return
        enterCorrectionMode(gid)
    }

    private fun exitEditMode() {
        // Appliquer le label corrige
        if (correctionGroupFirstIdx >= 0 && correctionLabel.isNotEmpty()) {
            engine.groupLabels[correctionGroupFirstIdx] = correctionLabel
            Log.i(TAG, "Label corrige: '$correctionLabel'")
        }
        editMode = EditMode.NONE
        correctionGroupId = null
        correctionGroupFirstIdx = -1
        correctionLabel = ""
        correctLetterIndex = -1
        insertAtIndex = -1
        correctionPaths.clear()
    }

    fun isCorrecting() = editMode == EditMode.CORRECT_TRANSCRIPTION

    // ═══════════════════════════════════════════════════════════════════
    // EFFACEMENT & DÉPLACEMENT (importés de l'IME)
    // ═══════════════════════════════════════════════════════════════════

    private val erasedStrokes = mutableSetOf<Int>()
    private var gestureStartX = 0f
    private var gestureStartY = 0f

    /** Active le mode effacement. Appelé par CaptureActivity après détection du geste. */
    fun enterEraseMode(startX: Float) {
        editMode = EditMode.ERASE
        gestureStartX = startX
        Log.i(TAG, "→ Mode EFFACEMENT")
    }

    /** Active le mode déplacement. */
    fun enterMoveMode(startX: Float, startY: Float) {
        editMode = EditMode.MOVE
        gestureStartX = startX
        gestureStartY = startY
        Log.i(TAG, "→ Mode DÉPLACEMENT")
    }

    /** Position de coupe preview (0.0 à 1.0, -1 si pas de scrub actif). */
    private var scrubCutRatio: Float = -1f
    /** Ligne de coupe verticale affichée pendant le scrub. */
    private var scrubCutX: Float = 0f

    /** Scrub : preview seule — trait rouge + zone qui sera coupée.
     *  La coupe réelle est appliquée au PEN_UP via applyScrubCut(). */
    fun scrubGroup(currentX: Float) {
        val gid = selectedGroupId ?: return
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return
        if (group.strokeIds.isEmpty()) return

        val gb = group.bounds
        val groupWidth = gb.right - gb.left
        if (groupWidth <= 0f) return

        val ratio = ((currentX - gb.left) / groupWidth).coerceIn(0f, 1f)
        scrubCutRatio = ratio
        scrubCutX = gb.left + groupWidth * ratio
        invalidate()
    }

    /** Applique la coupe au PEN_UP. */
    fun applyScrubCut() {
        if (scrubCutRatio < 0f) return
        val gid = selectedGroupId ?: return
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return

        val strokes = group.strokeIds.mapNotNull { sid ->
            val idx = engine.inkStrokeIdToRegistryIndex[sid]
            if (idx != null && idx < engine.strokeRegistry.size) idx to engine.strokeRegistry[idx] else null
        }.filter { (_, sr) -> sr.points.size >= 2 }

        if (strokes.isEmpty()) { scrubCutRatio = -1f; return }

        val strokeLengths = strokes.map { (_, sr) ->
            var len = 0.0; val pts = sr.points
            for (i in 1 until pts.size)
                len += Math.hypot((pts[i].first - pts[i-1].first).toDouble(), (pts[i].second - pts[i-1].second).toDouble())
            len
        }
        val totalLen = strokeLengths.sum()
        if (totalLen <= 0.0) { scrubCutRatio = -1f; return }
        val cutLen = totalLen * scrubCutRatio

        var accum = 0.0
        for (i in strokes.indices) {
            val (idx, sr) = strokes[i]
            val sl = strokeLengths[i]
            if (accum + sl <= cutLen) {
                accum += sl
            } else if (accum >= cutLen) {
                sr.points.clear(); sr.timestamps.clear(); sr.pressures.clear()
                sr.isDeleted = true
                if (idx !in erasedStrokes) erasedStrokes.add(idx)
            } else {
                val pts = sr.points
                var ptAccum = 0.0; var cutPtIdx = pts.size
                for (j in 1 until pts.size) {
                    ptAccum += Math.hypot((pts[j].first - pts[j-1].first).toDouble(), (pts[j].second - pts[j-1].second).toDouble())
                    if (accum + ptAccum >= cutLen) { cutPtIdx = j; break }
                }
                if (cutPtIdx > 0 && cutPtIdx < pts.size) {
                    val kept = pts.take(cutPtIdx)
                    val keptTs = sr.timestamps.take(cutPtIdx)
                    val keptPr = sr.pressures.take(cutPtIdx)
                    sr.points.clear(); sr.points.addAll(kept)
                    sr.timestamps.clear(); sr.timestamps.addAll(keptTs)
                    sr.pressures.clear(); sr.pressures.addAll(keptPr)
                }
                accum = cutLen
            }
        }

        val refreshedGroup = gm.allGroupsFull().find { it.id == gid }
        if (refreshedGroup != null) {
            engine.computeBlobPath(refreshedGroup)?.let { newBlob ->
                engine.groupBlobs[gid] = newBlob
                refreshedGroup.bounds.set(newBlob.bounds.left, newBlob.bounds.top, newBlob.bounds.right, newBlob.bounds.bottom)
            }
        }

        val firstIdx = group.strokeIds.firstOrNull()?.let { engine.inkStrokeIdToRegistryIndex[it] }
        if (firstIdx != null) engine.groupLabels.remove(firstIdx)

        engine.redrawBitmapInternal(fullRedraw = true)
        scrubCutRatio = -1f
        invalidate()
    }

    /** Déplace le groupe sélectionné du delta (dx, dy).
     *  Aligné sur l'IME : drawColor(CLEAR) + redraw complet + blob.path.offset(). */
    fun moveGroup(dx: Float, dy: Float) {
        val gid = selectedGroupId ?: return
        val gm = engine.groupManager ?: return
        val group = gm.allGroupsFull().find { it.id == gid } ?: return

        // ═══ 1. Translater les strokes ═══
        for (sid in group.strokeIds) {
            val idx = engine.inkStrokeIdToRegistryIndex[sid] ?: continue
            if (idx < engine.strokeRegistry.size) {
                engine.strokeRegistry[idx].points.replaceAll { p: Pair<Float, Float> -> Pair(p.first + dx, p.second + dy) }
            }
        }
        // Déplacer l'ancre du label
        val firstSid = group.strokeIds.firstOrNull()
        val firstIdx = firstSid?.let { sid -> engine.inkStrokeIdToRegistryIndex[sid] }
        if (firstIdx != null) {
            val anchor = engine.groupAnchor[firstIdx]
            if (anchor != null) {
                engine.groupAnchor[firstIdx] = Pair(anchor.first + dx, anchor.second + dy)
            }
        }
        group.bounds.offset(dx, dy)

        // ═══ 2. Déplacer le blob directement (pas de recalcul coûteux) ═══
        engine.groupBlobs[gid]?.let { blob ->
            val m = android.graphics.Matrix()
            m.postTranslate(dx, dy)
            blob.path.transform(m)
            val b = blob.bounds
            engine.groupBlobs[gid] = com.parnasse.miroir.BlobData(
                blob.path,
                com.parnasse.miroir.RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
            )
        }

        // ═══ 3. Redraw complet (comme l'IME) ═══
        // drawColor(CLEAR) est O(1) GPU. Redessiner tous les strokes est
        // acceptable car isAntiAlias=false sur EPD.
        engine.redrawBitmapInternal(fullRedraw = true)
        invalidate()
    }

    /** Redessine tous les strokes dans le bitmap (hors strokes effacés/supprimés). */
    fun redrawBitmapOnly() {
        val canvas = engine.bitmapCanvas
        if (canvas == null) {
            Log.w(TAG, "redrawBitmapOnly: bitmapCanvas est null — impossible de redessiner")
            return
        }
        Log.d(TAG, "redrawBitmapOnly: ${engine.strokeRegistry.size} strokes → bitmap")
        engine.redrawBitmapInternal()
    }

    /** Sortir du mode édition (effacement/déplacement). */
    fun exitGestureMode() {
        editMode = EditMode.NONE
        Log.i(TAG, "Sortie mode édition")
    }

    // ═══════════════════════════════════════════════════════════════════
    // RENDU
    // ═══════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        Log.d(TAG, "onDraw — sel=${selectedGroupId?.take(8) ?: "null"}")

        // 1. Bitmap (fond + strokes sauvegardés)
        engine.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2. Blob du groupe SELECTED uniquement (comme l'IME)
        val gm = engine.groupManager
        val realSelectedId = gm?.groupsInState(GroupState.SELECTED)?.firstOrNull()?.id
        if (realSelectedId != null) {
            engine.groupBlobs[realSelectedId]?.let { blob ->
                canvas.drawPath(blob.path, selectedBlobPaint)
            }
        }

        // 2b. Preview scrub : surbrillance des points conservés
        if (scrubCutRatio >= 0f && scrubCutRatio < 1f && selectedGroupId != null) {
            val gm = engine.groupManager
            val group = gm?.allGroupsFull()?.find { it.id == selectedGroupId }
            if (group != null) {
                val strokes = group.strokeIds.mapNotNull { sid ->
                    val idx = engine.inkStrokeIdToRegistryIndex[sid]
                    if (idx != null && idx < engine.strokeRegistry.size) idx to engine.strokeRegistry[idx] else null
                }.filter { (_, sr) -> sr.points.size >= 2 }
                if (strokes.isNotEmpty()) {
                    val strokeLengths = strokes.map { (_, sr) ->
                        var len = 0.0; val pts = sr.points
                        for (i in 1 until pts.size)
                            len += Math.hypot((pts[i].first - pts[i-1].first).toDouble(), (pts[i].second - pts[i-1].second).toDouble())
                        len
                    }
                    val totalLen = strokeLengths.sum()
                    if (totalLen > 0) {
                        val cutLen = totalLen * scrubCutRatio
                        val keepPaint = android.graphics.Paint().apply {
                            color = Color.BLACK; strokeWidth = 5f
                            style = android.graphics.Paint.Style.STROKE
                            strokeCap = android.graphics.Paint.Cap.ROUND; isAntiAlias = false
                        }
                        var accum = 0.0
                        for (i in strokes.indices) {
                            val (_, sr) = strokes[i]
                            val sl = strokeLengths[i]
                            if (accum + sl <= cutLen) {
                                drawStrokePath(canvas, sr, keepPaint)
                                accum += sl
                            } else if (accum < cutLen) {
                                val pts = sr.points
                                var ptAccum = 0.0; var cutPtIdx = pts.size
                                for (j in 1 until pts.size) {
                                    ptAccum += Math.hypot((pts[j].first - pts[j-1].first).toDouble(), (pts[j].second - pts[j-1].second).toDouble())
                                    if (accum + ptAccum >= cutLen) { cutPtIdx = j; break }
                                }
                                if (cutPtIdx > 1) {
                                    val path = android.graphics.Path()
                                    path.moveTo(pts[0].first, pts[0].second)
                                    for (j in 1 until cutPtIdx.coerceAtMost(pts.size))
                                        path.lineTo(pts[j].first, pts[j].second)
                                    canvas.drawPath(path, keepPaint)
                                }
                                accum = cutLen
                            }
                        }
                    }
                }
            }
        }

        // 3. Template
        for (ly in engine.cachedTemplateLines) {
            canvas.drawLine(0f, ly, width.toFloat(), ly, Template.GUIDE_PAINT)
        }

        // 4. Mode correction : cadre + puces
        if (isCorrecting()) {
            drawCorrectionFrame(canvas)
        }

        // 5. Labels (toujours visibles, même en mode correction)
        if (showLabels && engine.groupLabels.isNotEmpty()) {
            drawLabels(canvas)
        }

        // 6. Stroke en cours
        if (isStylusDown && engine.currentStrokeRecord != null) {
            canvas.drawPath(engine.currentPath, strokePaint)
        }
    }

    private fun drawCorrectionFrame(canvas: Canvas) {
        val anchor = engine.groupAnchor[correctionGroupFirstIdx] ?: return
        if (correctionLabel.isEmpty()) return
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val letterW = spacing * 0.7f
        val totalW = letterW * correctionLabel.length
        val snapY = engine.snapToLine(anchor.second)
        val startX = anchor.first - totalW / 2f
        val startY = snapY - spacing * 0.8f

        // Fond blanc (tampon)
        canvas.drawRect(startX - 20f, startY - 10f, startX + totalW + 20f, startY + letterW + 10f,
            Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
        // Bordure
        canvas.drawRect(startX - 20f, startY - 10f, startX + totalW + 20f, startY + letterW + 10f,
            Paint().apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 2f })

        // Lettres
        for (i in correctionLabel.indices) {
            val cx = startX + letterW * i + letterW / 2f
            val cy = startY + letterW * 0.75f
            val bg = if (i == correctLetterIndex) Color.argb(40, 0, 0, 255) else Color.argb(20, 0, 0, 0)
            canvas.drawRect(startX + letterW * i, startY, startX + letterW * (i + 1), startY + letterW,
                Paint().apply { color = bg; style = Paint.Style.FILL })
            val tp = if (i == correctLetterIndex)
                Paint().apply { color = Color.BLUE; textSize = letterW * 0.8f; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            else
                Paint().apply { color = Color.DKGRAY; textSize = letterW * 0.8f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText(correctionLabel[i].toString(), cx, cy, tp)
        }

        // Puces +
        val chipRadius = maxOf(letterW * 0.3f, 14f)
        for (i in 0..correctionLabel.length) {
            val cx = startX + letterW * i
            val cy = startY - chipRadius - 4f
            canvas.drawCircle(cx, cy, chipRadius,
                Paint().apply { color = Color.argb(180, 40, 44, 52); style = Paint.Style.FILL; isAntiAlias = true })
            val border = if (i == insertAtIndex)
                Paint().apply { color = Color.argb(255, 40, 200, 60); style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
            else
                Paint().apply { color = Color.argb(100, 180, 180, 200); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
            canvas.drawCircle(cx, cy, chipRadius, border)
            val tp = if (i == insertAtIndex)
                Paint().apply { color = Color.argb(255, 80, 240, 80); textSize = chipRadius * 1.2f; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            else
                Paint().apply { color = Color.argb(220, 140, 200, 140); textSize = chipRadius * 1.2f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText("+", cx, cy + chipRadius * 0.4f, tp)
        }

        // Puces -
        for (i in correctionLabel.indices) {
            val cx = startX + letterW * i + letterW / 2f
            val cy = startY + letterW + chipRadius + 4f
            canvas.drawCircle(cx, cy, chipRadius,
                Paint().apply { color = Color.argb(180, 40, 44, 52); style = Paint.Style.FILL; isAntiAlias = true })
            val border = if (i == correctLetterIndex)
                Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
            else
                Paint().apply { color = Color.argb(100, 200, 100, 100); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
            canvas.drawCircle(cx, cy, chipRadius, border)
            val tp = if (i == correctLetterIndex)
                Paint().apply { color = Color.argb(255, 255, 60, 60); textSize = chipRadius * 1.2f; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
            else
                Paint().apply { color = Color.argb(220, 200, 140, 140); textSize = chipRadius * 1.2f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
            canvas.drawText("\u2212", cx, cy + chipRadius * 0.4f, tp)
        }

        // Pastille 🔒
        val lockCX = startX - 20f - chipRadius - 6f
        val lockCY = startY + letterW / 2f
        canvas.drawCircle(lockCX, lockCY, chipRadius,
            Paint().apply { color = Color.argb(180, 40, 44, 52); style = Paint.Style.FILL; isAntiAlias = true })
        canvas.drawCircle(lockCX, lockCY, chipRadius,
            Paint().apply { color = Color.argb(100, 200, 160, 100); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true })
        val lockText = Paint().apply { color = Color.argb(220, 200, 160, 100); textSize = chipRadius * 1.0f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText("\uD83D\uDD12", lockCX, lockCY + chipRadius * 0.35f, lockText)
    }

    private fun drawLabels(canvas: Canvas) {
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        data class LabelEntry(val firstIdx: Int, val label: String, val groupLeft: Float, val snapY: Float, val isSelected: Boolean)
        val entries = mutableListOf<LabelEntry>()
        for ((firstIdx, label) in engine.groupLabels) {
            val anchor = engine.groupAnchor[firstIdx] ?: continue
            val snapY = engine.snapToLine(anchor.second)
            // Trouver le groupe pour sa position gauche
            val gm = engine.groupManager
            var gLeft = anchor.first  // fallback
            if (gm != null) {
                val group = gm.allGroupsFull().find { g ->
                    val firstSid = g.strokeIds.firstOrNull() ?: return@find false
                    engine.inkStrokeIdToRegistryIndex[firstSid] == firstIdx
                }
                if (group != null) gLeft = group.bounds.left
            }
            var isSel = false
            if (selectedGroupId != null) {
                val selGroup = gm?.allGroupsFull()?.find { it.id == selectedGroupId }
                val selFirstSid = selGroup?.strokeIds?.firstOrNull()
                val selFirstRI = selFirstSid?.let { engine.inkStrokeIdToRegistryIndex[it] }
                isSel = selFirstRI == firstIdx
            }
            entries.add(LabelEntry(firstIdx, label, gLeft, snapY, isSel))
        }
        entries.sortWith(compareBy<LabelEntry> { it.snapY }.thenBy { it.groupLeft })

        for ((_, label, groupLeft, snapY, isSel) in entries) {
            val textW = labelPaint.measureText(label)
            val labelX = groupLeft  // aligné au début du groupe
            val labelY = snapY + 18f
            val bgRect = android.graphics.RectF(
                labelX - 4f, labelY - 24f,
                labelX + textW + 8f, labelY + 10f
            )
            val bgColor = if (isSel) Color.argb(220, 220, 235, 255) else Color.argb(180, 255, 255, 255)
            canvas.drawRoundRect(bgRect, 6f, 6f, Paint().apply { color = bgColor; style = Paint.Style.FILL })
            canvas.drawText(label, labelX, labelY, labelPaint)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    fun clearCanvas() {
        engine.bitmap?.eraseColor(Color.WHITE)
        engine.bitmapCanvas?.drawColor(Color.WHITE)
        deselectGroup()
        exitEditMode()
        invalidate()
    }

    private fun drawStrokePath(canvas: Canvas, sr: StrokeRecord, paint: Paint) {
        val path = Path()
        path.moveTo(sr.points[0].first, sr.points[0].second)
        for (i in 1 until sr.points.size)
            path.lineTo(sr.points[i].first, sr.points[i].second)
        canvas.drawPath(path, paint)
    }
}
