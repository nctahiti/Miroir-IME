package com.parnasse.miroir

import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint as OnyxTouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.util.UUID

/**
 * MiroirIME — Portail d'écriture manuscrite universel.
 *
 * InputMethodService qui capture l'écriture au stylet, la reconnaît via
 * ML Kit Digital Ink, et injecte le texte dans le champ cible.
 *
 * Phase 1 (squelette) : capture → reconnaissance → commit.
 * Pas de persistance, pas de groupement — le minimum vivant.
 *
 * Architecture :
 *   TouchHelper (Onyx) → forward MotionEvent → onTouchEvent → strokes → ML Kit → commit
 */
class MiroirIME : InputMethodService() {

    companion object {
        private const val TAG = "Miroir/IME"
        private const val IME_HEIGHT_DP = 160
    }

    // ── Dessin ────────────────────────────────────────────────────────
    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val strokePaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private var currentPath = Path()
    private var currentStroke: StrokeRecord? = null

    // ── TouchHelper Onyx ───────────────────────────────────────────────
    private var touchHelper: TouchHelper? = null
    private var useTouchHelper = false
    private var touchHelperAttempted = false
    private var touchHelperLastEvent = 0L

    // ── Reconnaissance ML Kit ──────────────────────────────────────────
    private var recognizer: DigitalInkWrapper? = null
    private val strokeRegistry = mutableListOf<StrokeRecord>()
    private val inkStrokeIdToRegistryIndex = mutableMapOf<Long, Int>()
    private var inkStrokeIdCounter = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "miroir-ime-infer").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private var pendingRecognition = false
    private var accumulatedText = ""  // texte déjà commité

    // ── GroupManager — groupement spatial par blob ─────────────────────
    private var groupManager: GroupManager? = null
    // Map firstIdx → texte reconnu (labels)
    private val groupLabels = mutableMapOf<Int, String>()
    private val labelPaint = Paint().apply {
        color = Color.argb(180, 80, 80, 80)
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Vue IME ────────────────────────────────────────────────────────
    private var imeView: CaptureSurfaceView? = null

    // ── Template (partition) ───────────────────────────────────────────
    // L'espacement est calculé dynamiquement selon la hauteur du canvas
    // pour garantir ~4-6 lignes visibles quelle que soit la densité d'écran.
    private var template: Template = Template.HorizontalStaff(spacingPx = 120f) // sera recalculé

    /** Recalcule l'espacement du template selon la hauteur réelle du canvas. */
    private fun updateTemplateSpacing(canvasHeight: Int) {
        if (canvasHeight <= 0) return
        // ~5 lignes dans la hauteur disponible
        val targetLines = 5f
        val spacing = (canvasHeight / targetLines).coerceIn(60f, 200f)
        template = Template.HorizontalStaff(spacingPx = spacing)
        Log.d(TAG, "Template: ${spacing.toInt()}px entre lignes (hauteur=$canvasHeight)")
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE IME
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MiroirIME — Portail d'écriture universel — création")
        recognizer = DigitalInkWrapper(this)
        recognizer?.load()

        // GroupManager — groupement spatial par blob
        groupManager = GroupManager({ group ->
            // Groupe complété → reconnaissance individuelle
            val indices = group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) return@GroupManager
            Log.i(TAG, "Groupe STORED: ${group.id} (${indices.size} strokes)")
            inferExecutor.submit {
                recognizeGroup(indices, group.strokeIds.firstOrNull() ?: 0L)
            }
        }).also {
            it.pointProvider = { strokeId ->
                inkStrokeIdToRegistryIndex[strokeId]
                    ?.let { idx -> strokeRegistry.getOrNull(idx)?.points }
            }
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView — création de la surface de capture")

        val container = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (IME_HEIGHT_DP * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.argb(240, 255, 255, 255))
        }

        val surface = CaptureSurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        imeView = surface

        container.addView(surface)
        container.post {
            if (surface.width > 0 && surface.height > 0) {
                bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
                bitmapCanvas?.drawColor(Color.WHITE)
                updateTemplateSpacing(surface.height)
            }
        }

        // Initialiser TouchHelper sur la surface
        initTouchHelper(surface)

        return container
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "onStartInputView — champ: ${info?.fieldName ?: "inconnu"}")
        // Nouveau champ : réinitialiser le texte accumulé
        accumulatedText = ""
        clearCanvas()
    }
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (finishingInput) {
            Log.i(TAG, "onFinishInputView — fermeture")
            releaseTouchHelper()
        }
    }

    override fun onDestroy() {
        releaseTouchHelper()
        recognizer?.close()
        inferExecutor.shutdown()
        bitmap?.recycle()
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════════════════════
    // VUE DE CAPTURE (interne)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Surface de capture qui reçoit les MotionEvent forwardés par TouchHelper.
     * Le TouchHelper Onyx, avec setPostInputEvent(true), transforme les données
     * brutes du stylet en MotionEvent standard et les injecte dans onTouchEvent.
     */
    private inner class CaptureSurfaceView(context: android.content.Context) : View(context) {

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            // Dessiner la partition (template delta)
            val t = template
            if (t is Template.HorizontalStaff) {
                t.draw(canvas, width, height)
            }
            // Dessiner les labels de groupe
            drawGroupLabels(canvas)
            canvas.drawPath(currentPath, strokePaint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // TouchHelper forward les événements stylet ici (quand setPostInputEvent=true)
            if (!useTouchHelper && touchHelperAttempted) {
                // TouchHelper a échoué, traiter directement
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onStylusDown(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        onStylusPoint(
                            event.getHistoricalX(i),
                            event.getHistoricalY(i),
                            event.getHistoricalPressure(i)
                        )
                    }
                    onStylusPoint(event.x, event.y, event.pressure)
                }
                MotionEvent.ACTION_UP -> {
                    onStylusUp()
                }
            }
            return true
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH HELPER (Onyx SDK)
    // ═══════════════════════════════════════════════════════════════════

    private fun initTouchHelper(target: View) {
        if (touchHelper != null) return
        try {
            touchHelper = TouchHelper.create(target, TouchHelper.FEATURE_APP_TOUCH_RENDER,
                object : RawInputCallback() {
                    override fun onBeginRawDrawing(p0: Boolean, p1: OnyxTouchPoint) {
                        touchHelperLastEvent = System.currentTimeMillis()
                    }
                    override fun onRawDrawingTouchPointMoveReceived(point: OnyxTouchPoint?) {
                        touchHelperLastEvent = System.currentTimeMillis()
                    }
                    override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
                        if (list != null) touchHelperLastEvent = System.currentTimeMillis()
                    }
                    override fun onEndRawDrawing(p0: Boolean, p1: OnyxTouchPoint) {
                        touchHelperLastEvent = System.currentTimeMillis()
                    }
                    override fun onBeginRawErasing(p0: Boolean, p1: OnyxTouchPoint) {}
                    override fun onEndRawErasing(p0: Boolean, p1: OnyxTouchPoint) {}
                    override fun onRawErasingTouchPointMoveReceived(p0: OnyxTouchPoint) {}
                    override fun onRawErasingTouchPointListReceived(p0: TouchPointList) {}
                })

            touchHelper!!.setRawInputReaderEnable(true)
            touchHelper!!.setBrushRawDrawingEnabled(true)
            touchHelper!!.setRawDrawingEnabled(true)
            touchHelper!!.openRawDrawing()
            touchHelper!!.setPostInputEvent(true)  // forward vers onTouchEvent
            useTouchHelper = (touchHelper != null)
            touchHelperAttempted = true
            touchHelperLastEvent = System.currentTimeMillis()
            Log.i(TAG, "TouchHelper actif (useTouchHelper=$useTouchHelper)")

            // Mode écriture EPD
            try {
                EpdController.setScreenHandWritingPenState(target, 1)
                EpdController.enablePost(target, 0)
                EpdController.setViewDefaultUpdateMode(target, UpdateMode.DU)
                Log.i(TAG, "EPD handwriting mode ON")
            } catch (e: Exception) {
                Log.w(TAG, "EPD handwriting mode indisponible: ${e.message}")
            }
        } catch (e: Exception) {
            useTouchHelper = false
            touchHelper = null
            touchHelperAttempted = true
            Log.w(TAG, "TouchHelper indisponible: ${e.message} — fallback onTouchEvent")
        }
    }

    private fun releaseTouchHelper() {
        try {
            touchHelper?.closeRawDrawing()
            touchHelper?.setRawDrawingEnabled(false)
        } catch (_: Exception) {}
        imeView?.let { v ->
            try {
                EpdController.setViewDefaultUpdateMode(v, UpdateMode.GU)
                EpdController.enablePost(v, 1)
                EpdController.setScreenHandWritingPenState(v, 0)
            } catch (_: Exception) {}
        }
        touchHelper = null
        useTouchHelper = false
    }

    // ═══════════════════════════════════════════════════════════════════
    // GESTION DES POINTS STYLET
    // ═══════════════════════════════════════════════════════════════════

    private fun onStylusDown(x: Float, y: Float) {
        currentPath.reset()
        currentPath.moveTo(x, y)
        currentStroke = StrokeRecord(
            id = UUID.randomUUID().toString()
        ).also { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(1.0f)
        }
        imeView?.invalidate()
    }

    private fun onStylusPoint(x: Float, y: Float, pressure: Float) {
        currentPath.lineTo(x, y)
        currentStroke?.let { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(pressure.coerceIn(0f, 1f))
        }
        imeView?.invalidate()
    }

    private fun onStylusUp() {
        val stroke = currentStroke
        currentStroke = null
        if (stroke == null || stroke.points.isEmpty()) return

        // Rastériser le stroke dans le bitmap
        val canvas = bitmapCanvas ?: return
        if (stroke.points.size < 2) {
            val p = stroke.points.first()
            canvas.drawCircle(p.first, p.second, 1.5f, strokePaint.apply { style = Paint.Style.FILL })
        } else {
            canvas.drawPath(currentPath, strokePaint.apply { style = Paint.Style.STROKE })
        }
        currentPath.reset()

        // Ajouter au registre
        strokeRegistry.add(stroke)
        val registryIdx = strokeRegistry.size - 1
        val inkId = ++inkStrokeIdCounter
        inkStrokeIdToRegistryIndex[inkId] = registryIdx

        // ═══ GroupManager : groupement spatial ═══
        val inkStroke = strokeRecordToInkStroke(stroke, inkId)
        groupManager?.onStrokeSealed(inkStroke)

        imeView?.invalidate()
    }

    /** Convertit un StrokeRecord en InkStroke (format GroupManager) */
    private fun strokeRecordToInkStroke(sr: StrokeRecord, id: Long): InkStroke {
        val inkStroke = InkStroke(id = id, sessionId = 0L)
        val t0 = sr.timestamps.firstOrNull() ?: System.currentTimeMillis()
        for (i in sr.points.indices) {
            val (x, y) = sr.points[i]
            val t = sr.timestamps.getOrElse(i) { t0 + i * 16L }
            val p = sr.pressures.getOrElse(i) { 1.0f }
            val action = if (i == 0) InkPoint.ACTION_DOWN
                else if (i == sr.points.size - 1) InkPoint.ACTION_UP
                else InkPoint.ACTION_MOVE
            inkStroke.points.add(InkPoint(
                x = x, y = y,
                pressure = p,
                tilt = 0f, orientation = 0f, distance = 0f,
                timestamp = t,
                action = action,
                toolType = InkPoint.TOOL_STYLUS
            ))
        }
        inkStroke.endNano = sr.timestamps.lastOrNull() ?: t0
        inkStroke.isSealed = true
        return inkStroke
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECONNAISSANCE PAR GROUPE (via GroupManager)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reconnaît un groupe individuel (appelé depuis le thread background).
     */
    private fun recognizeGroup(indices: List<Int>, firstStrokeId: Long) {
        val recognizer = recognizer ?: return
        if (!recognizer.isLoaded) return

        try {
            val strokesCopy = strokeRegistry.toList()
            val result = recognizer.recognize(strokesCopy, indices)
            if (!result.isNullOrBlank()) {
                Log.i(TAG, "Reconnaissance groupe: \"$result\" (${indices.size} strokes)")
                uiHandler.post {
                    // Stocker le label
                    val firstIdx = indices.firstOrNull() ?: return@post
                    groupLabels[firstIdx] = result
                    // Commit le nouveau texte
                    commitText(result)
                    imeView?.invalidate()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reconnaissance groupe: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // COMMIT — injection dans le champ cible
    // ═══════════════════════════════════════════════════════════════════

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText("$text ", 1)
        Log.i(TAG, "Texte injecté: \"$text\"")
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITAIRES
    // ═══════════════════════════════════════════════════════════════════

    private fun clearCanvas() {
        bitmapCanvas?.drawColor(Color.WHITE)
        currentPath.reset()
        currentStroke = null
        imeView?.invalidate()
    }

    /** Dessine les labels de groupe sous leur emplacement spatial */
    private fun drawGroupLabels(canvas: Canvas) {
        if (groupLabels.isEmpty()) return
        val gm = groupManager ?: return
        val groups = gm.allGroups()
        for (group in groups) {
            val firstIdx = group.strokeIds.firstOrNull()
                ?.let { inkStrokeIdToRegistryIndex[it] }
                ?: continue
            val label = groupLabels[firstIdx] ?: continue
            // Calculer la position Y du label (sous le groupe)
            val registryIdx = firstIdx
            val sr = strokeRegistry.getOrNull(registryIdx) ?: continue
            if (sr.points.isEmpty()) continue
            // Centre Y du stroke + 30px
            var sumY = 0f
            for (pt in sr.points) sumY += pt.second
            val centerY = sumY / sr.points.size
            val x = sr.points.first().first
            canvas.drawText(label, x, centerY + 30f, labelPaint)
        }
    }
}
