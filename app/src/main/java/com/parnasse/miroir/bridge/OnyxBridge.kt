package com.parnasse.miroir.bridge

import android.content.Context
import android.graphics.*
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.data.note.TouchPoint as OnyxTouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

// ═══════════════════════════════════════════════════════════════════
// OnyxBridge — Surcouche Kotlin idiomatique pour le SDK Onyx OpenBridge
// ═══════════════════════════════════════════════════════════════════
//
// Ce module propose une API Kotlin native (coroutines, Flow, sealed classes)
// pour le SDK Onyx TouchHelper. Il est conçu pour être :
//   - Expressif : des noms courts, des types forts
//   - Sûr : cycle de vie géré par `use { }`, pas de try/catch manuel
//   - Composable : Flow<StylusEvent> pour la capture, fonctions suspend pour le contrôle
//
// Usage typique :
//   val bridge = OnyxBridge(context, surfaceView)
//   bridge.use {
//       it.stylusEvents.collect { event ->
//           when (event) {
//               is StylusEvent.Down -> engine.beginStroke(event.x, event.y, event.pressure)
//               is StylusEvent.Move -> engine.addPoint(event.x, event.y, event.pressure)
//               is StylusEvent.Up   -> engine.endStroke()
//           }
//       }
//   }
//
// Contribution à la communauté OpenBridge — pas à Onyx Corporation.
// Si Onyx voulait un SDK Kotlin, il l'aurait fait. Nous, on le fait
// pour les développeurs qui écrivent sur EPD, pas pour une entreprise.
// Ce code est libre — utilisez-le, améliorez-le, partagez-le.
// Projet Parnasse Numérique — Juillet 2026

/**
 * Événement stylet unifié. Remplace les 4 callbacks RawInputCallback.
 */
sealed class StylusEvent {
    abstract val x: Float
    abstract val y: Float
    abstract val pressure: Float
    abstract val timestamp: Long

    data class Down(
        override val x: Float,
        override val y: Float,
        override val pressure: Float,
        override val timestamp: Long,
        val eraser: Boolean = false
    ) : StylusEvent()

    data class Move(
        override val x: Float,
        override val y: Float,
        override val pressure: Float,
        override val timestamp: Long
    ) : StylusEvent()

    data class Up(
        override val x: Float,
        override val y: Float,
        override val pressure: Float,
        override val timestamp: Long,
        val eraser: Boolean = false
    ) : StylusEvent()
}

/**
 * Style de rendu plume. Mappe les constantes TouchHelper.
 */
enum class PenStyle(val onyxConstant: Int) {
    FOUNTAIN(TouchHelper.STROKE_STYLE_FOUNTAIN),
    BRUSH(TouchHelper.STROKE_STYLE_BRUSH),
    PENCIL(TouchHelper.STROKE_STYLE_PENCIL),
    MARKER(TouchHelper.STROKE_STYLE_MARKER)
}

/**
 * Pont Kotlin → SDK Onyx TouchHelper.
 *
 * Encapsule toute la tuyauterie : callbacks RawInputCallback → Flow<StylusEvent>,
 * cycle de vie (open/close), activation/désactivation du rendu, effacement surface.
 */
class OnyxBridge(
    private val context: Context,
    private val surfaceView: SurfaceView
) : AutoCloseable {

    private var touchHelper: TouchHelper? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _events = MutableSharedFlow<StylusEvent>(extraBufferCapacity = 256)
    private val _rawEvents = MutableSharedFlow<List<StylusEvent>>(extraBufferCapacity = 64)

    /** Flow principal : un événement stylet à la fois. */
    val stylusEvents: Flow<StylusEvent> = _events.asSharedFlow()

    /** Flow batch : liste d'événements reçus par `onRawDrawingTouchPointListReceived`. */
    val stylusBatchEvents: Flow<List<StylusEvent>> = _rawEvents.asSharedFlow()

    /** true si le stylet est en contact avec la surface. */
    var isStylusDown: Boolean = false
        private set

    // ═══════════════════════════════════════════════════════════════
    // CYCLE DE VIE
    // ═══════════════════════════════════════════════════════════════

    /**
     * Initialise le TouchHelper et commence à émettre les événements stylet.
     * Appelé automatiquement par `use { }`.
     */
    fun open(style: PenStyle = PenStyle.FOUNTAIN, widthDp: Float = 3f, color: Int = Color.BLACK) {
        if (touchHelper != null) return
        val density = context.resources.displayMetrics.density

        val callback = object : RawInputCallback() {
            override fun onBeginRawDrawing(eraser: Boolean, tp: OnyxTouchPoint) {
                isStylusDown = true
                _events.tryEmit(StylusEvent.Down(tp.x, tp.y, normalize(tp.pressure), tp.timestamp, eraser))
            }

            override fun onRawDrawingTouchPointMoveReceived(tp: OnyxTouchPoint?) {
                tp ?: return
                _events.tryEmit(StylusEvent.Move(tp.x, tp.y, normalize(tp.pressure), tp.timestamp))
            }

            override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {
                if (list == null || list.size() == 0) return
                val batch = (0 until list.size()).mapNotNull { i ->
                    val pt = list.get(i) ?: return@mapNotNull null
                    StylusEvent.Move(pt.x, pt.y, normalize(pt.pressure), pt.timestamp)
                }
                _rawEvents.tryEmit(batch)
            }

            override fun onEndRawDrawing(eraser: Boolean, tp: OnyxTouchPoint) {
                isStylusDown = false
                _events.tryEmit(StylusEvent.Up(tp.x, tp.y, normalize(tp.pressure), tp.timestamp, eraser))
            }

            override fun onBeginRawErasing(eraser: Boolean, tp: OnyxTouchPoint) {}
            override fun onEndRawErasing(eraser: Boolean, tp: OnyxTouchPoint) {}
            override fun onRawErasingTouchPointMoveReceived(tp: OnyxTouchPoint) {}
            override fun onRawErasingTouchPointListReceived(list: TouchPointList) {}
        }

        touchHelper = TouchHelper.create(surfaceView, TouchHelper.FEATURE_APP_TOUCH_RENDER, callback).apply {
            setRawInputReaderEnable(true)
            setBrushRawDrawingEnabled(true)
            openRawDrawing()
            setStrokeStyle(style.onyxConstant)
            setStrokeWidth(widthDp * density)
            setStrokeColor(color)
            setRawDrawingRenderEnabled(true)
            setRawDrawingEnabled(true)
        }
    }

    /** Suspend le rendu (garde la capture active). */
    fun suspendRendering() {
        try { touchHelper?.setRawDrawingEnabled(false) } catch (_: Exception) {}
    }

    /** Réactive le rendu. */
    fun resumeRendering() {
        try { touchHelper?.setRawDrawingEnabled(true) } catch (_: Exception) {}
    }

    /** Efface la SurfaceView. */
    fun clearSurface() {
        var canvas: Canvas? = null
        try {
            canvas = surfaceView.holder.lockCanvas()
            canvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        } catch (_: Exception) {
        } finally {
            try { canvas?.let { surfaceView.holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }

    /**
     * Désactive tout le rendu et efface la surface.
     * Appeler avant de passer en mode interaction (blobs, drag, correction).
     */
    fun pauseForInteraction() {
        suspendRendering()
        clearSurface()
    }

    /**
     * Réactive le rendu après une interaction.
     */
    fun resumeFromInteraction(style: PenStyle = PenStyle.FOUNTAIN, widthDp: Float = 3f, color: Int = Color.BLACK) {
        val density = context.resources.displayMetrics.density
        try {
            touchHelper?.apply {
                setStrokeStyle(style.onyxConstant)
                setStrokeWidth(widthDp * density)
                setStrokeColor(color)
                setRawDrawingRenderEnabled(true)
                setRawDrawingEnabled(true)
            }
        } catch (_: Exception) {}
    }

    /** Libère toutes les ressources. */
    override fun close() {
        try {
            touchHelper?.closeRawDrawing()
            touchHelper?.setRawDrawingEnabled(false)
        } catch (_: Exception) {}
        touchHelper = null
        scope.cancel()
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    /** Normalise la pression Onyx (0-4095 → 0-1). */
    private fun normalize(raw: Float): Float =
        if (raw > 1.0f) (raw / 4095.0f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
}
