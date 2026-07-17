package com.parnasse.miroir

import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * Implémentation Android standard de [StylusPort] — fallback universel.
 *
 * Fonctionne sur tout appareil Android sans dépendance Onyx.
 * Utilise les MotionEvent standards (pas de raw drawing hardware).
 */
class AndroidStylusPort : StylusPort {

    companion object {
        private const val TAG = "Miroir/AndroidStylus"
    }

    private var view: View? = null
    private var callback: StylusPort.Callback? = null
    private var active = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastPressure = 0f
    private var isDown = false

    override fun init(view: View) {
        this.view = view
        Log.i(TAG, "Initialisé (fallback Android standard)")
    }

    fun setCallback(cb: StylusPort.Callback) { callback = cb }

    /**
     * À appeler depuis onTouchEvent() de l'IME.
     * Convertit les MotionEvent en appels StylusPort.Callback.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false
        val x = event.x
        val y = event.y
        val pressure = event.pressure.coerceIn(0f, 1f)
        val time = event.eventTime

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true
                lastX = x; lastY = y; lastPressure = pressure
                callback?.onStylusDown(x, y, pressure, time)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDown) return false
                // Éviter les doublons (même position que le dernier point)
                if (x == lastX && y == lastY) return true
                lastX = x; lastY = y; lastPressure = pressure
                callback?.onStylusMove(x, y, pressure, time)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDown) return false
                isDown = false
                callback?.onStylusUp(x, y, pressure, time)
            }
        }
        return true
    }

    override fun onStylusPoint(x: Float, y: Float, pressure: Float, action: Int, timestamp: Long) {
        // Conversion MotionEvent → callback
        when (action) {
            MotionEvent.ACTION_DOWN -> callback?.onStylusDown(x, y, pressure, timestamp)
            MotionEvent.ACTION_MOVE -> callback?.onStylusMove(x, y, pressure, timestamp)
            MotionEvent.ACTION_UP -> callback?.onStylusUp(x, y, pressure, timestamp)
        }
    }

    override fun setActive(active: Boolean) {
        this.active = active
        if (!active) isDown = false
    }

    override fun release() {
        active = false
        view = null
        callback = null
    }

    override fun isRawDrawingActive(): Boolean = false
}
