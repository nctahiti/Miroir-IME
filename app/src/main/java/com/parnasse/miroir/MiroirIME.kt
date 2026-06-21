package com.parnasse.miroir

import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
 * Architecture :
 *   TouchHelper (Onyx) → forward MotionEvent → onTouchEvent → strokes
 *   → GroupManager (blob elliptique) → groupes → ML Kit → labels + commit
 */
class MiroirIME : InputMethodService() {

    companion object {
        private const val TAG = "Miroir/IME"
        // 0 = plein écran (le système donne toute la hauteur disponible)
        private const val IME_HEIGHT_DP = 0
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

    // ── GroupManager — groupement spatial par blob ─────────────────────
    private var groupManager: GroupManager? = null
    // Map firstIdx → texte reconnu (labels)
    private val groupLabels = mutableMapOf<Int, String>()
    private val labelPaint = Paint().apply {
        color = Color.BLACK  // noir pour visibilité e-ink
        textSize = 28f
        isAntiAlias = true
        typeface = Typeface.DEFAULT_BOLD
        // Fond blanc léger derrière le texte pour lisibilité
        setShadowLayer(3f, 1f, 1f, Color.argb(200, 255, 255, 255))
    }
    // Timer d'inactivité stylet global (pour différer l'inférence)
    private var lastStylusActivity = 0L
    private var inactivityCheckScheduled = false

    // ── Vue IME ────────────────────────────────────────────────────────
    private var imeView: CaptureSurfaceView? = null

    // ── Barre d'outils ─────────────────────────────────────────────────
    private var showOverlays = true  // 👁 toggle

    // ── Survol (sélection de groupe) — importé de CaptureView ──────────
    private var hoverX = 0f
    private var hoverY = 0f
    private var isHovering = false
    private var hoverWordGroup: List<Int>? = null
    private var hoverStrokeIndex: Int? = null
    private var longHoverStartMs = 0L
    private var longHoverFirstStroke: Int = -1
    private var selectedGroupId: String? = null

    // ── Cache spatial (comme CaptureView) ──────────────────────────────
    private var cachedSpatialGroups: List<List<Int>>? = null
    private var cachedSpatialBounds: List<android.graphics.RectF>? = null
    private var cachedGMCacheSize: Int = -1

    // ── Blob ───────────────────────────────────────────────────────────
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFA0A0A0.toInt()  // gris opaque, visible e-ink
        style = Paint.Style.FILL
    }

    // ── Cache performance ──────────────────────────────────────────────
    // Évite de recalculer le blob/partition à chaque frame onDraw()
    private var cachedBlobGroupId: String? = null
    private val cachedBlobOvals = mutableListOf<Triple<Float, Float, Float>>()
    private var cachedBlobRx = 0f
    private var cachedBlobRy = 0f
    private var cachedTemplateLines: List<Float> = emptyList()
    private var cachedTemplateHeight: Int = -1
    private val cachedLabelPositions = mutableMapOf<Int, Pair<Float, Float>>()

    // ═══ Throttled invalidate (comme CaptureView) ═══
    private var lastInvalidate = 0L
    private var pendingInvalidate = false

    /** Invalide avec un throttle de 30ms minimum entre redraws. */
    private fun throttledInvalidate() {
        if (pendingInvalidate) return
        val now = System.currentTimeMillis()
        val elapsed = now - lastInvalidate
        if (elapsed >= 30) {
            lastInvalidate = now
            imeView?.postInvalidate()
        } else {
            pendingInvalidate = true
            uiHandler.postDelayed({
                pendingInvalidate = false
                lastInvalidate = System.currentTimeMillis()
                imeView?.postInvalidate()
            }, 30 - elapsed)
        }
    }

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
        // Pré-calculer les positions (cache)
        val t = template
        if (t is Template.HorizontalStaff) {
            cachedTemplateLines = t.linePositions(canvasHeight)
        }
        cachedTemplateHeight = canvasHeight
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

        // ── GroupManager — groupement spatial par blob
        groupManager = GroupManager({ group ->
            // ⚠️ Callback vide — l'inférence est déclenchée par inactivité stylet.
            // Les groupes restent LOADED → absorption toujours active (comme Miroir).
        }).also {
            it.pointProvider = { strokeId ->
                inkStrokeIdToRegistryIndex[strokeId]
                    ?.let { idx -> strokeRegistry.getOrNull(idx)?.points }
            }
            // ═══ Persistance en mémoire (fichier temporaire) ═══
            val tmpDir = java.io.File(cacheDir, "ime-groups")
            tmpDir.mkdirs()
            it.persistence = GroupPersistence(java.io.File(tmpDir, "current.groups"))
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView — plein écran")

        val density = resources.displayMetrics.density
        val toolbarHeight = (80 * density).toInt()  // hauteur adaptée aux grands boutons

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            // Fond opaque — évite la composition coûteuse avec l'app en dessous
            setBackgroundColor(Color.WHITE)
        }

        // ── Barre d'outils EN HAUT ──────────────────────────────────
        val toolbar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight)
            setBackgroundColor(Color.argb(180, 240, 240, 240))
            gravity = android.view.Gravity.CENTER
        }

        fun makeButton(label: String, onClick: () -> Unit): android.widget.Button {
            return android.widget.Button(this).apply {
                text = label
                textSize = 22f  // ×5 par rapport à 12f
                setTextColor(Color.DKGRAY)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { onClick() }
            }
        }

        toolbar.addView(makeButton("✓") {
            val ic = currentInputConnection
            if (ic != null) ic.commitText("\n", 1)
            requestHideSelf(0)
        })

        toolbar.addView(makeButton("⚙") {
            // Ouvrir CalibrationActivity sans cacher l'IME
            val intent = android.content.Intent(this@MiroirIME, CalibrationActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        })

        toolbar.addView(makeButton("👁") {
            showOverlays = !showOverlays
            throttledInvalidate()
        })

        root.addView(toolbar)  // barre EN HAUT

        // ── Surface de capture (reste de l'écran) ───────────────────
        val surface = CaptureSurfaceView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f)  // weight=1 → prend tout l'espace restant
        }
        imeView = surface
        root.addView(surface)

        root.post {
            if (surface.width > 0 && surface.height > 0) {
                bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
                bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)  // transparent
                updateTemplateSpacing(surface.height)
            }
        }

        initTouchHelper(surface)
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.i(TAG, "onStartInputView — champ: ${info?.fieldName ?: "inconnu"}")
        syncGroupManagerParams()
        // Reconstruire le bitmap depuis les strokes existants
        rebuildBitmap()
    }

    /** Redessine tous les strokes du registre dans le bitmap. */
    private fun rebuildBitmap() {
        val canvas = bitmapCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (sr in strokeRegistry) {
            if (sr.points.size < 2) {
                if (sr.points.isNotEmpty()) {
                    val p = sr.points.first()
                    canvas.drawCircle(p.first, p.second, 1.5f, strokePaint.apply { style = Paint.Style.FILL })
                }
            } else {
                val path = Path()
                path.moveTo(sr.points[0].first, sr.points[0].second)
                for (i in 1 until sr.points.size) {
                    path.lineTo(sr.points[i].first, sr.points[i].second)
                }
                canvas.drawPath(path, strokePaint.apply { style = Paint.Style.STROKE })
            }
        }
        throttledInvalidate()
        // Invalider les caches pour refléter les groupes chargés
        cachedGMCacheSize = -1
        updateBlobCache()
    }

    /** Lit les paramètres de calibration et les applique au GroupManager. */
    private fun syncGroupManagerParams() {
        val gm = groupManager ?: return
        val calX = CalibrationActivity.getSpatialDistanceX(this)
        val calY = CalibrationActivity.getSpatialDistanceY(this)
        gm.params = gm.params.copy(
            spatialDistancePx = calX,
            spatialDistanceY = calY,
            transcriptionTimeoutMs = Long.MAX_VALUE  // jamais fermer — comme le Miroir
        )
        Log.d(TAG, "Params calibration: blobRx=$calX blobRy=$calY")
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
            if (showOverlays) {
                // Blob depuis le cache (recalculé seulement si groupe changé)
                for ((x, y, _) in cachedBlobOvals) {
                    canvas.drawOval(
                        x - cachedBlobRx, y - cachedBlobRy,
                        x + cachedBlobRx, y + cachedBlobRy, blobPaint)
                }
            }
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            if (showOverlays) {
                // Lignes de partition depuis le cache
                for (y in cachedTemplateLines) {
                    canvas.drawLine(0f, y, width.toFloat(), y, Template.GUIDE_PAINT)
                }
                // Labels de groupe
                drawGroupLabels(canvas)
            }
            canvas.drawPath(currentPath, strokePaint)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    updateHover(event.x, event.y)
                }
                MotionEvent.ACTION_DOWN -> {
                    isHovering = false; longHoverStartMs = 0L; longHoverFirstStroke = -1
                    onStylusDown(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        onStylusPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i))
                    }
                    onStylusPoint(event.x, event.y, event.pressure)
                }
                MotionEvent.ACTION_UP -> onStylusUp()
            }
            return true
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SPATIAL + SURVOL — importé de CaptureView (même mécanisme que playground)
    // ═══════════════════════════════════════════════════════════════════

    /** Cale Y sur la ligne de portée la plus proche. */
    private fun snapToLine(y: Float): Float {
        if (cachedTemplateLines.isEmpty()) return y
        var best = cachedTemplateLines.first()
        var bestDist = Math.abs(y - best)
        for (line in cachedTemplateLines) {
            val dist = Math.abs(y - line)
            if (dist < bestDist) { bestDist = dist; best = line }
        }
        return best
    }

    /** Groupes spatiaux depuis GroupManager (source unique), avec cache. */
    private fun getSpatialGroups(): List<List<Int>> {
        val gm = groupManager ?: return emptyList()
        val fullSize = gm.allGroupsFull().size
        if (cachedGMCacheSize != fullSize) {
            val full = gm.allGroupsFull()
            cachedSpatialGroups = full.mapNotNull { group ->
                group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
                    .ifEmpty { null }
            }
            // Calculer les bounds
            cachedSpatialBounds = cachedSpatialGroups!!.map { group ->
                val r = android.graphics.RectF(Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)
                for (idx in group) {
                    if (idx >= strokeRegistry.size) continue
                    for ((px, py) in strokeRegistry[idx].points) {
                        if (px < r.left) r.left = px; if (px > r.right) r.right = px
                        if (py < r.top) r.top = py; if (py > r.bottom) r.bottom = py
                    }
                }
                r
            }
            cachedGMCacheSize = fullSize
        }
        return cachedSpatialGroups ?: emptyList()
    }

    /** Bounds précalculés des groupes spatiaux. */
    private fun getSpatialBounds(): List<android.graphics.RectF> {
        getSpatialGroups()  // assure le cache
        return cachedSpatialBounds ?: emptyList()
    }

    /** Détection du groupe sous le stylet — identique à CaptureView. */
    private fun updateHover(x: Float, y: Float) {
        hoverX = x; hoverY = y
        isHovering = true
        val spatialGroups = getSpatialGroups()
        val spatialBounds = getSpatialBounds()
        val found = spatialGroups.withIndex().firstOrNull { (gi, group) ->
            val r = spatialBounds[gi]
            val groupLine = snapToLine((r.top + r.bottom) / 2f)
            r.left < Float.MAX_VALUE && x >= r.left && x <= r.right && Math.abs(y - groupLine) < 50f
        }
        hoverWordGroup = found?.value
        hoverStrokeIndex = found?.value?.firstOrNull()
        // Survol long → sélection
        checkLongHoverReactivation()
    }

    /** Survol long — sélectionne le groupe après le délai. Identique à CaptureView. */
    private fun checkLongHoverReactivation() {
        if (!isHovering) { longHoverStartMs = 0; longHoverFirstStroke = -1; return }
        val targetIndices = hoverWordGroup ?: run {
            longHoverStartMs = 0; longHoverFirstStroke = -1; return
        }
        val firstStroke = targetIndices.firstOrNull() ?: run {
            longHoverStartMs = 0; longHoverFirstStroke = -1; return
        }
        if (firstStroke != longHoverFirstStroke) {
            longHoverFirstStroke = firstStroke
            longHoverStartMs = System.currentTimeMillis()
            return
        }
        val delayMs = CalibrationActivity.getLongHoverDelay(this)
        if (System.currentTimeMillis() - longHoverStartMs < delayMs) return
        // Déclencher
        longHoverStartMs = Long.MAX_VALUE
        val gm = groupManager ?: return
        val anyStrokeId = inkStrokeIdToRegistryIndex.entries
            .firstOrNull { it.value == firstStroke }?.key ?: return
        val g = gm.reactivateGroup(anyStrokeId) ?: return
        // Désélectionner l'ancien
        selectedGroupId?.let { gm.deselectGroup(it) }
        if (gm.selectGroup(g.id)) {
            selectedGroupId = g.id
            updateBlobCache()
            throttledInvalidate()
            Log.i(TAG, "Survol long — groupe ${g.id} SELECTED")
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
                    override fun onBeginRawDrawing(p0: Boolean, p1: OnyxTouchPoint) {}
                    override fun onRawDrawingTouchPointMoveReceived(point: OnyxTouchPoint?) {}
                    override fun onRawDrawingTouchPointListReceived(list: TouchPointList?) {}
                    override fun onEndRawDrawing(p0: Boolean, p1: OnyxTouchPoint) {}
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
            Log.i(TAG, "TouchHelper actif")

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
            touchHelper = null
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
    }

    // ═══════════════════════════════════════════════════════════════════
    // GESTION DES POINTS STYLET
    // ═══════════════════════════════════════════════════════════════════

    private fun onStylusDown(x: Float, y: Float) {
        markStylusActive()
        currentPath.reset()
        currentPath.moveTo(x, y)
        currentStroke = StrokeRecord(
            id = UUID.randomUUID().toString()
        ).also { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(1.0f)
        }
        throttledInvalidate()
    }

    private fun onStylusPoint(x: Float, y: Float, pressure: Float) {
        currentPath.lineTo(x, y)
        currentStroke?.let { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(pressure.coerceIn(0f, 1f))
        }
        throttledInvalidate()
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
        // ═══ PAS d'evictInactive — l'IME n'a que quelques mots, pas de persistance ═══
        // Les groupes doivent rester en mémoire pour la sélection par survol.

        // Mettre à jour le cache du blob
        updateBlobCache()

        throttledInvalidate()
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
    // INFÉRENCE DIFFÉRÉE — flux asynchrone séquencé
    // ═══════════════════════════════════════════════════════════════════

    /** File d'attente FIFO de groupes à reconnaître. */
    private val inferenceQueue = java.util.concurrent.ConcurrentLinkedQueue<List<Int>>()
    /** true si une inférence est en cours (évite les chevauchements). */
    private var isInferring = false
    /** Groupes déjà inférés (groupId → true). */
    private val inferredGroups = mutableSetOf<String>()

    /** Appelé à chaque stroke — enregistre l'activité stylet. */
    private fun markStylusActive() {
        lastStylusActivity = System.currentTimeMillis()
        scheduleInactivityCheck()
    }

    /** Programme une vérification d'inactivité (debounce 800ms). */
    private fun scheduleInactivityCheck() {
        if (inactivityCheckScheduled) return
        inactivityCheckScheduled = true
        uiHandler.postDelayed({
            inactivityCheckScheduled = false
            val idle = System.currentTimeMillis() - lastStylusActivity
            if (idle >= 800) {
                scanAndInferGroups()
            } else {
                scheduleInactivityCheck()
            }
        }, 800)
    }

    /** Scan les groupes non inférés → file d'attente → pipeline. */
    private fun scanAndInferGroups() {
        val gm = groupManager ?: return
        val groups = gm.allGroups()
        for (group in groups) {
            if (group.id in inferredGroups) continue
            if (group.strokeIds.isEmpty()) continue
            val indices = group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) continue
            inferredGroups.add(group.id)
            inferenceQueue.add(indices)
            cachedGMCacheSize = -1
            Log.i(TAG, "Groupe à inférer: ${group.id} (${indices.size} strokes)")
        }
        if (inferenceQueue.isNotEmpty()) {
            startInferencePipeline()
        }
    }

    /** Démarre le pipeline (si pas déjà en cours). */
    private fun startInferencePipeline() {
        if (isInferring) return
        if (inferenceQueue.isEmpty()) return
        isInferring = true
        Log.i(TAG, "Pipeline inférence: ${inferenceQueue.size} groupe(s)")
        processNextInference()
    }

    /** Traite un groupe, puis enchaîne sur le suivant. */
    private fun processNextInference() {
        val indices = inferenceQueue.poll() ?: run {
            isInferring = false
            Log.i(TAG, "Pipeline inférence: terminé")
            return
        }
        inferExecutor.submit {
            recognizeGroup(indices)
            try { Thread.sleep(80) } catch (_: InterruptedException) {}
            processNextInference()
        }
    }

    /**
     * Reconnaît un groupe individuel (appelé depuis le thread background).
     */
    private fun recognizeGroup(indices: List<Int>) {
        val recognizer = recognizer ?: return
        if (!recognizer.isLoaded) return

        try {
            val strokesCopy = strokeRegistry.toList()
            val result = recognizer.recognize(strokesCopy, indices)
            if (!result.isNullOrBlank()) {
                Log.i(TAG, "Reconnaissance groupe: \"$result\" (${indices.size} strokes)")
                uiHandler.post {
                    val firstIdx = indices.firstOrNull() ?: return@post
                    groupLabels[firstIdx] = result
                    cachedGMCacheSize = -1  // invalider cache spatial
                    commitText(result)
                    throttledInvalidate()
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

    /** 
     * Met à jour le cache du blob (appelé après stroke/groupement, PAS dans onDraw).
     * Parcourt les points du dernier groupe et pré-calcule les ovales.
     */
    private fun updateBlobCache() {
        cachedBlobOvals.clear()
        val gm = groupManager ?: return
        // Priorité: groupe SELECTED, sinon dernier groupe
        val selectedGroups = gm.groupsInState(GroupState.SELECTED)
        val group = if (selectedGroups.isNotEmpty()) selectedGroups.first()
                     else gm.allGroups().lastOrNull() ?: return
        if (group.strokeIds.isEmpty()) return

        val rx = gm.params.spatialDistancePx
        val ry = gm.params.spatialDistanceY
        if (rx <= 0f && ry <= 0f) return

        cachedBlobRx = rx
        cachedBlobRy = ry
        cachedBlobGroupId = group.id

        val sampleStep = ((rx + ry) / 10f).toInt().coerceIn(1, 6)
        var pi = 0
        for (sid in group.strokeIds) {
            val idx = inkStrokeIdToRegistryIndex[sid] ?: continue
            val sr = strokeRegistry.getOrNull(idx) ?: continue
            for ((x, y) in sr.points) {
                if (pi % sampleStep == 0) {
                    cachedBlobOvals.add(Triple(x, y, rx + ry))
                }
                pi++
            }
        }
    }

    private fun clearCanvas() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        currentPath.reset()
        currentStroke = null
        throttledInvalidate()
    }

    /** Dessine les labels de groupe à leur position d'interligne */
    private fun drawGroupLabels(canvas: Canvas) {
        if (groupLabels.isEmpty()) return
        val groups = getSpatialGroups()
        val bounds = getSpatialBounds()
        var drawn = 0
        for ((firstIdx, label) in groupLabels) {
            val gi = groups.indexOfFirst { it.firstOrNull() == firstIdx }
            if (gi < 0 || gi >= bounds.size) {
                Log.d(TAG, "Label '$label' (firstIdx=$firstIdx) — groupe spatial introuvable parmi ${groups.size} groupes")
                continue
            }
            val r = bounds[gi]
            if (r.left >= Float.MAX_VALUE) continue
            val lineY = snapToLine((r.top + r.bottom) / 2f)
            val y = lineY + labelPaint.textSize + 4f
            canvas.drawText(label, r.left, y, labelPaint)
            drawn++
        }
        if (drawn > 0) Log.d(TAG, "Labels dessinés: $drawn/${groupLabels.size}")
    }
}
