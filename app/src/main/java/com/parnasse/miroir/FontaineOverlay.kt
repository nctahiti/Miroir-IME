package com.parnasse.miroir

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs

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
        private const val SWIPE_THRESHOLD = 30f
    }

    private var touchHelper: com.onyx.android.sdk.pen.TouchHelper? = null
    internal var isStylusDown = false
    private var surfaceReady = false
    private var strokeCount = 0
    private var strokeStarted = false  // true après le premier MOVE (différé)
    private var beginX = 0f; private var beginY = 0f; private var beginPressure = 0f
    private var longPressTimer: java.lang.Runnable? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastLPX = 0f; private var lastLPY = 0f
    private var lpTotalDist = 0f  // distance totale depuis BEGIN

    // Déduplication des points (Move vs List peuvent envoyer les mêmes points)
    private val processedPoints = mutableSetOf<String>()

    // ── Gesture tracking (post long-press) ──────────────────────────
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureMode: String? = null  // null, "erase", "move"
    private var lastGestureX = 0f
    private var lastGestureY = 0f

    var onStrokeFinished: ((registryIndex: Int) -> Unit)? = null
    /** Appelé au début de chaque stroke — pour annuler les timers d'affichage. */
    var onStrokeBegin: (() -> Unit)? = null
    /** Appelé quand un long-press est détecté (500ms immobile). */
    var onLongPressDetected: ((x: Float, y: Float) -> Unit)? = null
    /** Appelé quand un geste (swipe après long-press) est détecté. */
    var onGestureDetected: ((mode: String, x: Float, y: Float) -> Unit)? = null
    /** Appelé à chaque mouvement pendant un geste d'édition. */
    var onGestureMove: ((x: Float, y: Float, dx: Float, dy: Float, mode: String) -> Unit)? = null
    /** Appelé à la fin du geste (stylet levé). */
    var onGestureEnd: (() -> Unit)? = null
    var modeInteraction: Boolean = false

    /** Cible de forward des événements tactiles en mode interaction. */
    var touchForwardTarget: android.view.View? = null

    private var strokeColor = Color.BLACK
    private var strokeWidthDp = STROKE_WIDTH_DP

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)  // TRANSLUCENT pour compatibilité Onyx
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
                    strokeStarted = false  // différé — attendre le premier MOVE
                    beginX = tp.x; beginY = tp.y
                    beginPressure = normalizePressure(tp.pressure)
                    lastLPX = tp.x; lastLPY = tp.y
                    processedPoints.clear()
                    addPoint(tp)
                    // ⚠️ Pas de beginStroke() ici — différé au premier MOVE
                    onStrokeBegin?.invoke()
                    armLongPressTimer(tp.x, tp.y)
                    gestureStartX = tp.x; gestureStartY = tp.y
                    lastGestureX = tp.x; lastGestureY = tp.y
                    gestureMode = null
                }

                override fun onRawDrawingTouchPointMoveReceived(tp: com.onyx.android.sdk.data.note.TouchPoint?) {
                    keepRawDrawingActive()
                    if (tp != null) {
                        if (modeInteraction) {
                            handleGestureMove(tp.x, tp.y)
                        } else {
                            if (!strokeStarted) startDeferredStroke()
                            lpTotalDist += Math.hypot((tp.x - lastLPX).toDouble(), (tp.y - lastLPY).toDouble()).toFloat()
                            lastLPX = tp.x; lastLPY = tp.y
                        }
                    }
                }

                override fun onRawDrawingTouchPointListReceived(list: com.onyx.android.sdk.pen.data.TouchPointList?) {
                    keepRawDrawingActive()
                    if (!isStylusDown || list == null) return
                    for (i in 0 until list.size()) {
                        val pt = list.get(i) ?: continue
                        if (modeInteraction) {
                            handleGestureMove(pt.x, pt.y)
                        } else {
                            if (!strokeStarted) startDeferredStroke()
                            lpTotalDist += Math.hypot((pt.x - lastLPX).toDouble(), (pt.y - lastLPY).toDouble()).toFloat()
                            lastLPX = pt.x; lastLPY = pt.y
                            if (addPoint(pt)) {
                                engine.addStrokePoint(pt.x, pt.y, normalizePressure(pt.pressure))
                            }
                        }
                    }
                }

                override fun onEndRawDrawing(eraser: Boolean, tp: com.onyx.android.sdk.data.note.TouchPoint) {
                    cancelLongPressTimer()
                    if (!isStylusDown) return
                    isStylusDown = false
                    if (modeInteraction) {
                        onGestureEnd?.invoke()
                        gestureMode = null
                        Log.i(TAG, "🖊️ END   (geste) mode=$gestureMode")
                    } else if (!strokeStarted) {
                        // Tap sans mouvement → pas de stroke créé
                        Log.d(TAG, "🖊️ END   #$strokeCount (tap, pas de stroke)")
                        processedPoints.clear()
                    } else {
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
                    strokeStarted = false
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

    /** Crée le stroke au premier MOVE (différé depuis le BEGIN). */
    private fun startDeferredStroke() {
        strokeStarted = true
        engine.beginStroke(beginX, beginY, beginPressure)
        Log.v(TAG, "Stroke #$strokeCount commencé (différé)")
    }

    /** Maintient le raw drawing actif — appelé dans chaque callback. */
    private fun keepRawDrawingActive() {
        try {
            touchHelper?.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════════
    // LONG-PRESS (500ms immobile)
    // ═══════════════════════════════════════════════════════════════════

    private fun armLongPressTimer(x: Float, y: Float) {
        cancelLongPressTimer()
        lpTotalDist = 0f
        longPressTimer = java.lang.Runnable {
            // Après 500ms → si la distance totale < 20px, c'est un long-press
            if (lpTotalDist < 20f) {
                // Annuler le stroke en cours (s'il n'a pas encore été créé, tant mieux)
                if (!strokeStarted) {
                    engine.cancelStroke()  // nettoie currentStrokeRecord/currentPath
                }
                Log.i(TAG, "Long-press détecté à ($x, $y) — dist=${lpTotalDist.toInt()}px")
                onLongPressDetected?.invoke(x, y)
            } else {
                Log.d(TAG, "Long-press ignoré — dist=${lpTotalDist.toInt()}px (écriture)")
            }
        }
        uiHandler.postDelayed(longPressTimer!!, 500L)
    }

    private fun cancelLongPressTimer() {
        longPressTimer?.let { uiHandler.removeCallbacks(it) }
        longPressTimer = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // GESTURE DETECTION (post long-press)
    // ═══════════════════════════════════════════════════════════════════

    /** Appelé depuis les Move callbacks quand modeInteraction=true. */
    private fun handleGestureMove(x: Float, y: Float) {
        if (gestureMode == null) {
            // Détecter la direction du geste
            val dx = x - gestureStartX
            val dy = y - gestureStartY
            if (dx < -SWIPE_THRESHOLD) {
                gestureMode = "erase"
                Log.i(TAG, "→ Geste EFFACEMENT (←) dx=$dx")
                onGestureDetected?.invoke("erase", x, y)
            } else if (dy > SWIPE_THRESHOLD) {
                gestureMode = "move"
                Log.i(TAG, "→ Geste DÉPLACEMENT (↓) dy=$dy")
                onGestureDetected?.invoke("move", x, y)
            }
        } else {
            // Mouvement continu pendant le geste
            val dx = x - lastGestureX
            val dy = y - lastGestureY
            if (abs(dx) > 0.5f || abs(dy) > 0.5f) {
                onGestureMove?.invoke(x, y, dx, dy, gestureMode!!)
            }
        }
        lastGestureX = x; lastGestureY = y
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

    /** Désactive le rendu → désactive raw drawing (sans fermer le canal) + efface la surface. */
    fun desactiver() {
        try {
            touchHelper?.setRawDrawingEnabled(false)
        } catch (_: Exception) {}
        effacerSurface()
        Log.d(TAG, "Fontaine désactivée")
    }

    /** Réactive — rapide : juste openRawDrawing + essentiel. */
    fun activer() {
        try {
            val th = touchHelper ?: return
            th.openRawDrawing()
            th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)
            val density = resources.displayMetrics.density
            th.setStrokeWidth(strokeWidthDp * density)
            th.setStrokeColor(strokeColor)
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
        Log.d(TAG, "Fontaine réactivée")
    }

    /** Réactive — le canal raw drawing n'a jamais été fermé, juste désactivé. */
    fun reactiver() {
        try {
            val th = touchHelper ?: return
            // Pas besoin de openRawDrawing() — le canal est resté ouvert
            th.setStrokeStyle(com.onyx.android.sdk.pen.TouchHelper.STROKE_STYLE_FOUNTAIN)
            val density = resources.displayMetrics.density
            th.setStrokeWidth(strokeWidthDp * density)
            th.setStrokeColor(strokeColor)
            th.setRawDrawingRenderEnabled(true)
            th.setRawDrawingEnabled(true)
        } catch (_: Exception) {}
        Log.i(TAG, "Fontaine réactivée (canal préservé)")
    }

    // ═══════════════════════════════════════════════════════════════════
    // EFFACEMENT
    // ═══════════════════════════════════════════════════════════════════

    fun effacerSurface() {
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

    /** Remplit la SurfaceView en blanc opaque (pour le retour écriture). */
    fun remplirBlanc() {
        if (!surfaceReady) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.WHITE)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }

    /** Dessine un blob dans la SurfaceView (phare pendant l'écriture). */
    fun dessinerBlob(path: android.graphics.Path, bounds: RectF, fillPaint: android.graphics.Paint, borderPaint: android.graphics.Paint) {
        if (!surfaceReady) return
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.WHITE)  // fond blanc
                canvas.drawPath(path, fillPaint)
                canvas.drawPath(path, borderPaint)
            }
        } catch (_: Exception) {} finally {
            try { canvas?.let { holder.unlockCanvasAndPost(it) } } catch (_: Exception) {}
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOUCH — en mode interaction, on laisse tout passer à la View standard
    // ═══════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (modeInteraction) {
            // Bascule franche : forwarder à la View standard pour les gestes d'édition
            touchForwardTarget?.dispatchTouchEvent(event)
            return true
        }
        // En mode écriture, consommer pour éviter les taps parasites.
        return true
    }

    override fun onHoverEvent(event: android.view.MotionEvent): Boolean {
        if (modeInteraction) return false
        return super.onHoverEvent(event)
    }
}
