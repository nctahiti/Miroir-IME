package com.parnasse.miroir

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * FontaineOverlay — SurfaceView transparente pour la capture en mode FONTAINE.
 *
 * Implémentation canonique basée sur OpenInkBridge (GoVed/OpenInkBridge).
 * Utilise TouchHelper.STROKE_STYLE_FOUNTAIN pour le vrai rendu plume.
 *
 * Cycle :
 *   1. Écriture → hardware rend le trait en style FOUNTAIN + capture les points
 *   2. Stroke terminé → onStrokeFinished → inférence ML Kit
 *   3. Labels publiés → desactiver() (clearHardwareScribble + closeRawDrawing)
 *   4. Puis activer() immédiatement → prêt pour le prochain stroke
 */
class FontaineOverlay(context: Context, private val engine: MiroirEngine) : SurfaceView(context), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "Miroir/Fontaine"
        private const val STROKE_WIDTH_DP = 2f
    }

    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    private var isStylusDown = false
    private var surfaceReady = false
    private var strokeCount = 0

    // Déduplication des points (Move vs List peuvent envoyer les mêmes points)
    private val processedPoints = mutableSetOf<String>()

    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null
    /** Appelé au début de chaque stroke — pour annuler les timers d'affichage. */
    var onStrokeBegin: (() -> Unit)? = null
    var modeInteraction: Boolean = false

    private var strokeColor = Color.BLACK
    private var strokeWidthDp = STROKE_WIDTH_DP

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSPARENT)  // OpenInkBridge utilise TRANSPARENT
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
        // La vue est dimensionnée → on peut configurer maintenant
        if (w > 0 && h > 0) configureTouchHelper()
        Log.d(TAG, "Surface fontaine: ${w}x${h}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releaseTouchHelper()
        Log.i(TAG, "Surface fontaine détruite")
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH HELPER — MODE FONTAINE (API canonique OpenInkBridge)
    // ═══════════════════════════════════════════════════════════════════

    private fun initTouchHelper() {
        if (touchHelper != null) return
        try {
            val callback = object : com.onyx.android.sdk.pen.RawInputCallback() {
                override fun onBeginRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                    keepRawDrawingActive()
                    if (modeInteraction) return
                    strokeCount++
                    Log.i(TAG, "🖊️ BEGIN #$strokeCount eraser=$eraser x=${tp.x.toInt()} y=${tp.y.toInt()}")
                    isStylusDown = true
                    processedPoints.clear()
                    addPoint(tp)
                    engine.beginStroke(tp.x, tp.y, normalizePressure(tp.pressure))
                    // Annuler les timers d'affichage — on écrit, pas de refresh
                    onStrokeBegin?.invoke()
                }

                override fun onRawDrawingTouchPointMoveReceived(tp: com.onyx.android.sdk.data.note.TouchPoint?) {
                    keepRawDrawingActive()
                    if (!isStylusDown || tp == null) return
                    // Déduplication : on utilise aussi ListReceived, donc on ignore Move
                }

                override fun onRawDrawingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList?) {
                    keepRawDrawingActive()
                    if (!isStylusDown || list == null) return
                    for (i in 0 until list.size()) {
                        val pt = list.get(i) ?: continue
                        if (addPoint(pt)) {
                            engine.addStrokePoint(pt.x, pt.y, normalizePressure(pt.pressure))
                        }
                    }
                }

                override fun onEndRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                    if (!isStylusDown) return
                    isStylusDown = false
                    val ptCount = engine.currentStrokeRecord?.activePoints ?: 0
                    Log.i(TAG, "🖊️ END   #$strokeCount pts=$ptCount")
                    val ri = engine.endStroke()
                    processedPoints.clear()
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
            }

            // Constructeur 2-param (sans FEATURE flag) — le hardware rend
            touchHelper = com.onyx.android.sdk.pen.TouchHelper.create(this, callback)
            touchHelper!!.enableFingerTouch(true)  // false = stylet only, mais on veut les taps doigts

            // Désactiver le refresh auto au pen-up (on gère nous-mêmes)
            try {
                val method = touchHelper?.javaClass?.getMethod("setPenUpRefreshEnabled", Boolean::class.javaPrimitiveType)
                method?.invoke(touchHelper, false)
            } catch (_: Exception) {}

            // Configurer après le layout
            post { configureTouchHelper() }

            Log.i(TAG, "TouchHelper FONTAINE initialisé")
        } catch (e: Exception) {
            touchHelper = null
            Log.w(TAG, "TouchHelper FONTAINE indisponible: ${e.message}")
        }
    }

    /** Configure/reconfigure le TouchHelper avec l'ordre canonique OpenInkBridge. */
    private fun configureTouchHelper() {
        val th = touchHelper ?: return
        try {
            // 1. Ouvrir Raw Drawing (réinitialise tout)
            th.openRawDrawing()

            // 2. Limiter la zone de dessin
            val limitRect = android.graphics.Rect()
            getLocalVisibleRect(limitRect)
            if (limitRect.width() > 0 && limitRect.height() > 0) {
                th.setLimitRect(limitRect, emptyList())
            }

            // 3. Style FONTAINE (la vraie plume !)
            th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)

            // 4. Largeur physique (dp → px)
            val density = resources.displayMetrics.density
            val hwWidth = strokeWidthDp * density
            th.setStrokeWidth(hwWidth)

            // 5. Couleur
            th.setStrokeColor(strokeColor)

            // 6. Doigts
            th.enableFingerTouch(true)

            // 7. Rendu hardware
            th.setRawDrawingRenderEnabled(true)

            // 8. Activer
            th.setRawDrawingEnabled(true)

            Log.i(TAG, "TouchHelper configuré: FOUNTAIN w=$hwWidth limitRect=$limitRect")
        } catch (e: Exception) {
            Log.w(TAG, "Échec configuration TouchHelper: ${e.message}")
        }
    }

    private fun releaseTouchHelper() {
        try {
            touchHelper?.setRawDrawingEnabled(false)
            touchHelper?.closeRawDrawing()
        } catch (_: Exception) {}
        touchHelper = null
    }

    /** Maintient le raw drawing actif — appelé dans chaque callback. */
    private fun keepRawDrawingActive() {
        try {
            touchHelper?.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // POINTS
    // ═══════════════════════════════════════════════════════════════════

    /** Ajoute un point Onyx (avec déduplication). Retourne true si nouveau. */
    private fun addPoint(tp: com.onyx.android.sdk.data.note.TouchPoint): Boolean {
        val key = "${tp.timestamp}_${tp.x}_${tp.y}"
        return processedPoints.add(key)
    }

    /** Normalise la pression Onyx (0-4095 → 0-1). */
    private fun normalizePressure(raw: Float): Float {
        return if (raw > 1.0f) (raw / 4095.0f).coerceIn(0f, 1f) else raw.coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════
    // ACTIVATION / DÉSACTIVATION
    // ═══════════════════════════════════════════════════════════════════

    /** Désactive le rendu → ferme le raw drawing + efface la surface. */
    fun desactiver() {
        try {
            touchHelper?.closeRawDrawing()
        } catch (_: Exception) {}
        effacerSurface()
        Log.d(TAG, "Fontaine désactivée")
    }

    /** Réactive — rapide : juste openRawDrawing, pas de reconfiguration complète. */
    fun activer() {
        try {
            val th = touchHelper ?: return
            th.openRawDrawing()
            // Réappliquer juste l'essentiel (openRawDrawing() reset tout)
            th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)
            val density = resources.displayMetrics.density
            th.setStrokeWidth(strokeWidthDp * density)
            th.setStrokeColor(strokeColor)
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
        Log.d(TAG, "Fontaine réactivée")
    }

    // ═══════════════════════════════════════════════════════════════════
    // EFFACEMENT
    // ═══════════════════════════════════════════════════════════════════

    private fun effacerSurface() {
        if (!surfaceReady) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH — en mode interaction, on laisse tout passer à la View standard
    // ═══════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (modeInteraction) return false
        return super.onTouchEvent(event)
    }

    override fun onHoverEvent(event: android.view.MotionEvent): Boolean {
        if (modeInteraction) return false
        return super.onHoverEvent(event)
    }
}
