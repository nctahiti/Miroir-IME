package com.parnasse.miroir

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * FontaineOverlay — SurfaceView transparente pour la capture en mode FONTAINE.
 *
 * Placée entre la View standard et les overlays (setZOrderMediaOverlay).
 * Le hardware Onyx rend les strokes en style plume sur cette surface.
 * Quand la surface est effacée (transparent), la View standard en dessous est visible.
 */
class FontaineOverlay(context: Context, private val engine: MiroirEngine) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "Miroir/Fontaine"
    }

    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    private var isStylusDown = false
    private var surfaceReady = false
    private var strokeCount = 0  // compteur pour tracer les strokes

    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)                     // au-dessus de tout
        holder.setFormat(PixelFormat.TRANSLUCENT) // transparent → laisse voir dessous
    }

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

    private fun initTouchHelper() {
        if (touchHelper != null) return
        try {
            touchHelper = com.onyx.android.sdk.pen.TouchHelper.create(this,
                object : com.onyx.android.sdk.pen.RawInputCallback() {
                    override fun onBeginRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                        strokeCount++
                        Log.i(TAG, "🖊️ BEGIN #$strokeCount eraser=$eraser x=${tp.x.toInt()} y=${tp.y.toInt()}")
                        isStylusDown = true
                        engine.beginStroke(tp.x, tp.y)
                    }
                    override fun onRawDrawingTouchPointMoveReceived(tp: com.onyx.android.sdk.data.note.TouchPoint?) {
                        // ⚠️ Ignoré — on utilise onRawDrawingTouchPointListReceived pour éviter les doublons
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
                        Log.i(TAG, "🖊️ END   #$strokeCount eraser=$eraser pts=${engine.currentStrokeRecord?.activePoints ?: 0}")
                        if (!isStylusDown) {
                            Log.w(TAG, "⚠️ END sans BEGIN (isStylusDown=false)")
                            return
                        }
                        isStylusDown = false
                        val ptCount = engine.currentStrokeRecord?.activePoints ?: 0
                        val ri = engine.endStroke()
                        // Filtre anti-bruit : stroke trop court → ignoré
                        if (ri >= 0 && ptCount >= 10) {
                            onStrokeFinished?.invoke(ri)
                        } else if (ri >= 0) {
                            Log.d(TAG, "Stroke ignoré (${ptCount} pts)")
                        }
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
    // ACTIVATION / DÉSACTIVATION
    // ═══════════════════════════════════════════════════════════════════

    /** Désactive le rendu → surface effacée → View standard visible. */
    fun desactiver() {
        try {
            touchHelper?.closeRawDrawing()
        } catch (_: Exception) {}
        effacerSurface()
        Log.d(TAG, "Fontaine désactivée")
    }

    /** Réactive le rendu — prêt pour le prochain stroke (zéro latence). */
    fun activer() {
        try {
            touchHelper?.openRawDrawing()
            touchHelper?.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
        Log.d(TAG, "Fontaine réactivée")
    }

    private fun effacerSurface() {
        if (!surfaceReady) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }
}
