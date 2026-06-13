package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.data.note.TouchPoint as OnyxTouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

/**
 * CaptureView — Capture & edition stylo, 3 modes.
 *
 * CAPTURE (defaut) : flux V* continu vers .vstar.
 * EDIT : selection de stroke au tap, drag pour deplacer.
 * INSERT : interstice inter-stroke -> insertion.
 *
 * Gestes multi-touch :
 *   3 doigts <- : effacement progressif du stroke selectionne (chronologie inversee)
 */
class CaptureView(context: Context) : View(context) {

    private val TAG = "Miroir/Capture"

    // =========================================================================
    // DEBUG MODE — enregistre les coordonnees brutes TouchHelper AVANT encodage
    // =========================================================================
    companion object {
        /** Flag pour activer le debug des coordonnees brutes */
        const val DEBUG_RAW = true
        /** Repertoire de sortie (racine stockage interne) */
        private val DEBUG_DIR = "debug_raw"
        /** Repertoire des captures brutes (training data) */
        private val RAW_DIR = "raw_capture"
        /** Repertoire des notes libres (bloc-notes) */
        private val NOTE_DIR = "blocnote"
    }

    /** Flag: mode bloc-notes (pas de header label, sauvegarde dans blocnote/) */
    var isBlocnoteMode: Boolean = false
        set(v) { field = v; rawSessionLabel = "" }

    private var debugWriter: FileWriter? = null
    private var debugSessionStart: Long = 0L

    /** Initialise le debug : cree le fichier et ecrit l'en-tete */
    private fun initDebugLog() {
        if (!DEBUG_RAW) return
        try {
            val dir = File(context.filesDir, DEBUG_DIR)
            dir.mkdirs()
            val ts = System.currentTimeMillis()
            debugSessionStart = ts
            val f = File(dir, "raw_$ts.csv")
            debugWriter = FileWriter(f)
            debugWriter?.write("# Debug raw TouchHelper coordinates\n")
            debugWriter?.write("# Format: eventType,slot,seq,timestamp_ms,x,y,pressure\n")
            debugWriter?.flush()
            Log.i(TAG, "DEBUG_RAW: writing to ${f.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "DEBUG_RAW: cannot init: ${e.message}")
        }
    }

    /** Ecrit une ligne dans le log debug */
    private fun logRawPoint(event: String, seq: Int, t: Long, x: Float, y: Float, pressure: Float) {
        if (!DEBUG_RAW) return
        val w = debugWriter ?: return
        try {
            w.write("$event,$seq,$t,$x,$y,$pressure,$strokeCount\n")
            w.flush()
        } catch (_: IOException) {}
    }

    /** Ferme le log debug */
    private fun closeDebugLog() {
        if (!DEBUG_RAW) return
        try {
            debugWriter?.close()
            debugWriter = null
            Log.i(TAG, "DEBUG_RAW: closed")
        } catch (_: IOException) {}
    }

    // -- TouchHelper Onyx SDK ------------------------------------------------
    private var touchHelper: TouchHelper? = null
    var useTouchHelper = false
    var touchHelperAttempted = false
    /** Timestamp du dernier callback TouchHelper recu (0 = jamais) */
    private var touchHelperLastEvent = 0L


    // -- Reference au point precedent (pour calculer les deltas) --------------
    private var prevX: Float = 0f
    private var prevY: Float = 0f
    private var prevT: Long = 0L
    private var hasPrevPoint = false

    // -- Raw writer -----------------------------------------------------------
    private var rawWriter: FileWriter? = null
    private var rawSessionLabel: String = ""

    // -- Flags capabilities stylo ---------------------------------------------
    private var hasTilt = false

    // -- Compteurs -------------------------------------------------------------
    @Volatile private var strokeCount = 0
    private var pointInStroke = 0  // Index du point courant dans le stroke (0 = pendown)
    /** Compteur absolu de points TouchHelper recus (debug) */
    private var pointSeq = 0
    private var logTimer = 0L

    // Origine absolue du premier point de pose (pour reconstruction position)
    var sessionOriginX: Float = -1f
    var sessionOriginY: Float = -1f

    // =========================================================================
    // MODE SYSTEM — capture, edition, insertion
    // =========================================================================

    var currentMode: CaptureMode = CaptureMode.CAPTURE
        set(value) {
            val wasCapture = field == CaptureMode.CAPTURE
            val isCapture = value == CaptureMode.CAPTURE
            field = value
            selectedStrokeIndex = null
            dragStrokeIndex = null

            // TouchHelper ne doit etre actif qu'en mode CAPTURE
            // sinon il consomme les events stylo et bloque le hover
            if (wasCapture && !isCapture) {
                releaseTouchHelper()
            } else if (!wasCapture && isCapture) {
                initTouchHelper()
            }

            invalidate()
            onModeChanged?.invoke(value)
        }

    var onModeChanged: ((CaptureMode) -> Unit)? = null

    /** Registre de tous les strokes traces (pour edition) */
    private val strokeRegistry = mutableListOf<StrokeRecord>()

    /** Stroke en cours de dessin (null si pas en train de tracer) */
    private var drawingStroke: StrokeRecord? = null

    /** Index du stroke selectionne (null = aucun) */
    var selectedStrokeIndex: Int? = null
        set(value) {
            field = value
            onSelectionChanged?.invoke(value)
        }

    var onSelectionChanged: ((Int?) -> Unit)? = null

    /** Index du stroke en cours de drag */
    private var dragStrokeIndex: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // -- Etat du hover (survol du stylet, EDIT mode) ---------------------------
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverStrokeIndex: Int? = null

    // -- Groupement de mots (EDIT mode) ---------------------------------------
    private var wordGroupsCache: List<List<Int>>? = null
    private var hoverWordGroup: List<Int>? = null
    private var selectedWordGroup: List<Int>? = null
    private var dragWordGroup: List<Int>? = null

    // -- Reflux de texte (mise en page dynamique) -----------------------------
    /** Metriques sauvegardees au debut du drag, pour le reflow */
    private var flowState: ReflowState? = null
    /** Snapshots des positions originales des strokes (restauration entre frames) */
    private var flowBackup: MutableList<MutableList<Pair<Float, Float>>>? = null
    private var isHovering = false

    // -- Etat du geste 3 doigts ------------------------------------------------
    private var threeFingerStartX = 0f
    private var threeFingerActive = false
    private var threeFingerSwiped = false

    // -- Rendu -----------------------------------------------------------------
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        color = Color.parseColor("#1D9E75")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val selectionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D9E75")
        style = Paint.Style.FILL
    }

    // -- Lignes guides ---------------------------------------------------------
    private val guideLines = 17
    private val guidePaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    // -- Blob visuel du groupe actif (avant inférence) ------------------------
    private val blobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 68, 136, 255)    // bleu contour léger
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val blobClosedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 128, 128, 128)   // gris très discret
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    // Curseur de survol en mode CAPTURE (groupe qui sera réactivé)
    private val hoverFeedbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 100, 149, 237)  // bleu-violet
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    // Debug : indices des groupes
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 28f
        isFakeBoldText = true
    }
    private var blobRadiusX = 28f  // rayon horizontal du blob
    private var blobRadiusY = 48f  // rayon vertical du blob

    // Point clignotant du groupe actif
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val cursorAnimHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var cursorAnimRunning = false
    private var cursorVisible = true

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private val currentPath = mutableListOf<Pair<Float, Float>>()

    // -- Init ------------------------------------------------------------------
    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isLongClickable = true
        Log.i(TAG, "CaptureView V* initialise")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.i(TAG, "CaptureView attache a la fenetre")
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Log.i(TAG, "CaptureView detache de la fenetre")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val newBmp = Bitmap.createBitmap(
            maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888
        )
        bitmap = newBmp
        bitmapCanvas = Canvas(newBmp)
        Log.i(TAG, "Vue ${w}x${h}")
    }

    // =========================================================================
    // PIPELINE 1 : TouchHelper Onyx SDK (60Hz+)
    // =========================================================================

    fun initTouchHelper() {
        if (touchHelper != null) {
            Log.i(TAG, "TouchHelper deja initialise, ignore")
            return
        }
        try {
            touchHelper = TouchHelper.create(this, TouchHelper.FEATURE_APP_TOUCH_RENDER,
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
            touchHelper!!.setPostInputEvent(true)
            useTouchHelper = (touchHelper != null)
            touchHelperAttempted = true
            touchHelperLastEvent = System.currentTimeMillis()
            Log.i(TAG, "TouchHelper actif (useTouchHelper=$useTouchHelper)")
        } catch (e: Exception) {
            useTouchHelper = false
            touchHelper = null
            touchHelperAttempted = true
            Log.w(TAG, "TouchHelper indisponible: ${e.message} -- fallback onTouchEvent")
        }
    }

    fun releaseTouchHelper() {
        try {
            touchHelper?.closeRawDrawing()
            touchHelper?.setRawDrawingEnabled(false)
        } catch (_: Exception) {}
        touchHelper = null
        useTouchHelper = false
        touchHelperAttempted = false
    }

    // =========================================================================
    // PIPELINE 2 : onTouchEvent — dispatch modal + multi-touch
    // =========================================================================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount

        // MODE CAPTURE : totalement inerte — ni doigts, ni multi-touch
        // La surface se comporte comme une feuille de papier : seul le stylet trace.
        if (currentMode == CaptureMode.CAPTURE) {
            if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
                // WATCHDOG TouchHelper : si initialisé mais aucun callback depuis 2s,
                // tomber en fallback onTouchEvent (TouchHelper ne fonctionne pas sur ce device)
                if (touchHelperAttempted && useTouchHelper) {
                    val now = System.currentTimeMillis()
                    if (now - touchHelperLastEvent > 2000L) {
                        Log.w(TAG, "TouchHelper silencieux depuis ${now - touchHelperLastEvent}ms — fallback onTouchEvent")
                        useTouchHelper = false
                    }
                }
                handleCaptureEvent(event)
                return true
            }
            return true  // Consomme tout toucher non-stylo
        }

        // Multi-touch : gestes a 3 doigts (EDIT/INSERT uniquement)
        if (pointerCount >= 3) {
            return handleThreeFingerGesture(event)
        }

        // Stylo : dispatch selon le mode (EDIT/INSERT/REVIEW)
        if (event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            when (currentMode) {
                CaptureMode.EDIT -> handleEditEvent(event)
                CaptureMode.INSERT -> handleInsertEvent(event)
                else -> { /* REVIEW / autres : ignorer le stylo */ }
            }
            return true
        }
        return false
    }

    override fun performClick(): Boolean = true

    /**
     * Survol du stylet (hover) — Android genere ACTION_HOVER_ENTER/MOVE/EXIT
     * Uniquement en mode EDIT pour montrer les points d'accroche.
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        // En mode CAPTURE : on autorise le hover pour le survol long (réactivation)
        // mais on ne fait pas le rendu EDIT (points d'ancrage, sélection).
        if (currentMode == CaptureMode.CAPTURE) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    updateHover(event.x, event.y)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    isHovering = false
                    hoverStrokeIndex = null
                    hoverWordGroup = null
                    cancelLongHover()
                }
            }
            return true
        }

        // Mode EDIT : hover complet avec rendu visuel
        if (currentMode != CaptureMode.EDIT) return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                Log.d(TAG, "HOVER_ENTER @ (${event.x}, ${event.y})")
                updateHover(event.x, event.y)
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                Log.v(TAG, "HOVER_MOVE @ (${event.x}, ${event.y})")
                updateHover(event.x, event.y)
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                Log.d(TAG, "HOVER_EXIT")
                isHovering = false
                hoverStrokeIndex = null
                hoverWordGroup = null
                invalidate()
            }
        }
        return true
    }

    /** Retourne le nom lisible d'un actionMasked */
    private fun actionName(action: Int): String = when (action) {
        MotionEvent.ACTION_DOWN -> "DOWN"
        MotionEvent.ACTION_UP -> "UP"
        MotionEvent.ACTION_MOVE -> "MOVE"
        MotionEvent.ACTION_CANCEL -> "CANCEL"
        MotionEvent.ACTION_HOVER_ENTER -> "HOVER_ENTER"
        MotionEvent.ACTION_HOVER_MOVE -> "HOVER_MOVE"
        MotionEvent.ACTION_HOVER_EXIT -> "HOVER_EXIT"
        else -> action.toString()
    }

    /** Met a jour l'etat hover depuis un event quelconque */
    private fun updateHover(x: Float, y: Float) {
        hoverX = x
        hoverY = y
        isHovering = true
        val hitIdx = hitTest(x, y)
        if (hitIdx != hoverStrokeIndex) {
            hoverStrokeIndex = hitIdx
            hoverWordGroup = if (hitIdx != null) findWordGroup(hitIdx) else null
            val wgMsg = if (hoverWordGroup != null) " (mot: [${hoverWordGroup!!.joinToString(",")}])" else ""
            Log.d(TAG, "Hover stroke: $hitIdx${wgMsg} (${strokeRegistry.size} total)")
            invalidate()
        }
        // Survol long : réactiver le groupe si survolé assez longtemps
        checkLongHoverReactivation()
    }

    // ── Survol long (réactivation de groupe) ────────────────────────────
    private val longHoverHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longHoverRunnable: Runnable? = null
    private var longHoverGroup: List<Int>? = null

    /**
     * Si le stylet survole un groupe déjà inféré, active le groupe
     * après un court délai (pour éviter les micro-survols).
     * L'inférence suivra le timeout normal après écriture.
     */
    private fun checkLongHoverReactivation() {
        val group = hoverWordGroup
        // Ne pas réinitialiser si on survole toujours le même groupe
        if (group != null && group == longHoverGroup) return

        longHoverRunnable?.let { longHoverHandler.removeCallbacks(it) }
        longHoverRunnable = null
        longHoverGroup = null

        if (group == null) return
        if (!isBlocnoteMode || onWordGroupCompleted == null) return

        val allGroups = wordGroupsCache ?: computeWordGroups()
        val groupIndex = allGroups.indexOfFirst { it == group }
        if (groupIndex < 0 || groupIndex >= inferredGroupCount) return
        if (groupIndex == reactivatedGroupIndex) return

        // Activation immédiate — le délai est géré par le slider ⚙ et
        // sert uniquement à filtrer les micro-survols via le check ci-dessus
        // (group == longHoverGroup). L'inférence suivra le timeout normal.
        reactivatedGroupIndex = groupIndex
        // ⚠️ Ne pas invalider wordGroupsCache — il contient les absorptions
        longHoverGroup = group
        Log.i(TAG, "Survol — groupe $groupIndex activé (${group.size} strokes)")
    }

    /** Annule le timer de survol long */
    private fun cancelLongHover() {
        longHoverRunnable?.let { longHoverHandler.removeCallbacks(it) }
        longHoverRunnable = null
        longHoverGroup = null
    }

    // ── Double-tap (réactivation de groupe) ────────────────────────────
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var tapPendingGroup: List<Int>? = null

    // ── Appui long (réactivation sans créer de stroke) ──────────────────
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private var longPressStartTime = 0L
    private var longPressTriggered = false

    /**
     * Détecte un double-tap sur un mot clôturé → le réactive.
     * TouchHelper-compatible (passe par onTouchEvent forwardé).
     */
    private fun checkDoubleTapReactivation(x: Float, y: Float): Boolean {
        val now = System.currentTimeMillis()
        val dt = now - lastTapTime
        val dx = Math.abs(x - lastTapX)
        val dy = Math.abs(y - lastTapY)
        lastTapTime = now
        lastTapX = x
        lastTapY = y

        // Double-tap : < 400ms et < 40px d'écart
        if (dt in 100..400 && dx < 40f && dy < 40f) {
            val hitIdx = hitTest(x, y) ?: return false
            val group = findWordGroup(hitIdx) ?: return false
            val allGroups = wordGroupsCache ?: computeWordGroups()
            val groupIndex = allGroups.indexOfFirst { it == group }
            if (groupIndex < 0 || groupIndex >= inferredGroupCount) return false  // déjà actif

            Log.i(TAG, "Double-tap — réactivation du groupe $groupIndex (${group.size} strokes)")
            inferredGroupCount = groupIndex
            reactivatedGroupIndex = groupIndex
            wordGroupsCache = null
            cancelAutoInferTimeout()

            // Notifier pour relancer la reconnaissance
            val snapshot = strokeRegistry.toList()
            onWordGroupCompleted?.invoke(snapshot, group.toList())

            // Feedback visuel : faire pulser le point plus fort
            lastTapTime = 0  // reset pour éviter triple-tap
            return true
        }
        return false
    }

    // =========================================================================
    // MODE EDIT — selection + drag
    // =========================================================================

    private var editStartX = 0f
    private var editStartY = 0f
    private var wasDrag = false
    /** Décallage vertical du mot saisi par rapport à l'interligne au moment du DOWN */
    private var dragWordYOffset = 0f

    private fun handleEditEvent(event: MotionEvent) {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editStartX = x; editStartY = y
                wasDrag = false
                val hitIdx = hitTest(x, y)

                // 🪄 Mode décomposition : un tap décompose le groupe ciblé
                if (decomposeMode && hitIdx != null) {
                    decomposeGroupAt(hitIdx)
                    invalidate()
                    return
                }

                if (hitIdx != null) {
                    selectedWordGroup = findWordGroup(hitIdx)
                    initReflow(hitIdx)
                    // Sauvegarder l'offset Y du mot par rapport à l'interligne
                    dragWordYOffset = computeGroupCenterY(selectedWordGroup!!) - snapToLine(computeGroupCenterY(selectedWordGroup!!))
                } else {
                    selectedWordGroup = null
                    flowState = null
                    dragWordYOffset = 0f
                }
                dragWordGroup = selectedWordGroup
                selectedStrokeIndex = hitIdx
                val wgMsg = if (selectedWordGroup != null) " (mot: [${selectedWordGroup!!.joinToString(",")}])" else ""
                Log.d(TAG, "EDIT DOWN @ ($x, $y) hit=$hitIdx${wgMsg} (registry=${strokeRegistry.size})")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.abs(x - editStartX) > 8 || Math.abs(y - editStartY) > 8) {
                    wasDrag = true
                }
                val group = dragWordGroup
                if (group != null && wasDrag) {
                    // Simulation visuelle : seul le mot saisi se déplace.
                    // Les autres mots restent figés. Y snappé à l'interligne.
                    val dx = x - editStartX
                    val dy = y - editStartY
                    for (idx in group) {
                        if (idx < strokeRegistry.size) {
                            strokeRegistry[idx].translate(dx, dy)
                        }
                    }
                    editStartX = x
                    editStartY = y

                    // Snap Y : préserver l'offset du mot par rapport à l'interligne
                    // (pour garder l'empreinte cursive : descente du 'g', hampe du 't', etc.)
                    val cy = computeGroupCenterY(group)
                    val snappedY = snapToLine(cy)
                    val targetY = snappedY + dragWordYOffset
                    val snapDy = targetY - cy
                    if (Math.abs(snapDy) > 0.5f) {
                        for (idx in group) {
                            if (idx < strokeRegistry.size) {
                                strokeRegistry[idx].translate(0f, snapDy)
                            }
                        }
                        editStartY += snapDy
                    }

                    rebuildBitmap()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Réordonnancement : après un drag, l'ordre visuel des mots
                // devient l'ordre de lecture dans le fichier.
                if (wasDrag && dragWordGroup != null && flowState != null) {
                    val newOrder = computeVisualOrder(flowState!!.words)
                    if (newOrder != null) {
                        wordGroupsCache = newOrder
                        // Snap Y : préserver l'offset cursif du mot déplacé
                        val cy = computeGroupCenterY(dragWordGroup!!)
                        val snappedY = snapToLine(cy)
                        val targetY = snappedY + dragWordYOffset
                        val snapDy = targetY - cy
                        if (Math.abs(snapDy) > 0.5f) {
                            for (idx in dragWordGroup!!) {
                                if (idx < strokeRegistry.size) {
                                    strokeRegistry[idx].translate(0f, snapDy)
                                }
                            }
                        }
                        rebuildBitmap()
                        invalidate()
                        Log.i(TAG, "Réordonnancement: ${newOrder.size} groupes")
                    }
                }
                dragWordGroup = null
                flowState = null
                flowBackup = null
            }
        }
    }

    // =========================================================================
    // MODE INSERT — detection inter-stroke
    // =========================================================================

    private var insertPending = false
    private var insertX = 0f
    private var insertY = 0f
    private var insertIdx = 0

    private fun handleInsertEvent(event: MotionEvent) {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!insertPending) {
                    val gapIdx = findInsertionGap(y)
                    if (gapIdx != null) {
                        insertIdx = gapIdx
                        insertX = x; insertY = y
                        insertPending = true
                        invalidate()
                        Log.i(TAG, "Insertion activee a l'index $gapIdx @ ($x, $y)")
                    }
                } else {
                    insertPending = false
                    currentMode = CaptureMode.CAPTURE
                }
            }
        }
    }

    /** Trouve le gap d'insertion base sur la position Y */
    private fun findInsertionGap(y: Float): Int? {
        if (strokeRegistry.isEmpty()) return null
        val snappedY = snapToLine(y)
        for (i in 0 until strokeRegistry.size - 1) {
            val b1 = strokeRegistry[i].bounds()
            val b2 = strokeRegistry[i + 1].bounds()
            val line1 = snapToLine(b1.top + b1.bottom / 2)
            val line2 = snapToLine(b2.top + b2.bottom / 2)
            if (snappedY in (line1 + 10)..(line2 - 10)) {
                return i + 1
            }
        }
        return null
    }

    // =========================================================================
    // GESTE 3 DOIGTS — effacement progressif
    // =========================================================================

    private fun handleThreeFingerGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 3 && !threeFingerActive) {
                    var sx = 0f
                    val n = event.pointerCount.coerceAtMost(3)
                    for (i in 0 until n) sx += event.getX(i)
                    threeFingerStartX = sx / n
                    threeFingerActive = true
                    threeFingerSwiped = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!threeFingerActive) return false
                var cx = 0f
                val n = event.pointerCount.coerceAtMost(3)
                for (i in 0 until n) cx += event.getX(i)
                cx /= n
                if (cx - threeFingerStartX < -50 && !threeFingerSwiped) {
                    threeFingerSwiped = true
                    eraseSelectedStrokeProgressive()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount < 3) {
                    threeFingerActive = false
                    threeFingerSwiped = false
                }
            }
        }
        return true
    }

    /** Effacement progressif : supprime les DERNIERS points du stroke selectionne */
    private fun eraseSelectedStrokeProgressive() {
        val selIdx = selectedStrokeIndex
        if (selIdx == null || selIdx >= strokeRegistry.size) return
        val stroke = strokeRegistry[selIdx]
        if (stroke.activePoints < 2) {
            deleteSelectedStroke()
            return
        }
        val trimCount = maxOf(2, Math.ceil(stroke.activePoints * 0.2).toInt())
        stroke.trimFromEnd(trimCount)
        Log.i(TAG, "Erase: ${trimCount} pts supprimes, reste ${stroke.activePoints} pts")
        rebuildBitmap()
        invalidate()
    }

    /** Supprime completement le stroke selectionne */
    fun deleteSelectedStroke() {
        val selIdx = selectedStrokeIndex
        if (selIdx == null || selIdx >= strokeRegistry.size) return
        strokeRegistry.removeAt(selIdx)
        selectedStrokeIndex = null
        rebuildBitmap()
        invalidate()
        Log.i(TAG, "Stroke supprime (total: ${strokeRegistry.size})")
    }

    // =========================================================================
    // PIPELINE UNIQUE : handleCaptureEvent — rendu + stockage brut
    // =========================================================================
    // Pipeline unique qui capture les coordonnees brutes Android ET les stocke
    // en memoire (strokeRegistry) + log debug CSV. Pas de separation
    // TouchHelper/onTouchEvent : TouchHelper est tente mais echoue (aucun
    // callback), donc onTouchEvent prend le relais apres 2s de watchdog.
    //
    // Les coordonnees sont en pixel-space (Android View coordinates).
    // Aucun encodage VStar : on stocke les events bruts (x, y, pressure, time).
    // =========================================================================

    private fun handleCaptureEvent(event: MotionEvent) {
        logRawOnTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelAutoInferTimeout()

                // Enregistrer la position pour détection d'appui long
                longPressStartX = event.x
                longPressStartY = event.y
                longPressStartTime = System.currentTimeMillis()
                longPressTriggered = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    requestUnbufferedDispatch(event)
                }
                val x = event.x; val y = event.y; val t = event.eventTime

                // Capturer le premier point de pose (origine absolue)
                if (strokeCount == 0) {
                    sessionOriginX = x
                    sessionOriginY = y
                    Log.i(TAG, "Session origin: ($x, $y)")
                }

                // Nouveau stroke
                strokeCount++
                pointInStroke = 0
                prevX = x; prevY = y; prevT = t
                hasPrevPoint = true
                currentPath.clear()
                currentPath.add(Pair(x, y))
                drawingStroke = StrokeRecord(
                    points = mutableListOf(Pair(x, y)),
                    timestamps = mutableListOf(t),
                    pressures = mutableListOf(0.5f)
                )
                writeRawPoint("DOWN", x, y, 0.5f, t)
            }

            MotionEvent.ACTION_MOVE -> {
                // Détection d'appui long (>500ms sans bouger) → réactivation
                if (!longPressTriggered && isBlocnoteMode) {
                    val dt = System.currentTimeMillis() - longPressStartTime
                    val dx = Math.abs(event.x - longPressStartX)
                    val dy = Math.abs(event.y - longPressStartY)
                    if (dt > 500 && dx < 15f && dy < 15f) {
                        longPressTriggered = true
                        val hitIdx = hitTest(event.x, event.y)
                        if (hitIdx != null) {
                            val group = findWordGroup(hitIdx)
                            if (group != null) {
                                val allGroups = wordGroupsCache ?: computeWordGroups()
                                val groupIndex = allGroups.indexOfFirst { it == group }
                                if (groupIndex >= 0 && groupIndex < inferredGroupCount) {
                                    Log.i(TAG, "Appui long — réactivation du groupe $groupIndex")
                                    reactivatedGroupIndex = groupIndex
                                    cancelAutoInferTimeout()
                                    val snapshot = strokeRegistry.toList()
                                    onWordGroupCompleted?.invoke(snapshot, group.toList())
                                    // Annuler le stroke en cours
                                    drawingStroke = null
                                    currentPath.clear()
                                    hasPrevPoint = false
                                    postInvalidate()
                                    return
                                }
                            }
                        }
                    }
                }
                if (!hasPrevPoint) return

                // DIAG: first MOVE only - log historical + current
                if (strokeCount == 1 && pointInStroke == 0 && prevT > 0) {
                    Log.i(TAG, "DIAG MOVE hSize=" + event.historySize)
                    for (hi in 0 until event.historySize) {
                        if (hi < 3) {
                            Log.i(TAG, "DIAG   hist["+ hi +"]: x=" + event.getHistoricalX(hi) + " y=" + event.getHistoricalY(hi) + " t=" + event.getHistoricalEventTime(hi))
                        }
                    }
                    Log.i(TAG, "DIAG   curr: x=" + event.x + " y=" + event.y + " t=" + event.eventTime)
                }

                // Points historiques (batches par Android) — stocke les coordonnees brutes
                for (i in 0 until event.historySize) {
                    val hx = event.getHistoricalX(i)
                    val hy = event.getHistoricalY(i)
                    val ht = event.getHistoricalEventTime(i)
                    val hp = event.getHistoricalPressure(i)

                    pointInStroke++
                    prevX = hx; prevY = hy; prevT = ht
                    currentPath.add(Pair(hx, hy))
                    drawingStroke?.points?.add(Pair(hx, hy))
                    drawingStroke?.timestamps?.add(ht)
                    drawingStroke?.pressures?.add(hp)
                    writeRawPoint("MOVE", hx, hy, hp, ht)
                }

                // Point courant
                val x = event.x; val y = event.y; val t = event.eventTime; val p = event.getPressure()
                pointInStroke++
                prevX = x; prevY = y; prevT = t
                currentPath.add(Pair(x, y))
                drawingStroke?.points?.add(Pair(x, y))
                drawingStroke?.timestamps?.add(t)
                drawingStroke?.pressures?.add(p)
                writeRawPoint("MOVE", x, y, p, t)
                postInvalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (!hasPrevPoint) return
                val x = event.x; val y = event.y; val t = event.eventTime; val p = event.getPressure()

                pointInStroke++
                prevX = x; prevY = y; prevT = t
                currentPath.add(Pair(x, y))
                drawingStroke?.points?.add(Pair(x, y))
                drawingStroke?.timestamps?.add(t)
                drawingStroke?.pressures?.add(p)
                writeRawPoint("UP", x, y, p, t)

                hasPrevPoint = false
                rasterizeCurrentPath()
                registerCompletedStroke()
                logProgress()
                postInvalidate()
                currentPath.clear()
            }
        }
    }

    /** Ecrit une ligne de separation dans le log debug */
    private fun logRawLine() {
        if (!DEBUG_RAW) return
        try { debugWriter?.write("#---\n") } catch (_: IOException) {}
    }

    /** Ecrit une ligne de commentaire dans le log debug */
    private fun logRawComment(msg: String) {
        if (!DEBUG_RAW) return
        try { debugWriter?.write("# $msg\n") } catch (_: IOException) {}
    }

    // -- Raw session writer (training data) ----------------------------------

    /** Ouvre un fichier de capture brute pour cette session */
    private fun openRawWriter(label: String) {
        closeRawWriter()
        try {
            val dir = File(context.filesDir, if (isBlocnoteMode) NOTE_DIR else RAW_DIR)
            dir.mkdirs()
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val prefix = if (isBlocnoteMode) "note" else "raw"
            val safeLabel = if (isBlocnoteMode) "" else label.replace("[^a-zA-Z0-9_-]", "_").take(32)
            val fname = if (isBlocnoteMode) "${prefix}_${ts}.csv" else "${prefix}_${safeLabel}_${ts}.csv"
            val f = File(dir, fname)
            rawWriter = FileWriter(f)
            rawSessionLabel = label
            if (!isBlocnoteMode) {
                rawWriter?.write("# label:$label\n")
            }
            rawWriter?.write("# device:Boox Note Air 5C\n")
            rawWriter?.write("# format:eventType,seq,x,y,pressure,eventTime\n")
            rawWriter?.flush()
            Log.i(TAG, "Raw session: ${f.name} ${if (isBlocnoteMode) "[bloc-note]" else ""}")
        } catch (e: Exception) {
            Log.w(TAG, "Raw writer error: ${e.message}")
        }
    }

    /** Ecrit un point dans le fichier de capture brute */
    private fun writeRawPoint(event: String, x: Float, y: Float, p: Float, t: Long) {
        ensureRawWriterOpen()
        val w = rawWriter ?: return
        try {
            w.write("$event,$pointSeq,$x,$y,$p,$t\n")
            w.flush()
        } catch (_: IOException) {}
    }

    /** Ferme le writer de capture brute */
    private fun closeRawWriter() {
        try {
            rawWriter?.close()
        } catch (_: IOException) {}
        rawWriter = null
        rawSessionLabel = ""
    }

    /** Verifie si des strokes ont ete captures */
    fun hasStrokes(): Boolean {
        return strokeRegistry.isNotEmpty()
    }

    /** Chemin du fichier .note courant (null = nouvelle page) */
    var currentNotePath: String? = null

    /**
     * Sauvegarde la capture courante au format .note JSON.
     * Si currentNotePath != null, ecrase le meme fichier (edition en place).
     */
    fun saveCurrentNote(
        label: String = "",
        mode: String = "blocnote",
        transcriptions: List<String>? = null,
        corrections: List<String>? = null
    ): String? {
        if (strokeRegistry.isEmpty()) {
            Log.w(TAG, "saveCurrentNote: rien a sauvegarder"); return null
        }
        val rawWords = computeWordGroups()
        if (rawWords.isEmpty()) return null
        // Ordonner par position visuelle
        val words = computeVisualOrder(rawWords) ?: rawWords
        // Les transcriptions arrivent DEJA en ordre visuel (checkAutoInfer + refresh)
        // → pas de reordonnancement, on les utilise telles quelles
        val orderedTx = transcriptions
        try {
            val path = currentNotePath ?: buildNewNotePath(mode, label)
            val file = File(path); file.parentFile?.mkdirs()
            val origin = resolveSessionOrigin()
            val json = buildWordGroupsJson(words, origin, orderedTx, corrections)
            val doc = buildNoteDocument(json, mode, label, origin)
            val jsonStr = doc.toString(2)
            // Ecriture fichier sur thread background pour eviter ANR
            Thread {
                try {
                    file.writeText(jsonStr)
                    Log.i(TAG, "Note sauvegardee: $path — ${words.size} mots, ${strokeRegistry.size} strokes")
                } catch (e: Exception) {
                    Log.e(TAG, "Erreur ecriture note: ${e.message}")
                }
            }.start()
            currentNotePath = path
            return path
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sauvegarde note: ${e.message}"); return null
        }
    }

    private fun buildNewNotePath(mode: String, label: String): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dirName = when (mode) { "dictee" -> "raw_capture"; "train" -> "vstar"; else -> "blocnote" }
        val dir = File(context.filesDir, dirName); dir.mkdirs()
        val prefix = when (mode) { "dictee" -> "dictee"; "train" -> if (label.startsWith("bigram")) "bigram" else "word"; else -> "note" }
        val safeLabel = if (label.isNotEmpty() && mode != "blocnote") "_" + label.replace("[^a-zA-Z0-9_]", "_").take(32) else ""
        return File(dir, "${prefix}${safeLabel}_${ts}.note").absolutePath
    }

    private fun resolveSessionOrigin(): Pair<Float, Float> {
        if (strokeRegistry.isEmpty() || strokeRegistry[0].activePoints == 0) return Pair(0f, 0f)
        val p = strokeRegistry[0].points[0]; return Pair(p.first, p.second)
    }

    private fun buildWordGroupsJson(
        groups: List<List<Int>>, sessionOrigin: Pair<Float, Float>,
        transcriptions: List<String>?, corrections: List<String>?
    ): org.json.JSONArray {
        val arr = org.json.JSONArray()
        for ((gi, group) in groups.withIndex()) {
            if (group.isEmpty()) continue
            val wordObj = org.json.JSONObject()
            wordObj.put("transcription", transcriptions?.getOrElse(gi) { "" } ?: "")
            wordObj.put("correction", corrections?.getOrElse(gi) { "" } ?: "")

            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for (si in group) {
                if (si >= strokeRegistry.size) continue
                val s = strokeRegistry[si]
                for (k in 0 until s.activePoints) {
                    if (s.points[k].first < minX) minX = s.points[k].first
                    if (s.points[k].second < minY) minY = s.points[k].second
                    if (s.points[k].second > maxY) maxY = s.points[k].second
                }
            }
            val snappedY = snapToLine((minY + maxY) / 2f)
            val ox = minX - sessionOrigin.first; val oy = snappedY - sessionOrigin.second
            wordObj.put("origin", org.json.JSONArray(listOf(ox.toDouble(), oy.toDouble())))

            val strokesArr = org.json.JSONArray()
            for (si in group) {
                if (si >= strokeRegistry.size) continue
                val s = strokeRegistry[si]
                val strokeObj = org.json.JSONObject()
                strokeObj.put("seq", strokesArr.length())
                strokeObj.put("ornament", false)
                val ptsArr = org.json.JSONArray()
                for (k in 0 until s.activePoints) {
                    val ptArr = org.json.JSONArray()
                    ptArr.put((s.points[k].first - minX).toDouble())
                    ptArr.put((s.points[k].second - snappedY).toDouble())
                    ptArr.put(s.pressures.getOrElse(k) { 0.5f }.toDouble())
                    ptArr.put(if (k == 0) 0L else s.timestamps[k] - s.timestamps[k - 1])
                    ptsArr.put(ptArr)
                }
                strokeObj.put("points", ptsArr)
                strokesArr.put(strokeObj)
            }
            wordObj.put("strokes", strokesArr)
            wordObj.put("tokens", org.json.JSONArray())
            arr.put(wordObj)
        }
        return arr
    }

    private fun buildNoteDocument(words: org.json.JSONArray, mode: String, label: String, origin: Pair<Float, Float>): org.json.JSONObject {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val doc = org.json.JSONObject()
        doc.put("format", "parnasse.note.v1")
        doc.put("device", "Boox Note Air 5C")
        doc.put("created", now)
        doc.put("mode", mode)
        if (label.isNotEmpty()) doc.put("label", label)
        doc.put("sessionOrigin", org.json.JSONArray(listOf(origin.first.toDouble(), origin.second.toDouble())))
        doc.put("words", words)
        return doc
    }

    fun loadNoteFile(file: File): Boolean {
        return try {
            val json = org.json.JSONObject(file.readText())
            closeRawWriter(); closeDebugLog(); clear()
            val mode = json.optString("mode", "blocnote")
            isBlocnoteMode = (mode == "blocnote")
            // Restaurer le sessionOrigin du document
            val sessArr = json.optJSONArray("sessionOrigin")
            val sx = sessArr?.optDouble(0, 0.0)?.toFloat() ?: 0f
            val sy = sessArr?.optDouble(1, 0.0)?.toFloat() ?: 0f
            val words = json.optJSONArray("words") ?: return false

            // Reconstruire les groupes explicites (tels que sauvegardés)
            val loadedGroups = mutableListOf<List<Int>>()
            for (wi in 0 until words.length()) {
                val word = words.getJSONObject(wi)
                val origArr = word.optJSONArray("origin")
                val ox = origArr?.optDouble(0, 0.0)?.toFloat() ?: 0f
                val oy = origArr?.optDouble(1, 0.0)?.toFloat() ?: 0f
                val strokesArr = word.optJSONArray("strokes") ?: continue
                val groupIndices = mutableListOf<Int>()
                for (si in 0 until strokesArr.length()) {
                    val pts = strokesArr.getJSONObject(si).getJSONArray("points")
                    val sr = StrokeRecord()
                    for (pi in 0 until pts.length()) {
                        val pt = pts.getJSONArray(pi)
                        // Restaurer les coordonnees absolues:
                        // abs_x = sessionOrigin.x + origin.x + relative_x
                        sr.points.add(Pair(
                            sx + ox + pt.getDouble(0).toFloat(),
                            sy + oy + pt.getDouble(1).toFloat()))
                        sr.pressures.add(pt.optDouble(2, 0.5).toFloat())
                        sr.timestamps.add(pt.optLong(3, 0L))
                    }
                    strokeRegistry.add(sr)
                    groupIndices.add(strokeRegistry.size - 1)  // index dans le registre
                }
                if (groupIndices.isNotEmpty()) {
                    loadedGroups.add(groupIndices.toList())
                }
            }
            // Restaurer les groupes et marquer comme tous inférés
            wordGroupsCache = loadedGroups
            inferredGroupCount = loadedGroups.size

            currentNotePath = file.absolutePath
            rebuildBitmap()
            currentMode = CaptureMode.EDIT
            onModeChanged?.invoke(currentMode)
            Log.i(TAG, "Note chargee: ${file.name} — ${strokeRegistry.size} strokes, ${loadedGroups.size} groupes")
            postInvalidate(); true
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement note: ${e.message}"); false
        }
    }

    fun updateWordTranscription(wordIndex: Int, transcription: String): Boolean {
        if (currentNotePath == null) return false
        return try {
            val file = File(currentNotePath!!)
            if (!file.exists()) return false
            val json = org.json.JSONObject(file.readText())
            val words = json.getJSONArray("words")
            if (wordIndex < 0 || wordIndex >= words.length()) return false
            words.getJSONObject(wordIndex).put("transcription", transcription)
            file.writeText(json.toString(2)); true
        } catch (e: Exception) { Log.w(TAG, "Erreur transcription: ${e.message}"); false }
    }

    /** Lit toutes les transcriptions d'une note chargee */
    fun getNoteTranscriptions(): List<String>? {
        if (currentNotePath == null) return null
        return try {
            val file = File(currentNotePath!!)
            if (!file.exists()) return null
            val json = org.json.JSONObject(file.readText())
            val words = json.optJSONArray("words") ?: return null
            
            // Lire avec position pour trier par ordre visuel
            data class WordInfo(val index: Int, val text: String, val lineY: Float, val originX: Float)
            val infos = mutableListOf<WordInfo>()
            for (wi in 0 until words.length()) {
                val w = words.getJSONObject(wi)
                val t = w.optString("transcription", "")
                val origin = w.optJSONArray("origin")
                val ox = origin?.optDouble(0)?.toFloat() ?: 0f
                val oy = origin?.optDouble(1)?.toFloat() ?: 0f
                if (t.isNotBlank()) {
                    // Utiliser l'origine Y du mot pour déterminer la ligne
                    infos.add(WordInfo(wi, t, oy, ox))
                }
            }
            // Trier par ligne Y puis par X
            val lineHeight = 80f
            infos.sortWith(compareBy<WordInfo> { (it.lineY / lineHeight).toInt() }.thenBy { it.originX })
            infos.map { it.text }
        } catch (e: Exception) { Log.w(TAG, "Erreur lecture transcriptions: ${e.message}"); null }
    }

    /** Ouvre le raw writer avec un label (appelé depuis CaptureActivity pour le training)
     *  Force raw_capture/ même si isBlocnoteMode est true. */
    fun openWriterForLabel(label: String) {
        val savedBlocnote = isBlocnoteMode
        isBlocnoteMode = false
        openRawWriter(label)
        isBlocnoteMode = savedBlocnote
    }

    /** Ouvre le raw writer lazyment au premier trait */
    private fun ensureRawWriterOpen() {
        if (rawWriter == null) {
            openRawWriter("")
        }
    }

    /** Ferme le writer et redemarre une session vierge (bloc-note) */
    fun closeRawWriterForBlocNote() {
        closeRawWriter()
    }

    /** Demarre une nouvelle session bloc-note sans label.
     *  Le fichier CSV n'est cree qu'au premier trait (lazy).
     */
    fun startBlocNoteSession() {
        clear()
        initDebugLog()
        rawSessionLabel = ""
        currentNotePath = null  // nouvelle page → nouveau fichier a la validation
        currentMode = CaptureMode.CAPTURE
        reloadAutoInferDelay()  // charger le délai configuré
        startCursorAnimation()  // point clignotant
        onModeChanged?.invoke(currentMode)
        Log.i(TAG, "Bloc-note: nouvelle page (lazy, mode CAPTURE)")
    }

    /** Log un evenement onTouchEvent dans le CSV debug */
    private fun logRawOnTouchEvent(event: MotionEvent) {
        if (!DEBUG_RAW) return
        val w = debugWriter ?: return
        try {
            val action = actionName(event.actionMasked)
            val hSize = event.historySize
            // Points historiques (batches)
            for (hi in 0 until hSize) {
                pointSeq++
                val hx = event.getHistoricalX(hi)
                val hy = event.getHistoricalY(hi)
                val hp = event.getHistoricalPressure(hi)
                val ht = event.getHistoricalEventTime(hi)
                w.write("$action,hist[$hi],$pointSeq,$ht,$hx,$hy,$hp\n")
            }
            // Point courant
            pointSeq++
            w.write("$action,curr,$pointSeq,${event.eventTime},${event.x},${event.y},${event.getPressure()},${event.getToolType(0)}\n")
            w.flush()
        } catch (_: IOException) {}
    }

    /** Enregistre le stroke termine dans le registre */
    /** Callback appele quand un groupe de mots est complete par la detection spatiale */
    var onWordGroupCompleted: ((strokes: List<StrokeRecord>, group: List<Int>) -> Unit)? = null

    /** Nombre de groupes deja inferes (pour eviter les doublons) */
    private var inferredGroupCount = 0

    /** Index du groupe réactivé (-1 = aucun), pour forcer l'absorption */
    var reactivatedGroupIndex = -1
        private set

    private fun registerCompletedStroke() {
        val ds = drawingStroke ?: return
        strokeRegistry.add(ds)

        // Absorption directe si groupe réactivé
        if (reactivatedGroupIndex >= 0 && strokeRegistry.size >= 2) {
            // Forcer le recalcul sans cache
            wordGroupsCache = null
            val groups = computeWordGroups()
            val targetGroup = groups.getOrNull(reactivatedGroupIndex)
            val newIdx = strokeRegistry.size - 1
            Log.i(TAG, "⚡ ABSORB CHECK: reactIdx=$reactivatedGroupIndex groups=${groups.size} target=${targetGroup?.size} newIdx=$newIdx")
            if (targetGroup != null && targetGroup.isNotEmpty()) {
                // Chercher la distance min entre le nouveau stroke et le groupe cible
                var minDist = Float.MAX_VALUE
                for (tidx in targetGroup) {
                    if (tidx >= newIdx) continue
                    val st = strokeRegistry[tidx]
                    for (k1 in 0 until st.activePoints step 4) {
                        val p1x = st.points[k1].first; val p1y = st.points[k1].second
                        for (k2 in 0 until ds.activePoints step 4) {
                            val dx = p1x - ds.points[k2].first
                            val dy = p1y - ds.points[k2].second
                            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (d < minDist) minDist = d
                        }
                    }
                }
                val threshold = maxOf(
                    CalibrationActivity.getSpatialDistanceX(context),
                    CalibrationActivity.getSpatialDistanceY(context)
                ) * 2.5f
                Log.i(TAG, "⚡ ABSORB: minDist=$minDist threshold=$threshold")
                if (minDist < threshold) {
                    // Trouver le groupe source (où computeWordGroups a placé le stroke)
                    // et l'en retirer avant de le fusionner dans le groupe cible
                    val sourceGroupIdx = groups.indexOfFirst { newIdx in it }
                    val merged = groups.mapIndexed { gi, g ->
                        when {
                            gi == reactivatedGroupIndex -> g + newIdx
                            gi == sourceGroupIdx -> g.filter { it != newIdx }
                            else -> g
                        }
                    }
                    wordGroupsCache = merged
                    Log.i(TAG, "⚡ ABSORB OK: stroke #$newIdx → groupe #$reactivatedGroupIndex (retiré de #$sourceGroupIdx)")
                } else {
                    Log.i(TAG, "⚡ ABSORB FAIL: dist trop grande, fin réactivation")
                    reactivatedGroupIndex = -1
                }
            }
        } else {
            invalidateWordGroups()
        }

        Log.d(TAG, "Stroke #${strokeRegistry.size}: ${ds.activePoints} pts")
        drawingStroke = null
        checkAutoInfer()
    }

    /** Handler pour le timeout d'inference auto (thread UI) */
    private val autoInferHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autoInferDelayMs = 1500L  // modifiable via SharedPreferences

    private var inferTimeoutRunnable: Runnable? = null

    /** Recharge le délai d'auto-inférence depuis les préférences */
    fun reloadAutoInferDelay() {
        autoInferDelayMs = CalibrationActivity.getAutoInferDelay(context)
    }
    /** Detecte si un nouveau groupe de mots a ete complete et appelle onWordGroupCompleted */
    private fun checkAutoInfer() {
        if (onWordGroupCompleted == null) return
        if (!isBlocnoteMode) return

        val groups = computeWordGroups()
        if (groups.isEmpty()) return

        // Tirer pour les groupes "confirmés" (suivis d'un autre groupe)
        // Le dernier groupe attend le timeout (pas de groupe suivant pour confirmer)
        if (groups.size > inferredGroupCount + 1) {
            val snapshot = strokeRegistry.toList()
            // Ordonner par position visuelle (ligne Y, colonne X) pour l'ordre de lecture
            val ordered = computeVisualOrder(groups) ?: groups
            for (gi in inferredGroupCount until ordered.size - 1) {
                Log.i(TAG, "Auto-infer: groupe $gi/${ordered.size} (${ordered[gi].size} strokes)")
                onWordGroupCompleted?.invoke(snapshot, ordered[gi].toList())
            }
            inferredGroupCount = ordered.size - 1
        }

        // Timeout pour le dernier groupe ou le groupe réactivé
        inferTimeoutRunnable?.let { autoInferHandler.removeCallbacks(it) }
        val r = Runnable {
            val g = computeWordGroups()
            if (g.isEmpty()) return@Runnable
            val snapshot = strokeRegistry.toList()
            val ordered = computeVisualOrder(g) ?: g

            // Inférence pour les groupes non-inférés normaux
            for (gi in inferredGroupCount until ordered.size) {
                Log.i(TAG, "Auto-infer TIMEOUT: groupe $gi/${ordered.size} (${ordered[gi].size} strokes)")
                onWordGroupCompleted?.invoke(snapshot, ordered[gi].toList())
            }
            inferredGroupCount = ordered.size

            // Si un groupe réactivé existe, le ré-inférer aussi
            if (reactivatedGroupIndex >= 0 && reactivatedGroupIndex < ordered.size) {
                Log.i(TAG, "Auto-infer TIMEOUT: groupe réactivé #$reactivatedGroupIndex (${ordered[reactivatedGroupIndex].size} strokes)")
                onWordGroupCompleted?.invoke(snapshot, ordered[reactivatedGroupIndex].toList())
                reactivatedGroupIndex = -1  // réactivation terminée
            }
        }
        inferTimeoutRunnable = r
        autoInferHandler.postDelayed(r, autoInferDelayMs)
    }

    /** Annule le timeout d'inference auto (appele quand un nouveau trait commence) */
    private fun cancelAutoInferTimeout() {
        inferTimeoutRunnable?.let { autoInferHandler.removeCallbacks(it) }
        inferTimeoutRunnable = null
    }

    // -- Conversions de pression ---------------------------------------------

    private fun pressureToInt(pressure: Float): Int {
        return (pressure.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
    }

    // -- tiltX/tiltY -> azimuth + tilt ---------------------------------------

    private fun tiltToAzimuth(tiltX: Int, tiltY: Int): Int {
        if (tiltX == 0 && tiltY == 0) return 0xFF
        val deg = (Math.toDegrees(
            kotlin.math.atan2(tiltY.toDouble(), tiltX.toDouble())
        ).toInt() % 360).coerceIn(0, 359)
        return (deg * 254 / 359).coerceIn(0, 254)
    }

    private fun tiltToTilt(tiltX: Int, tiltY: Int): Int {
        if (tiltX == 0 && tiltY == 0) return 0xFF
        val norm = kotlin.math.sqrt((tiltX * tiltX + tiltY * tiltY).toDouble())
        val deg = (norm.toFloat() / 1000f * 90f).toInt().coerceIn(0, 90)
        return (deg * 254 / 90).coerceIn(0, 254)
    }

    // -- Contexte depuis le MotionEvent (fallback) ---------------------------

    private fun orientationAndTiltFromEvent(
        ev: MotionEvent, histIdx: Int, x: Float, y: Float
    ): Pair<Int, Int> {
        val azByte = try {
            val rad = if (histIdx >= 0)
                ev.getHistoricalAxisValue(MotionEvent.AXIS_ORIENTATION, histIdx)
            else
                ev.getAxisValue(MotionEvent.AXIS_ORIENTATION)
            if (rad.isNaN() || rad < 0f) 0xFF
            else {
                val deg = ((rad * 180f / Math.PI.toFloat()).toInt() % 360).coerceIn(0, 359)
                (deg * 254 / 359).coerceIn(0, 254)
            }
        } catch (_: Exception) { 0xFF }

        val tiltByte = try {
            val tRad = if (histIdx >= 0)
                ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, histIdx)
            else
                ev.getAxisValue(MotionEvent.AXIS_TILT)
            if (tRad.isNaN()) 0xFF
            else {
                val deg = (tRad * 180f / Math.PI.toFloat()).toInt().coerceIn(0, 90)
                (deg * 254 / 90).coerceIn(0, 254)
            }
        } catch (_: Exception) { 0xFF }
        return Pair(azByte, tiltByte)
    }

    // -- Journalisation periodique -------------------------------------------

    private fun logProgress() {
        val now = System.nanoTime()
        if (now - logTimer > 2_000_000_000L) {
            val src = if (useTouchHelper) "TouchHelper" else "onTouchEvent"
            Log.i(TAG, "#$strokeCount strokes | ${strokeRegistry.size} registry | ${pointSeq} events | src: $src")
            logTimer = now
        }
    }

    // =========================================================================
    // RENDU
    // =========================================================================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // Stroke en cours de dessin
        if (currentPath.size >= 2) {
            val path = Path()
            path.moveTo(currentPath[0].first, currentPath[0].second)
            for (i in 1 until currentPath.size) {
                path.lineTo(currentPath[i].first, currentPath[i].second)
            }
            canvas.drawPath(path, strokePaint)
        }

        // Lignes guides (cahier)
        val spacing = height.toFloat() / (guideLines + 1)
        for (i in 1..guideLines) {
            canvas.drawLine(0f, spacing * i, width.toFloat(), spacing * i, guidePaint)
        }

        // Blob visuel — rectangle pointillé autour du dernier groupe non-inféré
        drawActiveGroupBlob(canvas)

        // Curseur de survol en mode CAPTURE — contour bleu-violet pointillé
        drawHoverFeedback(canvas)

        // Point clignotant du groupe actif (à l'origine du 1er stroke)
        drawActiveGroupCursor(canvas)

        // ── DEBUG : indices des groupes ────────────────────────────────
        drawGroupDebugInfo(canvas)

        // SURCOUCHE EDITION
        if (currentMode == CaptureMode.EDIT) {
            // Points d'ancrage : tous les strokes montrent leur premier/dernier point
            for (s in strokeRegistry) {
                if (s.activePoints >= 2) {
                    val first = s.points[0]
                    val last = s.points[s.activePoints - 1]
                    canvas.drawCircle(first.first, first.second, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(80, 100, 149, 237)  // bleu-gris discret
                        style = Paint.Style.FILL
                    })
                    canvas.drawCircle(last.first, last.second, 5f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(80, 100, 149, 237)
                        style = Paint.Style.FILL
                    })
                }
            }

            // Hover: surligne le groupe de mots survole
            val hoverGroup = if (isHovering) hoverWordGroup else null
            if (hoverGroup != null) {
                val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#9370DB")
                    strokeWidth = 5f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                for (idx in hoverGroup) {
                    if (idx < strokeRegistry.size && strokeRegistry[idx].activePoints >= 2) {
                        drawStrokeRecord(canvas, strokeRegistry[idx], hoverPaint)
                        val first = strokeRegistry[idx].points[0]
                        val last = strokeRegistry[idx].points[strokeRegistry[idx].activePoints - 1]
                        canvas.drawCircle(first.first, first.second, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#4169E1")
                            style = Paint.Style.FILL
                        })
                        canvas.drawCircle(last.first, last.second, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#FF6347")
                            style = Paint.Style.FILL
                        })
                    }
                }
                // Ancre du mot precedent (dernier point du stroke avant le groupe)
                val firstInGroup = hoverGroup[0]
                val prevIdx = firstInGroup - 1
                if (prevIdx >= 0 && prevIdx < strokeRegistry.size) {
                    val prev = strokeRegistry[prevIdx]
                    if (prev.activePoints > 0) {
                        val prevLast = prev.points[prev.activePoints - 1]
                        canvas.drawCircle(prevLast.first, prevLast.second, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#9370DB")
                            style = Paint.Style.FILL
                        })
                    }
                }
                // Ancre du mot suivant (premier point du stroke apres le groupe)
                val lastInGroup = hoverGroup[hoverGroup.size - 1]
                val nextIdx = lastInGroup + 1
                if (nextIdx < strokeRegistry.size) {
                    val next = strokeRegistry[nextIdx]
                    if (next.activePoints > 0) {
                        val nextFirst = next.points[0]
                        canvas.drawCircle(nextFirst.first, nextFirst.second, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#20B2AA")
                            style = Paint.Style.FILL
                        })
                    }
                }
            }
            // Selection: surligne le groupe de mots selectionne en vert
            val selGroup = selectedWordGroup
            if (selGroup != null) {
                for (idx in selGroup) {
                    if (idx < strokeRegistry.size && strokeRegistry[idx].activePoints >= 2) {
                        drawStrokeRecord(canvas, strokeRegistry[idx], selectedPaint)
                        val first = strokeRegistry[idx].points[0]
                        canvas.drawCircle(first.first, first.second, 6f, selectionDotPaint)
                    }
                }
            }
        }

        // SURCOUCHE INSERTION
        if (currentMode == CaptureMode.INSERT && insertPending) {
            val zonePaint = Paint().apply {
                color = Color.argb(60, 255, 165, 0)
                style = Paint.Style.FILL
            }
            canvas.drawCircle(insertX, insertY, 30f, zonePaint)
        }
    }

    private fun drawStrokeRecord(canvas: Canvas, sr: StrokeRecord, paint: Paint) {
        if (sr.activePoints < 2) return
        val path = Path()
        path.moveTo(sr.points[0].first, sr.points[0].second)
        for (i in 1 until sr.activePoints) {
            path.lineTo(sr.points[i].first, sr.points[i].second)
        }
        canvas.drawPath(path, paint)
    }

    private fun rasterizeCurrentPath() {
        if (currentPath.size < 2) return
        val path = Path()
        path.moveTo(currentPath[0].first, currentPath[0].second)
        for (i in 1 until currentPath.size) {
            path.lineTo(currentPath[i].first, currentPath[i].second)
        }
        bitmapCanvas?.drawPath(path, strokePaint)
    }

    /** Reconstruit le bitmap a partir du registre de strokes */
    private fun rebuildBitmap() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val path = Path()
        for (stroke in strokeRegistry) {
            if (stroke.activePoints < 2) continue
            path.rewind()
            path.moveTo(stroke.points[0].first, stroke.points[0].second)
            for (i in 1 until stroke.activePoints) {
                path.lineTo(stroke.points[i].first, stroke.points[i].second)
            }
            bitmapCanvas?.drawPath(path, strokePaint)
        }
    }

    // =========================================================================
    // UTILITAIRES
    // =========================================================================

    /** Magnetisme vertical : snap Y a l'interligne */
    private fun snapToLine(y: Float): Float {
        val h = height.toFloat()
        if (h <= 0f) return y  // securite: vue pas encore dimensionnee
        val spacing = h / (guideLines + 1)
        return Math.round(y / spacing) * spacing
    }

    /** Hit test : trouve l'index du stroke le plus proche du point (x, y) */
    fun hitTest(x: Float, y: Float): Int? {
        for (i in strokeRegistry.indices.reversed()) {
            val stroke = strokeRegistry[i]
            for (p in stroke.points) {
                val dx = x - p.first
                val dy = y - p.second
                if (Math.sqrt((dx * dx + dy * dy).toDouble()) < 50.0) return i
            }
        }
        return null
    }

    fun getStrokeCount(): Int = strokeRegistry.size

    /** Expose le registre pour la sauvegarde editeur */
    fun getStrokeRegistry(): MutableList<StrokeRecord> = strokeRegistry

    fun getStats(): String =
        "$strokeCount strokes | ${strokeRegistry.size} registry | $pointSeq events | mode=${currentMode.label}"

    /** Centre Y moyen d'un groupe de mots (moyenne de tous les points) */
    private fun computeGroupCenterY(group: List<Int>): Float {
        var cy = 0f; var count = 0
        for (idx in group) {
            if (idx < strokeRegistry.size) {
                val s = strokeRegistry[idx]
                for (k in 0 until s.activePoints) {
                    cy += s.points[k].second
                    count++
                }
            }
        }
        return if (count > 0) cy / count else 0f
    }

    // =========================================================================
    // GROUPEMENT DE MOTS
    // =========================================================================

    /** Invalide le cache des groupes (appeler apres ajout/suppression/deplacement) */
    private fun invalidateWordGroups() {
        wordGroupsCache = null
    }

    /** Force le recalcul complet des groupes et le rafraîchissement de l'affichage */
    fun recalculateWordGroups() {
        invalidateWordGroups()
        val groups = computeWordGroups()
        Log.i(TAG, "♻️ Recalcul groupes: ${groups.size} groupes, ${strokeRegistry.size} strokes")
        postInvalidate()
    }

    // =========================================================================
    // DEBUG — Décomposition de groupe
    // =========================================================================

    /** Flag activé par le bouton 🪄 : un tap sur un groupe le décompose */
    var decomposeMode = false

    /** Décompose le groupe contenant le stroke ciblé en strokes individuels */
    fun decomposeGroupAt(strokeIndex: Int): Boolean {
        val groups = computeWordGroups()
        val targetGroupIdx = groups.indexOfFirst { strokeIndex in it }
        if (targetGroupIdx < 0) {
            Log.w(TAG, "🪄 Décompose: stroke $strokeIndex introuvable dans les groupes")
            return false
        }
        val targetGroup = groups[targetGroupIdx]
        if (targetGroup.size <= 1) {
            Log.i(TAG, "🪄 Décompose: groupe déjà unitaire (${targetGroup.size} stroke)")
            return false
        }
        // Remplacer le groupe par des groupes d'un seul stroke
        val newGroups = groups.toMutableList()
        newGroups.removeAt(targetGroupIdx)
        for (i in targetGroup.indices.reversed()) {
            newGroups.add(targetGroupIdx, listOf(targetGroup[i]))
        }
        wordGroupsCache = newGroups
        Log.i(TAG, "🪄 Décompose: groupe [$targetGroupIdx] (${targetGroup.size} strokes) → ${targetGroup.size} groupes unitaires")
        postInvalidate()
        return true
    }

    /**
     * Calcule les groupes de mots par distance inter-stroke.
     * Deux strokes consecutifs sont dans le meme mot si l'ecart horizontal
     * est < 1.2× la hauteur mediane des strokes.
     */
    private fun computeWordGroups(): List<List<Int>> {
        wordGroupsCache?.let {
            Log.d(TAG, "computeWordGroups: cache hit (${it.size} groupes)")
            return it
        }
        Log.d(TAG, "computeWordGroups: RECALCUL (cache miss)")

        if (strokeRegistry.isEmpty()) {
            wordGroupsCache = emptyList()
            return emptyList()
        }
        if (strokeRegistry.size == 1) {
            wordGroupsCache = listOf(listOf(0))
            return wordGroupsCache!!
        }

        // Borne X droite (dernier point) et gauche (premier) de chaque stroke
        val rightX = strokeRegistry.map { s ->
            s.points.take(s.activePoints).maxOf { it.first }
        }
        val leftX = strokeRegistry.map { s ->
            s.points.take(s.activePoints).minOf { it.first }
        }

        // Hauteur mediane pour seuils adaptatifs
        val heights = strokeRegistry.mapNotNull { s ->
            if (s.activePoints < 2) null
            else {
                val ys = s.points.take(s.activePoints).map { it.second }
                ys.max() - ys.min()
            }
        }
        val medianHeight = if (heights.isEmpty()) 120f else heights.sorted().let { h -> h[h.size / 2] }
        val threshold = medianHeight * 1.2f

        // Centre Y de chaque stroke pour detection de retour a la ligne
        val yMin = strokeRegistry.map { s -> s.points.take(s.activePoints).minOf { p -> p.second } }
        val yMax = strokeRegistry.map { s -> s.points.take(s.activePoints).maxOf { p -> p.second } }
        val yCenter = strokeRegistry.indices.map { i -> (yMin[i] + yMax[i]) / 2f }
        val lineWrapThreshold = medianHeight * 0.5f

        // Groupement : distance minimale entre strokes + detection ligne (Y+X)
        val groups = mutableListOf<MutableList<Int>>()
        var current = mutableListOf(0)
        for (i in 1 until strokeRegistry.size) {
            val xGapRaw = leftX[i] - rightX[i - 1]

            // Retour a la ligne :
            //  - le stroke COMMENCE nettement PLUS BAS que la fin du precedent
            //    (sinon c'est un jambage descendant qui chevauche en Y)
            //  - ET le stroke commence NETTEMENT a gauche de la fin du precedent
            //    (descendante du 'g' : xGapRaw ~0~20, retour ligne : xGapRaw << -200)
            val startsBelow = yMin[i] > yMax[i - 1] + lineWrapThreshold * 0.3f
            if (startsBelow && xGapRaw < -medianHeight * 0.5f) {
                groups.add(current)
                current = mutableListOf(i)
            } else {
                // Ecart horizontal entre les strokes (absolu, bidirectionnel)
                // Les strokes très à gauche (correction, accent) sont traités
                // comme éloignés → nouveau groupe. L'absorption les fusionnera
                // dans le groupe réactivé si nécessaire.
                val gap = kotlin.math.abs(xGapRaw)
                if (gap < threshold) {
                    current.add(i)
                } else {
                    groups.add(current)
                    current = mutableListOf(i)
                }
            }
        }
        groups.add(current)

        // Consolidation post-hoc : verifier la frontiere entre mots adjacents
        // en utilisant la DISTANCE MINIMALE entre les points des strokes de frontiere
        var ci = 0
        while (ci < groups.size - 1) {
            val lastStrokeIdx = groups[ci].last()
            val firstStrokeIdx = groups[ci + 1].first()
            if (lastStrokeIdx >= strokeRegistry.size || firstStrokeIdx >= strokeRegistry.size) {
                ci++
                continue
            }
            val sLast = strokeRegistry[lastStrokeIdx]
            val sFirst = strokeRegistry[firstStrokeIdx]

            // Distance minimale entre points echantillonnes des deux strokes
            var minDist = Float.MAX_VALUE
            val step = maxOf(1, minOf(sLast.activePoints, sFirst.activePoints) / 20)
            for (k1 in 0 until sLast.activePoints step step) {
                val p1x = sLast.points[k1].first
                val p1y = sLast.points[k1].second
                for (k2 in 0 until sFirst.activePoints step step) {
                    val dx = p1x - sFirst.points[k2].first
                    val dy = p1y - sFirst.points[k2].second
                    val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                    if (d < minDist) minDist = d
                }
            }

            // Si la distance minimale est < seuil ET que les centres Y sont proches :
            // c'est le meme mot mal detecte → fusion
            val yC1 = yCenter.getOrNull(lastStrokeIdx) ?: 0f
            val yC2 = yCenter.getOrNull(firstStrokeIdx) ?: 0f
            if (minDist < threshold && kotlin.math.abs(yC2 - yC1) < lineWrapThreshold) {
                val merged = mutableListOf<Int>()
                merged.addAll(groups[ci])
                merged.addAll(groups[ci + 1])
                groups[ci] = merged
                groups.removeAt(ci + 1)
                // Re-verifier la nouvelle frontiere (ci reste le meme)
            } else {
                ci++
            }
        }

        wordGroupsCache = groups.map { it.toList() }

        // Fusion cross-groupe : après réactivation, un stroke en fin de registre
        // peut être proche d'un groupe antérieur. On vérifie chaque groupe
        // contre TOUS les groupes précédents (pas seulement adjacents).
        var merged = true
        while (merged) {
            merged = false
            for (gi in (wordGroupsCache!!.size - 1) downTo 1) {
                val group = wordGroupsCache!![gi]
                val sIdx = group.first()
                if (sIdx >= strokeRegistry.size) continue
                val sCurr = strokeRegistry[sIdx]
                for (pi in 0 until gi) {
                    val prevGroup = wordGroupsCache!![pi]
                    val pIdx = prevGroup.last()
                    if (pIdx >= strokeRegistry.size) continue
                    val sPrev = strokeRegistry[pIdx]
                    // Distance minimale entre les strokes de frontière
                    var minDist = Float.MAX_VALUE
                    for (k1 in 0 until sPrev.activePoints step 4) {
                        val p1x = sPrev.points[k1].first; val p1y = sPrev.points[k1].second
                        for (k2 in 0 until sCurr.activePoints step 4) {
                            val dx = p1x - sCurr.points[k2].first
                            val dy = p1y - sCurr.points[k2].second
                            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (d < minDist) minDist = d
                        }
                    }
                    val yC1 = yCenter.getOrNull(pIdx) ?: 0f
                    val yC2 = yCenter.getOrNull(sIdx) ?: 0f
                    if (minDist < threshold && kotlin.math.abs(yC2 - yC1) < lineWrapThreshold) {
                        val mergedGroup = prevGroup.toMutableList()
                        mergedGroup.addAll(group)
                        wordGroupsCache = wordGroupsCache!!.toMutableList().apply {
                            set(pi, mergedGroup)
                            removeAt(gi)
                        }
                        merged = true
                        break
                    }
                }
                if (merged) break
            }
        }
        return wordGroupsCache!!
    }

    /** Retourne le groupe de mots contenant l'index stroke, ou null */
    private fun findWordGroup(strokeIndex: Int): List<Int>? {
        return computeWordGroups().find { strokeIndex in it }
    }

    /**
     * Point d'acces public pour la sauvegarde : calcule les groupes
     * de mots tels que vus par le mode edition, pour les injecter dans
     * le fichier VStar (tokens ps=4 separateurs).
     */
    fun computeWordGroupsForSave(): List<List<Int>> {
        val groups = computeWordGroups()
        return computeVisualOrder(groups) ?: groups  // ordre visuel pour la sauvegarde
    }

    /**
     * Reconstruit l'ordre des groupes de mots selon leur position visuelle
     * actuelle dans strokeRegistry. Trie par ligne (Y) puis colonne (X).
     * Utilisé au drop du drag EDIT pour synchroniser fichier ↔ affichage.
     * Retourne null si les groupes sont intacts (même ordre qu'avant).
     */
    private fun computeVisualOrder(words: List<List<Int>>): List<List<Int>>? {
        val lineSpacing = if (guideLines > 0) height.toFloat() / (guideLines + 1) else 80f

        data class WordPos(val line: Int, val centerX: Float, val wordIdx: Int)
        val positions = mutableListOf<WordPos>()

        for ((wi, word) in words.withIndex()) {
            var cx = 0f; var cy = 0f; var count = 0
            for (si in word) {
                if (si < strokeRegistry.size) {
                    val s = strokeRegistry[si]
                    for (k in 0 until s.activePoints) {
                        cx += s.points[k].first
                        cy += s.points[k].second
                        count++
                    }
                }
            }
            if (count > 0) {
                cx /= count; cy /= count
                val line = (cy / lineSpacing).toInt().coerceAtLeast(0)
                positions.add(WordPos(line, cx, wi))
            }
        }

        // Trier par ligne puis colonne
        positions.sortWith(compareBy({ it.line }, { it.centerX }))

        // Vérifier si l'ordre a réellement changé
        val newOrder = positions.map { words[it.wordIdx] }
        val sameOrder = newOrder.size == words.size &&
            newOrder.indices.all { newOrder[it] === words[it] }

        return if (sameOrder) null else newOrder
    }

    /**
     * Réagencer les mots dans l'ordre visuel après un drop EDIT.
     * Place les groupes left→right, top→bottom avec interlignage,
     * en utilisant snapToLine() pour le magnétisme Y.
     * Fixe aussi bien les positions (visuel propre) que
     * l'ordre dans wordGroupsCache (ordre fichier).
     */
    private fun repaginate(words: List<List<Int>>) {
        if (words.isEmpty()) return
        val lineSpacing = if (guideLines > 0) height.toFloat() / (guideLines + 1) else 80f
        val marginLeft = 20f
        val marginRight = width.toFloat() - 20f
        val wordGap = 30f

        var cursorX = marginLeft
        var currentLine = 0f  // Y de la ligne courante (snapée)

        for (word in words) {
            // Bounding box actuelle du mot
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for (si in word) {
                if (si < strokeRegistry.size) {
                    val s = strokeRegistry[si]
                    for (k in 0 until s.activePoints) {
                        minX = minOf(minX, s.points[k].first)
                        maxX = maxOf(maxX, s.points[k].first)
                        minY = minOf(minY, s.points[k].second)
                        maxY = maxOf(maxY, s.points[k].second)
                    }
                }
            }
            val w = (maxX - minX).coerceAtLeast(10f)

            // Retour à la ligne si le mot dépasse la marge droite
            if (cursorX + w > marginRight) {
                currentLine += lineSpacing
                cursorX = marginLeft
            }

            // Centre Y du mot sur la ligne courante
            val wordCenterY = (minY + maxY) / 2f
            val snappedLine = snapToLine(currentLine + lineSpacing / 2f)
            val dx = cursorX - minX
            val dy = snappedLine - wordCenterY

            for (si in word) {
                if (si < strokeRegistry.size) {
                    strokeRegistry[si].translate(dx, dy)
                }
            }

            cursorX += w + wordGap
        }
    }

    // =========================================================================
    // REFLOW — mise en page dynamique pendant le drag
    // =========================================================================

    /**
     * Etat du flux de mots au moment du DOWN.
     * Sauvegarde les metriques necessaires au reflow sans toucher aux strokes.
     */
    data class ReflowState(
        val words: List<List<Int>>,             // indices strokes par mot
        val dragWordIndex: Int,                  // index du mot drague dans words
        val wordWidths: FloatArray,              // largeur de chaque mot
        val interWordGaps: FloatArray,           // ecart mot[i] -> mot[i+1]
        val wordLeftEdge: FloatArray,           // bord gauche du mot (X min)
        val wordCenterY: FloatArray,             // centre Y de chaque mot (offset interligne)
        val yOffsets: FloatArray,                // decalage Y par rapport a l'interligne
        val wordSnappedY: FloatArray,            // snapToLine(centerY) de chaque mot (preserve les sauts de ligne)
        val lineSpacing: Float,                  // hauteur interligne
        val marginLeft: Float,                   // marge gauche
        val marginRight: Float,                  // marge droite (limite de ligne)
        val wordLines: IntArray                  // ligne originale de chaque mot
    )

    /** Initialise l'etat de reflow au debut du drag */
    private fun initReflow(hitStrokeIndex: Int) {
        val words = computeWordGroups()
        if (words.isEmpty()) { flowState = null; return }

        val dragWordIdx = words.indexOfFirst { hitStrokeIndex in it }
        if (dragWordIdx < 0) { flowState = null; return }

        val lineSpacing = if (guideLines > 0) height.toFloat() / (guideLines + 1) else 80f
        val marginLeft = 20f
        val marginRight = width.toFloat() - 20f

        val wordWidths = FloatArray(words.size)
        val wordLeftEdge = FloatArray(words.size)
        val interWordGaps = FloatArray(if (words.size > 1) words.size - 1 else 0)
        val minY = FloatArray(words.size)
        val maxY = FloatArray(words.size)

        for (i in words.indices) {
            var minX = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            minY[i] = Float.MAX_VALUE
            maxY[i] = Float.MIN_VALUE
            for (si in words[i]) {
                if (si >= strokeRegistry.size) continue
                val s = strokeRegistry[si]
                for (k in 0 until s.activePoints) {
                    val x = s.points[k].first
                    val y = s.points[k].second
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY[i]) minY[i] = y
                    if (y > maxY[i]) maxY[i] = y
                }
            }
            wordLeftEdge[i] = minX
            wordWidths[i] = (maxX - minX).coerceAtLeast(10f)
        }

        for (i in 0 until words.size - 1) {
            // Gap = bord droit du mot i - bord gauche du mot i+1
            // Calcule sur TOUS les points du mot (min/max X), pas les extremes des strokes frontiere
            val rightEdgeI = wordLeftEdge[i] + wordWidths[i]
            val leftEdgeNext = wordLeftEdge[i + 1]
            interWordGaps[i] = maxOf(0f, leftEdgeNext - rightEdgeI).coerceAtMost(200f)
        }

        // Decalage Y : hauteur du mot par rapport a l'interligne
        val wordLines = IntArray(words.size)
        val wordCenterY = FloatArray(words.size)
        val yOffsets = FloatArray(words.size)
        val wordSnappedY = FloatArray(words.size)
        for (i in words.indices) {
            val centerY = (minY[i] + maxY[i]) / 2f
            wordCenterY[i] = centerY
            val snapped = snapToLine(centerY)
            yOffsets[i] = centerY - snapped
            wordSnappedY[i] = snapped
            wordLines[i] = (centerY / lineSpacing).toInt().coerceAtLeast(0)
        }

        // Snapshot des positions originales de TOUS les strokes
        flowBackup = mutableListOf()
        for (s in strokeRegistry) {
            flowBackup!!.add(s.points.take(s.activePoints).map { Pair(it.first, it.second) }.toMutableList())
        }

        flowState = ReflowState(
            words = words,
            dragWordIndex = dragWordIdx,
            wordWidths = wordWidths,
            interWordGaps = interWordGaps,
            wordLeftEdge = wordLeftEdge,
            wordCenterY = wordCenterY,
            yOffsets = yOffsets,
            wordSnappedY = wordSnappedY,
            lineSpacing = lineSpacing,
            marginLeft = marginLeft,
            marginRight = marginRight,
            wordLines = wordLines
        )
    }

    /** Applique le reflow apres un deplacement (dx, dy) accumule depuis le DOWN */
    private fun applyReflow(totalDx: Float, totalDy: Float) {
        val backup = flowBackup ?: return
        val fs = flowState ?: return

        // Restaurer les positions originales depuis le snapshot
        for (i in strokeRegistry.indices) {
            if (i < backup.size) {
                val pts = strokeRegistry[i].points
                pts.clear()
                pts.addAll(backup[i])
            }
        }

        val startIdx = fs.dragWordIndex
        // Combien d'interlignes le stylet a traverse (arrondi)
        val penLines = Math.round(totalDy / fs.lineSpacing)
        val snappedDy = penLines.toFloat() * fs.lineSpacing

        // 1. Positionner le mot drague
        val origWordLineY = snapToLine(fs.wordCenterY[startIdx])
        val targetLineY = origWordLineY + snappedDy
        val targetY = targetLineY + fs.yOffsets[startIdx]

        // delta Y par rapport au CENTRE du mot
        translateWord(fs.words[startIdx], totalDx, targetY - fs.wordCenterY[startIdx])

        // 2. Cascade reflow des mots suivants
        var cursorX = wordRightX(fs, startIdx) + totalDx + fs.interWordGaps.getOrElse(startIdx) { 30f }
        var currentLine = (targetLineY / fs.lineSpacing).toInt().coerceAtLeast(0)

        for (i in startIdx + 1 until fs.words.size) {
            val wordW = fs.wordWidths[i]

            // Ligne naturelle du mot : position Y d'origine + decalage vertical du drag
            val naturalLineY = fs.wordSnappedY[i] + snappedDy
            val naturalLine = (naturalLineY / fs.lineSpacing).toInt().coerceAtLeast(0)

            // Detection d'avancement naturel : mot sur sa propre ligne, pas de debordement
            val naturalAdvance = naturalLine > currentLine
            if (naturalAdvance) {
                currentLine = naturalLine
                cursorX = fs.marginLeft
            }

            // Verifier si le mot tient sur cette ligne
            val overflowed = cursorX + wordW > fs.marginRight
            if (overflowed) {
                currentLine++
                cursorX = fs.marginLeft
            }

            // Position Y :
            //  - avancement naturel sans overflow → Y exact d'origine (sauts de ligne preserves)
            //  - overflow ou meme ligne → Y calcule depuis currentLine * lineSpacing
            val lineY = if (naturalAdvance && !overflowed) {
                naturalLineY
            } else {
                currentLine * fs.lineSpacing
            }
            val wordY = lineY + fs.yOffsets[i]
            translateWord(fs.words[i], cursorX - fs.wordLeftEdge[i], wordY - fs.wordCenterY[i])

            cursorX += wordW + fs.interWordGaps.getOrElse(i) { 30f }
        }
    }

    /** Decale tous les strokes d'un mot d'un vecteur (dx, dy) */
    private fun translateWord(wordIndices: List<Int>, dx: Float, dy: Float) {
        for (si in wordIndices) {
            if (si < strokeRegistry.size) {
                strokeRegistry[si].translate(dx, dy)
            }
        }
    }

    /** Retourne la coordonnee X du bord droit d'un mot (calculee depuis initReflow) */
    private fun wordRightX(fs: ReflowState, wordIdx: Int): Float {
        return fs.wordLeftEdge[wordIdx] + fs.wordWidths[wordIdx]
    }

    // -- Chargement fichier VStar (editeur) ----------------------------------

    /**
     * Charge un fichier .vstar existant dans le registre pour edition.
     * Passe automatiquement en mode EDIT.
     *
     * @param file le fichier .vstar a charger
     * @return true si le chargement a reussi
     */
    // =========================================================================
    // LOAD CSV (bloc-note) — Charge les strokes depuis un fichier CSV bloc-note
    // =========================================================================

    /**
     * Charge un fichier CSV bloc-note et peuple le registre de strokes.
     *
     * Format CSV attendu :
     *   # comments ignored
     *   eventType,seq,x,y,pressure,eventTime
     *   DOWN,0,100.0,200.0,0.5,123456789
     *   MOVE,1,105.0,205.0,0.5,123456790
     *   UP,2,110.0,210.0,0.0,123456791
     *
     * @param file le fichier CSV bloc-note a charger
     * @return true si charge correctement
     */
    fun loadCSV(file: File): Boolean {
        closeRawWriter()
        closeDebugLog()

        try {
            val lines = file.readLines()
            clear()

            var currentStroke: StrokeRecord? = null

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

                val parts = trimmed.split(",")
                if (parts.size < 6) continue

                val event = parts[0]
                val x = parts[2].toFloatOrNull() ?: continue
                val y = parts[3].toFloatOrNull() ?: continue
                val p = parts[4].toFloatOrNull() ?: 0f
                val t = parts[5].toLongOrNull() ?: 0L

                when (event) {
                    "DOWN" -> {
                        currentStroke = StrokeRecord(
                            points = mutableListOf(Pair(x, y)),
                            timestamps = mutableListOf(t),
                            pressures = mutableListOf(p)
                        )
                    }
                    "MOVE" -> {
                        currentStroke?.let { s ->
                            s.points.add(Pair(x, y))
                            s.timestamps.add(t)
                            s.pressures.add(p)
                        }
                    }
                    "UP" -> {
                        currentStroke?.let { s ->
                            s.points.add(Pair(x, y))
                            s.timestamps.add(t)
                            s.pressures.add(p)
                            strokeRegistry.add(s)
                        }
                        currentStroke = null
                    }
                }
            }

            rebuildBitmap()
            // En mode bloc-note, rester en CAPTURE pour pouvoir dessiner
            if (!isBlocnoteMode) {
                currentMode = CaptureMode.EDIT
                onModeChanged?.invoke(currentMode)
            }

            Log.i(TAG, "CSV charge: ${file.name} — ${strokeRegistry.size} strokes")
            postInvalidate()
            return true

        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement CSV: ${e.message}")
            return false
        }
    }

    fun loadVStarFile(file: File): Boolean {
        closeDebugLog()
        val doc = VStarDocument(file)
        if (!doc.load()) return false

        clear()
        strokeRegistry.addAll(doc.strokes)

        // Fallback pour les vieux fichiers SANS origine : centrer dans la vue
        if (!doc.hasOrigin && strokeRegistry.isNotEmpty() && width > 0) {
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for (sr in strokeRegistry) {
                for (pt in sr.points) {
                    if (pt.first < minX) minX = pt.first
                    if (pt.first > maxX) maxX = pt.first
                    if (pt.second < minY) minY = pt.second
                    if (pt.second > maxY) maxY = pt.second
                }
            }
            val strokeW = maxX - minX
            val strokeH = maxY - minY
            if (strokeW > 0 && strokeH > 0) {
                val centerX = (maxX + minX) / 2f
                val centerY = (maxY + minY) / 2f
                val targetCX = width / 2f
                val targetCY = height / 3f  // tiers superieur
                for (sr in strokeRegistry) {
                    for (i in sr.points.indices) {
                        sr.points[i] = Pair(
                            sr.points[i].first - centerX + targetCX,
                            sr.points[i].second - centerY + targetCY
                        )
                    }
                }
            }
        }

        rebuildBitmap()
        currentMode = CaptureMode.EDIT
        onModeChanged?.invoke(currentMode)

        Log.i(TAG, "VStar charge: ${file.name} — ${doc.strokes.size} strokes | hasOrigin=${doc.hasOrigin}")
        postInvalidate()
        return true
    }

    // -- Controle de session -------------------------------------------------

    /**
     * Demarre une nouvelle session de capture avec un label pour le debug.
     * @param label nom de la session (mot ecrit, pour identifier le log)
     */
    fun startSession(label: String = "") {
        clear()
        initDebugLog()
        if (label.isNotEmpty()) {
            logRawComment("SESSION: $label")
            val savedBlocnote = isBlocnoteMode
            isBlocnoteMode = false
            openRawWriter(label)
            isBlocnoteMode = savedBlocnote
        }
        Log.i(TAG, "Session started: $label")
    }

    /**
     * Dessine les contours des zones d'absorption (blobs) autour des groupes.
     * Contour seulement (STROKE) — ne cache pas les strokes.
     * Blob ovoïde : rayons X et Y indépendants (réglables).
     * Échantillonnage adaptatif.
     */
    private fun drawActiveGroupBlob(canvas: Canvas) {
        if (currentMode != CaptureMode.CAPTURE) return
        if (!isBlocnoteMode) return
        val groups = wordGroupsCache ?: computeWordGroups()
        if (groups.isEmpty()) return

        val distX = CalibrationActivity.getSpatialDistanceX(context)
        val distY = CalibrationActivity.getSpatialDistanceY(context)
        blobRadiusX = distX * 0.7f
        blobRadiusY = distY * 0.7f
        val sampleStep = ((blobRadiusX + blobRadiusY) / 12f).toInt().coerceIn(1, 6)

        for (gi in groups.indices) {
            val group = groups[gi]
            val isInferred = gi < inferredGroupCount
            // Ne montrer que le blob du groupe réactivé, pas tous les non-inférés
            if (reactivatedGroupIndex < 0) {
                // Mode normal : blob seulement sur le dernier groupe non inféré
                if (isInferred) continue
                if (gi != groups.size - 1) continue
            } else {
                // Mode réactivation : blob seulement sur le groupe réactivé
                if (gi != reactivatedGroupIndex) continue
            }
            val paint = if (isInferred) blobClosedPaint else blobActivePaint
            if (isInferred && group.size > 1) continue

            for (idx in group) {
                if (idx >= strokeRegistry.size) continue
                val s = strokeRegistry[idx]
                var pi = 0
                for ((x, y) in s.points) {
                    if (pi % sampleStep == 0) {
                        // Ovale au lieu du cercle : rayons X et Y distincts
                        canvas.drawOval(
                            x - blobRadiusX, y - blobRadiusY,
                            x + blobRadiusX, y + blobRadiusY,
                            paint
                        )
                    }
                    pi++
                }
            }
        }
    }

    /**
     * Curseur de feedback — contour pointillé bleu-violet autour du groupe
     * survolé en mode CAPTURE (indique quel mot sera réactivé).
     */
    private fun drawHoverFeedback(canvas: Canvas) {
        if (currentMode != CaptureMode.CAPTURE) return
        if (!isBlocnoteMode || !isHovering) return
        val group = hoverWordGroup ?: return

        // Vérifier que ce groupe est bien clôturé (inféré)
        val allGroups = wordGroupsCache ?: computeWordGroups()
        val groupIndex = allGroups.indexOfFirst { it == group }
        if (groupIndex < 0 || groupIndex >= inferredGroupCount) return

        // Calculer la boîte englobante
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        var hasPoints = false
        for (idx in group) {
            if (idx >= strokeRegistry.size) continue
            for ((x, y) in strokeRegistry[idx].points) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
                hasPoints = true
            }
        }
        if (!hasPoints) return

        val pad = 8f
        canvas.drawRect(
            minX - pad, minY - pad, maxX + pad, maxY + pad,
            hoverFeedbackPaint
        )
    }

    /**
     * Curseur clignotant noir — style éditeur de texte.
     * Binaire on/off 500ms, positionné à l'origine du premier stroke du groupe actif.
     */
    private fun drawActiveGroupCursor(canvas: Canvas) {
        if (!cursorVisible) return
        if (currentMode != CaptureMode.CAPTURE) return
        if (!isBlocnoteMode) return
        val groups = wordGroupsCache ?: computeWordGroups()
        if (groups.isEmpty()) return

        val activeGroup: List<Int>? = if (reactivatedGroupIndex >= 0 && reactivatedGroupIndex < groups.size) {
            groups[reactivatedGroupIndex]
        } else {
            groups.lastOrNull()
        }
        if (activeGroup == null || activeGroup.isEmpty()) return

        val firstStrokeIdx = activeGroup.first()
        if (firstStrokeIdx >= strokeRegistry.size) return
        val s = strokeRegistry[firstStrokeIdx]
        if (s.activePoints < 1) return

        val originX = s.points[0].first
        val originY = s.points[0].second

        // Curseur éditeur : barre verticale noire + petit cercle
        val barWidth = 3f
        val barHeight = 28f
        canvas.drawRect(
            originX - barWidth/2, originY - barHeight/2,
            originX + barWidth/2, originY + barHeight/2,
            cursorPaint
        )
        canvas.drawCircle(originX, originY - barHeight/2, 3f, cursorPaint)
    }

    /** Démarre l'animation du curseur (clignotement binaire 500ms) */
    private fun startCursorAnimation() {
        if (cursorAnimRunning) return
        cursorAnimRunning = true
        cursorVisible = true
        val runnable = object : Runnable {
            override fun run() {
                if (!cursorAnimRunning) return
                cursorVisible = !cursorVisible
                invalidate()
                cursorAnimHandler.postDelayed(this, 500)
            }
        }
        cursorAnimHandler.postDelayed(runnable, 500)
    }

    private fun stopCursorAnimation() {
        cursorAnimRunning = false
    }

    /**
     * DEBUG : affiche les indices des groupes et l'état (inféré/réactivé/actif).
     * En rouge au-dessus de chaque groupe pour diagnostic.
     */
    private fun drawGroupDebugInfo(canvas: Canvas) {
        val groups = wordGroupsCache ?: return
        for (gi in groups.indices) {
            val group = groups[gi]
            if (group.isEmpty()) continue
            val firstIdx = group.first()
            if (firstIdx >= strokeRegistry.size) continue
            val s = strokeRegistry[firstIdx]
            if (s.activePoints < 1) continue
            val x = s.points[0].first
            val y = s.points[0].second - 20f

            val state = when {
                gi < inferredGroupCount && gi != reactivatedGroupIndex -> "C"  // Clôturé
                gi == reactivatedGroupIndex -> "R"  // Réactivé
                else -> "A"  // Actif
            }
            canvas.drawText("$gi:$state", x, y, debugTextPaint)
        }
        // Afficher les compteurs en bas à gauche
        canvas.drawText("inf=$inferredGroupCount react=${reactivatedGroupIndex}", 20f, height - 20f, debugTextPaint)
    }

    fun clear() {
        stopCursorAnimation()
        closeRawWriter()
        closeDebugLog()
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        currentPath.clear()
        strokeRegistry.clear()
        drawingStroke = null
        selectedStrokeIndex = null
        dragStrokeIndex = null
        selectedWordGroup = null
        hoverWordGroup = null
        dragWordGroup = null
        wordGroupsCache = null
        strokeCount = 0
        pointSeq = 0
        inferredGroupCount = 0
        reactivatedGroupIndex = -1
        pointInStroke = 0
        hasPrevPoint = false
        insertPending = false
        postInvalidate()
    }
}
