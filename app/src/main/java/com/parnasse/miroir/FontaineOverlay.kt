package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * FontaineOverlay — SurfaceView transparente superposée pour la capture en mode FONTAINE.
 *
 * Le hardware Onyx rend les strokes en style plume directement sur cette surface.
 * Les callbacks TouchHelper capturent les points pour MiroirEngine (groupes, inférence).
 *
 * Cette vue est transparente et positionnée AU-DESSUS de la View standard
 * qui gère tout le reste (blobs, labels, template, strokes passés).
 *
 * Cycle :
 *   1. Écriture → fontaine rend le trait en temps réel + capture les points
 *   2. Stroke terminé → callback onStrokeFinished → inférence
 *   3. Après rafraîchissement de la View standard → effacer cette surface
 *   4. Prêt pour le prochain stroke
 */
class FontaineOverlay(context: Context, private val engine: MiroirEngine) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "Miroir/Fontaine"
    }

    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    private var isStylusDown = false
    private var surfaceReady = false

    /** Appelé quand un stroke est terminé (pour lancer l'inférence). */
    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)                    // au-dessus de la View standard
        holder.setFormat(PixelFormat.TRANSLUCENT) // fond transparent
    }

    // ═══════════════════════════════════════════════════════════════════
    // SURFACE HOLDER
    // ═══════════════════════════════════════════════════════════════════

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        Log.i(TAG, "Surface fontaine créée")
        initTouchHelper()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        Log.d(TAG, "Surface fontaine: ${w}x${h}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseTouchHelper()
        Log.i(TAG, "Surface fontaine détruite")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH HELPER — MODE FONTAINE
    // ═══════════════════════════════════════════════════════════════════

    private fun initTouchHelper() {
        if (touchHelper != null) return
        try {
            touchHelper = com.onyx.android.sdk.pen.TouchHelper.create(this,
                object : com.onyx.android.sdk.pen.RawInputCallback() {
                    override fun onBeginRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                        isStylusDown = true
                        engine.beginStroke(tp.x, tp.y)
                    }
                    override fun onRawDrawingTouchPointMoveReceived(tp: com.onyx.android.sdk.data.note.TouchPoint?) {
                        if (!isStylusDown || tp == null) return
                        engine.addStrokePoint(tp.x, tp.y, tp.pressure.coerceIn(0f, 1f))
                    }
                    override fun onRawDrawingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList?) {
                        if (!isStylusDown || list == null) return
                        for (i in 0 until list.size()) {
                            list.get(i)?.let { pt ->
                                engine.addStrokePoint(pt.x, pt.y, pt.pressure.coerceIn(0f, 1f))
                            }
                        }
                    }
                    override fun onEndRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                        if (!isStylusDown) return
                        isStylusDown = false
                        engine.addStrokePoint(tp.x, tp.y, tp.pressure.coerceIn(0f, 1f))
                        val ri = engine.endStroke()
                        if (ri >= 0) {
                            onStrokeFinished?.invoke(ri)
                        }
                        // L'effacement sera fait par CaptureActivity après l'inférence
                    }
                    override fun onBeginRawErasing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                    override fun onEndRawErasing(p0: Boolean, p1: com.onyx.android.sdk.data.note.TouchPoint) {}
                    override fun onRawErasingTouchPointMoveReceived(p0: com.onyx.android.sdk.data.note.TouchPoint) {}
                    override fun onRawErasingTouchPointListReceived(p0: com.onyx.android.sdk.pen.data.TouchPointList) {}
                })
            touchHelper!!.openRawDrawing()
            touchHelper!!.setRawInputReaderEnable(true)
            touchHelper!!.setRawDrawingRenderEnabled(true)
            touchHelper!!.setRawDrawingEnabled(true)
            Log.i(TAG, "TouchHelper FONTAINE actif")
        } catch (e: Exception) {
            touchHelper = null
            Log.w(TAG, "TouchHelper FONTAINE indisponible: ${e.message}")
        }
    }

    private fun releaseTouchHelper() {
        try {
            touchHelper?.closeRawDrawing()
            touchHelper?.setRawDrawingEnabled(false)
        } catch (_: Exception) {}
        touchHelper = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // EFFACEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Efface le contenu de la surface fontaine.
     * Appelé après que la View standard a rafraîchi son rendu,
     * pour éviter le double affichage du stroke.
     */
    fun effacer() {
        if (!surfaceReady) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }
}
