package com.parnasse.miroir

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceView

/**
 * Overlay EPD transparent — canal de rafraîchissement indépendant de l'IME.
 *
 * Placé au-dessus de [CaptureSurfaceView] avec [set] = true.
 * Intercepte les événements stylet et les forwarde vers :
 *   1. TouchHelper → raw drawing hardware (near-zero latency, trait DU)
 *   2. MiroirIME → rastérisation bitmap (permanent)
 *
 * Le trait hardware est effacé après rastérisation (clearHardwareScribble).
 * Inspiré d'OpenInkBridgeOverlayCanvas.
 */
class EpdOverlayView(context: Context) : SurfaceView(context) {

    companion object {
        private const val TAG = "Miroir/EpdOverlay"
    }

    /** Callback pour forwarder les points stylet vers MiroirIME. */
    var onStrokePoint: ((x: Float, y: Float, pressure: Float, action: Int, timestamp: Long) -> Unit)? = null
    var onHoverEnter: (() -> Unit)? = null
    var onHoverExit: (() -> Unit)? = null

    private var stylusOnly = true
    private var isDrawing = false
    private var strokeColor = Color.BLACK
    private var strokeWidth = 3f

    /** Port EPD pour le contrôle du mode de rafraîchissement. */
    var epdPort: EpdPort? = null

    init {
        setWillNotDraw(false)  // permet onDraw() sur SurfaceView
        setZOrderMediaOverlay(true)   // au-dessus de l'app hôte
        holder.setFormat(PixelFormat.TRANSPARENT)  // fond transparent
        Log.i(TAG, "EpdOverlayView créé — SurfaceView transparent ")
    }

    fun configureStroke(color: Int, width: Float) {
        strokeColor = color
        strokeWidth = width
    }

    fun setStylusOnly(enabled: Boolean) {
        stylusOnly = enabled
        // TouchHelper sera configuré dans MiroirIME
    }

    /**
     * Forwarde les événements MOTION EVENT bruts vers le TouchHelper
     * et les convertit en points logiques pour MiroirIME.
     */
    fun forwardTouchEvent(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (stylusOnly && !isStylus) return false

        val x = event.x
        val y = event.y
        val pressure = event.pressure.coerceIn(0f, 1f)
        val time = event.eventTime

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDrawing = true
                epdPort?.setDefaultMode(DisplayMode.DU)  // ═══ DU pour le trait ═══
                onStrokePoint?.invoke(x, y, pressure, MotionEvent.ACTION_DOWN, time)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDrawing) return false
                // Points historiques (haute fréquence stylet)
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(0, i)
                    val hy = event.getHistoricalY(0, i)
                    val hp = event.getHistoricalPressure(0, i).coerceIn(0f, 1f)
                    val ht = event.getHistoricalEventTime(i)
                    onStrokePoint?.invoke(hx, hy, hp, MotionEvent.ACTION_MOVE, ht)
                }
                onStrokePoint?.invoke(x, y, pressure, MotionEvent.ACTION_MOVE, time)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDrawing) return false
                isDrawing = false
                onStrokePoint?.invoke(x, y, pressure, MotionEvent.ACTION_UP, time)
            }
        }
        invalidate() // déclenche onDraw() → raw drawing du TouchHelper
        return true
    }

    fun onHover(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        val isStylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (!isStylus) return false
        when (event.action) {
            MotionEvent.ACTION_HOVER_ENTER -> onHoverEnter?.invoke()
            MotionEvent.ACTION_HOVER_EXIT -> onHoverExit?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Le raw drawing est géré par TouchHelper (matériel).
        // onDraw() n'est appelé que pour les overlays logiciels (fallback).
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Ne pas consommer — laisser descendre vers la vue IME en dessous
        return false
    }
}
