package com.parnasse.miroir

import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint as OnyxTouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * Implémentation Onyx Boox de [StylusPort] — utilise TouchHelper pour le raw drawing hardware.
 *
 * Encapsule TOUTE la dépendance au SDK Onyx. Si cette classe n'est pas chargeable
 * (appareil non-Boox), le fallback [AndroidStylusPort] prend le relais.
 */
class OnyxStylusPort : StylusPort {

    companion object {
        private const val TAG = "Miroir/OnyxStylus"
        /** Vérifie si le SDK Onyx est disponible sur cet appareil. */
        fun isAvailable(): Boolean {
            return try {
                Class.forName("com.onyx.android.sdk.pen.TouchHelper")
                true
            } catch (_: Exception) { false }
            }
    }

    private var view: View? = null
    private var touchHelper: TouchHelper? = null
    private var callback: StylusPort.Callback? = null
    private var rawDrawingEnabled = false
    private var stylusOnly = true
    private var strokeActive = false

    override fun init(view: View) {
        this.view = view
        try {
            touchHelper = TouchHelper.create(view, object : RawInputCallback() {
                override fun onBeginRawDrawing(eraser: Boolean, touchPoint: OnyxTouchPoint) {
                    strokeActive = true
                    callback?.onStylusDown(
                        touchPoint.x, touchPoint.y,
                        normalizePressure(touchPoint.pressure),
                        touchPoint.timestamp
                    )
                }

                override fun onRawDrawingTouchPointMoveReceived(touchPoint: OnyxTouchPoint) {
                    if (strokeActive) {
                        callback?.onStylusMove(
                            touchPoint.x, touchPoint.y,
                            normalizePressure(touchPoint.pressure),
                            touchPoint.timestamp
                        )
                    }
                }

                override fun onRawDrawingTouchPointListReceived(touchPointList: TouchPointList) {
                    if (strokeActive) {
                        for (i in 0 until touchPointList.size()) {
                            touchPointList.get(i)?.let { pt ->
                                callback?.onStylusMove(
                                    pt.x, pt.y,
                                    normalizePressure(pt.pressure),
                                    pt.timestamp
                                )
                            }
                        }
                    }
                }

                override fun onEndRawDrawing(eraser: Boolean, touchPoint: OnyxTouchPoint) {
                    strokeActive = false
                    callback?.onStylusUp(
                        touchPoint.x, touchPoint.y,
                        normalizePressure(touchPoint.pressure),
                        touchPoint.timestamp
                    )
                }

                override fun onBeginRawErasing(eraser: Boolean, touchPoint: OnyxTouchPoint) {}
                override fun onEndRawErasing(eraser: Boolean, touchPoint: OnyxTouchPoint) {}
                override fun onRawErasingTouchPointMoveReceived(touchPoint: OnyxTouchPoint) {}
                override fun onRawErasingTouchPointListReceived(touchPointList: TouchPointList) {}
            })
            touchHelper!!.enableFingerTouch(!stylusOnly)
            Log.i(TAG, "TouchHelper initialisé")
        } catch (e: Exception) {
            Log.e(TAG, "Échec TouchHelper: ${e.message}")
        }
    }

    fun setCallback(cb: StylusPort.Callback) { callback = cb }

    fun setStylusOnly(enabled: Boolean) {
        stylusOnly = enabled
        touchHelper?.enableFingerTouch(!enabled)
    }

    override fun onStylusPoint(x: Float, y: Float, pressure: Float, action: Int, timestamp: Long) {
        // Non utilisé — TouchHelper gère les événements directement
    }

    override fun setActive(active: Boolean) {
        rawDrawingEnabled = active
        try {
            touchHelper?.setRawDrawingEnabled(active)
            if (active) {
                touchHelper?.openRawDrawing()
                touchHelper?.setRawDrawingRenderEnabled(true)
            } else {
                touchHelper?.closeRawDrawing()
            }
        } catch (e: Exception) {
            Log.w(TAG, "setActive($active): ${e.message}")
        }
    }

    override fun release() {
        setActive(false)
        touchHelper = null
        view = null
        callback = null
    }

    override fun isRawDrawingActive(): Boolean = rawDrawingEnabled

    fun getTouchHelper(): TouchHelper? = touchHelper

    private fun normalizePressure(raw: Float): Float {
        return if (raw > 1.0f) (raw / 4095.0f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
    }
}
