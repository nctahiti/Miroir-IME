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
    private val handler = Handler(Looper.getMainLooper())
    private var pendingRecognition = false

    // ── Vue IME ────────────────────────────────────────────────────────
    private var imeView: CaptureSurfaceView? = null

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE IME
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MiroirIME — Portail d'écriture universel — création")
        recognizer = DigitalInkWrapper(this)
        recognizer?.load()
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
            }
        }

        // Initialiser TouchHelper sur la surface
        initTouchHelper(surface)

        return container
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "onStartInputView — champ: ${info?.fieldName ?: "inconnu"}")
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
        imeView?.invalidate()

        // Lancer la reconnaissance
        scheduleRecognition()
    }

    // ═══════════════════════════════════════════════════════════════════
    // RECONNAISSANCE ML KIT
    // ═══════════════════════════════════════════════════════════════════

    private fun scheduleRecognition() {
        if (pendingRecognition) return
        pendingRecognition = true

        handler.postDelayed({
            pendingRecognition = false
            doRecognition()
        }, 800)
    }

    private fun doRecognition() {
        val recognizer = recognizer ?: return
        if (!recognizer.isLoaded) {
            Log.d(TAG, "Modèle ML Kit pas encore chargé — reconnaissance différée")
            handler.postDelayed({ scheduleRecognition() }, 2000)
            return
        }
        if (strokeRegistry.isEmpty()) return

        try {
            val indices = strokeRegistry.indices.toList()
            val result = recognizer.recognize(strokeRegistry, indices)
            if (!result.isNullOrBlank()) {
                Log.i(TAG, "Reconnaissance: \"$result\" (${strokeRegistry.size} strokes)")
                commitText(result)
                clearCanvas()
                strokeRegistry.clear()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reconnaissance: ${e.message}")
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
}
