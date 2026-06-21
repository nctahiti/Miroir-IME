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
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
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
 *
 * ═══ ARCHITECTURE DES PIPELINES ═══
 *   PIPELINE 1 (CAPTURE) : TouchHelper → handleCaptureEvent()
 *     → création de strokes, appui long (réactivation), absorption
 *   PIPELINE 2 (EDIT)    : onTouchEvent → handleEditEvent()
 *     → sélection, drag, décomposition (🪄)
 *
 * ═══ SEUILS DE PROXIMITÉ (calibration) ═══
 *   distX (↔) → threshold de groupement + absorption
 *   distY (↕) → lineWrapThreshold (retour à la ligne)
 *   ⚠️ NE PAS utiliser maxOf(distX, distY) — axes indépendants
 *
 * ═══ MACHINE À ÉTATS DES GROUPES ═══
 *   ACTIF → CLÔTURÉ (inferredGroupCount) → RÉACTIVÉ (reactivatedGroupIndex)
 *   L'absorption retire le stroke du groupe source avant fusion (pas de duplicata)
 */
class CaptureView(context: Context) : View(context) {

    private val TAG = "Miroir/Capture"

    // =========================================================================
    // DEBUG MODE — enregistre les coordonnees brutes TouchHelper AVANT encodage
    // =========================================================================
    companion object {
        /** Flag pour activer le debug des coordonnees brutes */
        const val DEBUG_RAW = false  // désactivé — le flush() synchrone cause des ANR
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

            // Mode édition : GroupManager gère les groupes

            invalidate()
            onModeChanged?.invoke(value)
        }

    var onModeChanged: ((CaptureMode) -> Unit)? = null

    /** Registre de tous les strokes traces (pour edition) */
    private val strokeRegistry = mutableListOf<StrokeRecord>()

    // ═══ V4 — CONDUIT V★ + INFÉRENCE ASYNCHRONE ═══
    /** Écriture temps réel des strokes en V★ delta binaire (le conduit) */
    var vstarWriter: VStarWriter? = null
    /** Processeur d'inférence asynchrone (thread background dédié) */
    var strokeProcessor: StrokeProcessor? = null

    /** Compteur monotone pour l'ordre d'écriture (indépendant de l'archivage) */
    private val groupSequenceCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** Throttling du rafraîchissement (e-ink ~35Hz, pas besoin de 60Hz) */
    private var lastInvalidateTime = 0L
    private val minInvalidateIntervalMs = 30L  // ~33 Hz

    /** Désactive tous les overlays visuels (blob, debug, ancres EDIT) pour diagnostic latence */
    var showVisualOverlays = true

    /** Stroke en cours de dessin (null si pas en train de tracer) */
    private var drawingStroke: StrokeRecord? = null

    /** Index du stroke selectionne (null = aucun) */
    var selectedStrokeIndex: Int? = null
        set(value) {
            field = value
            onSelectionChanged?.invoke(value)
        }

    var onSelectionChanged: ((Int?) -> Unit)? = null

    /** Notifié quand le groupe actif change (sélection, désélection, création) */
    var onActiveGroupChanged: (() -> Unit)? = null

    /** Index du stroke en cours de drag */
    private var dragStrokeIndex: Int? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // -- Etat du hover (survol du stylet, EDIT mode) ---------------------------
    private var hoverX = 0f
    private var hoverY = 0f
    private var hoverStrokeIndex: Int? = null

    // -- Groupement de mots (EDIT mode) ---------------------------------------
    // wordGroupsCache + fullGroupsCache supprimés — GroupManager gère les groupes
    private var hoverWordGroup: List<Int>? = null
    private var selectedWordGroup: List<Int>? = null
    private var dragWordGroup: List<Int>? = null

    // -- GroupManager (machine à états WIP — cohabitation progressive) --------
    /** Gestionnaire de groupes avec machine à états (STORED/LOADED/SELECTED/DELEGATED).
     *  Injecté en parallèle de checkAutoInfer. Recevra chaque stroke scellé
     *  pour construire les groupes. Au Cap 7, remplacera checkAutoInfer. */
    // useGroupManager supprimé — GroupManager est le seul chemin

    /** Map inkStroke.id → index dans strokeRegistry (pour reconstruire les groupes) */
    private val inkStrokeIdToRegistryIndex = mutableMapOf<Long, Int>()
    /** Map inverse : index strokeRegistry → inkStroke.id (pour la réactivation hover) */
    private val registryIndexToInkStrokeId = mutableMapOf<Int, Long>()

    /** Retourne le seq (orderIndex) du groupe actif (SELECTED > LOADED), ou null */
    fun getActiveGroupSeq(): Int? {
        val activeGroup = groupManager.groupsInState(GroupState.SELECTED).firstOrNull()
            ?: groupManager.groupsInState(GroupState.LOADED).firstOrNull()
        return activeGroup?.orderIndex
    }

    /** Stocke le seq dans l'InkGroup correspondant au groupe spatial */
    private fun mapSpatialGroupToSeq(spatialIndices: List<Int>, seq: Int) {
        val firstIdx = spatialIndices.firstOrNull() ?: return
        val inkStrokeId = registryIndexToInkStrokeId[firstIdx] ?: return
        val inkGroup = groupManager.findGroupByStroke(inkStrokeId) ?: return
        inkGroup.orderIndex = seq
    }

    val groupManager = GroupManager({ group ->
        // ═══ CAP 7 : bascule — GroupManager remplace checkAutoInfer ═══
        val indices = group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
        if (indices.isEmpty()) return@GroupManager
        val snapshot = strokeRegistry.toList()
        val seq = groupSequenceCounter.getAndIncrement()
        group.orderIndex = seq  // stocker dans l'InkGroup pour liaison transcription
        Log.i(TAG, "GroupManager -> STORED: groupe ${group.id} (${indices.size} strokes, seq=$seq)")
        onWordGroupCompleted?.invoke(snapshot, indices, seq)
        vstarWriter?.writeGroupSep()
    }).also {
        // ═══ Brancher le fournisseur de points pour isStrokeNearGroup point-contre-point ═══
        it.pointProvider = { strokeId ->
            inkStrokeIdToRegistryIndex[strokeId]
                ?.let { registryIdx -> strokeRegistry.getOrNull(registryIdx)?.points }
        }
        // ═══ Quand un groupe SELECTED est auto-désélectionné ═══
        it.onGroupAutoDeselected = {
            // Restaurer les params calibrés (pas de reset à 1px — le blob reste actif)
            syncGroupManagerParams()
            Log.d(TAG, "Params absorption restaurés (auto-désélection)")
            onActiveGroupChanged?.invoke()
        }
    }

    /**
     * Synchronise les paramètres du GroupManager avec la calibration.
     * Lit depuis CalibrationActivity (SharedPreferences) et met à jour BlobParams.
     * Appelé au démarrage et après chaque changement de calibration.
     */
    fun syncGroupManagerParams() {
        val ctx = context
        // Lecture depuis la calibration (SharedPreferences) — source unique
        val calSpatialX = CalibrationActivity.getSpatialDistanceX(ctx)  // rayon horizontal du blob
        val calSpatialY = CalibrationActivity.getSpatialDistanceY(ctx)  // rayon vertical du blob
        val calTemporal = CalibrationActivity.getTemporalDistance(ctx)  // ~800ms
        // Blob = absorption : les valeurs de calibration SONT les rayons du blob.
        // Plus de coefficients cachés (*0.75, *0.7, *0.35).
        // isStrokeNearGroup et drawActiveGroupBlob utilisent les mêmes rx, ry.
        
        groupManager.params = BlobParams(
            spatialDistancePx = calSpatialX,  // DIRECT — rayon blob horizontal
            spatialDistanceY = calSpatialY,   // DIRECT — rayon blob vertical
            minOverlapPercent = 100,  // plus utilisé (absorption par blob elliptique)
            temporalDistanceMs = 0L,  // plus utilisé
            transcriptionTimeoutMs = Long.MAX_VALUE,  // désactivé : inférence via registerCompletedStroke
            groupLevel = GroupLevel.WORD,
            captureAnchor = CaptureAnchor.BOTTOM
        )
        Log.i(TAG, "GroupManager params sync: blobRx=$calSpatialX, blobRy=$calSpatialY, temporal=$calTemporal")
    }

    // -- Reflux de texte
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

    // Poignée d'interligne (zone de sélection)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 80, 80, 80)  // gris discret e-ink
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // -- Blob visuel du groupe actif (avant inférence) ------------------------
    private val blobActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 68, 136, 255)    // bleu très discret (e-ink friendly)
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
    // Ghost : points neutralises par le scrub temporel (affichage debug)
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)  // gris tres pale
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    // Indicateur de mode (bateau/phare/montre) — coin haut-droit
    private val modeIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK  // noir plein, lisible e-ink
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val modeIndicatorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)  // remplissage noir leger
        style = Paint.Style.FILL
    }
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        textSize = 28f
        isFakeBoldText = true
    }
    // Transcriptions par groupe (firstStrokeIndex → texte), peuplé directement à l'inférence
    private val groupTranscriptions = mutableMapOf<Int, String>()
    // Compteur d'inférences par groupe (pour détecter les ré-inférences inutiles)
    private val groupInferenceCount = mutableMapOf<Int, Int>()
    // Horodatage de la dernière inférence (ms)
    private var lastInferenceTime: Long = 0

    /** Appelé par CaptureActivity quand un groupe vient d'être inféré. */
    internal fun onGroupInferred(firstIdx: Int, text: String) {
        groupTranscriptions[firstIdx] = text
        throttledInvalidate()  // rafraîchir le label immédiatement
    }

    /** Accès lecture pour la synchro compagnon (CaptureActivity). */
    internal fun getGroupTranscription(firstIdx: Int): String? = groupTranscriptions[firstIdx]

    /**
     * Retourne les transcriptions dans l'ordre des seedGroups (JSON → ordre spatial de sauvegarde).
     * Les nouveaux groupes (post-chargement) sont ajoutés en fin de liste.
     * Source : groupTranscriptions (firstIdx → texte), pas le .transcription.
     */
    internal fun getOrderedTranscriptions(): List<String> {
        val groups = getSpatialGroups()
        val result = groups.mapNotNull { group ->
            val firstIdx = group.firstOrNull() ?: return@mapNotNull null
            groupTranscriptions[firstIdx]
        }.filter { it.isNotBlank() }
        Log.i(TAG, "📋 getOrderedTranscriptions: ${groups.size} groupes → ${result.size} mots → ${result.take(6).joinToString(" | ")}")
        return result
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

            // ═══ ONYX EPD — mode écriture optimisé ═══
            try {
                EpdController.setScreenHandWritingPenState(this, 1)
                EpdController.enablePost(this, 0)
                EpdController.setViewDefaultUpdateMode(this, UpdateMode.DU)
                Log.i(TAG, "EPD handwriting mode ON (DU, enablePost=0)")
            } catch (e: Exception) {
                Log.w(TAG, "EPD handwriting mode échoué: ${e.message}")
            }
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
        // ═══ ONYX EPD — désactiver le mode écriture ═══
        try {
            EpdController.setViewDefaultUpdateMode(this, UpdateMode.GU)  // retour au mode normal
            EpdController.enablePost(this, 1)
            EpdController.setScreenHandWritingPenState(this, 0)
            Log.i(TAG, "EPD handwriting mode OFF")
        } catch (e: Exception) {
            Log.w(TAG, "EPD release échoué: ${e.message}")
        }
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
                CaptureMode.EDIT, CaptureMode.EDIT_TEMPORAL -> handleEditEvent(event)
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
                    // ⚠️ Pas de deselectAllGroups ici — le hover e-ink est instable
                    // La désélection se fait quand un autre groupe est survolé
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
                // ⚠️ NE PAS changer de mode sur HOVER_EXIT :
                // sur e-ink, le passage hover->contact emet HOVER_EXIT avant ACTION_DOWN
                // → le mode EDIT serait perdu avant meme que le touch n'arrive.
                // Le retour CAPTURE est gere par le tap vide dans handleEditEvent UP.
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
        // Sélection par poignée d'interligne (cohérent avec checkLongHoverReactivation)
        val spatialGroups = getSpatialGroups()
        val spatialBounds = getSpatialBounds()
        val found = spatialGroups.withIndex().firstOrNull { (gi, group) ->
            val r = spatialBounds[gi]
            val groupLine = snapToLine((r.top + r.bottom) / 2f)
            r.left < Float.MAX_VALUE && x >= r.left && x <= r.right && Math.abs(y - groupLine) < 50f
        }
        val newWordGroup = found?.value
        val newStrokeIdx = newWordGroup?.firstOrNull()
        if (newStrokeIdx != hoverStrokeIndex || newWordGroup != hoverWordGroup) {
            hoverStrokeIndex = newStrokeIdx
            hoverWordGroup = newWordGroup
            if (newStrokeIdx != null) {
                Log.d(TAG, "Hover stroke: $newStrokeIdx (${strokeRegistry.size} total)")
            } else {
                // Diagnostic : pourquoi aucun groupe trouvé ?
                val sg = spatialGroups; val sb = spatialBounds
                val nearby = sg.indices.filter { gi ->
                    val r = sb[gi]
                    val gl = snapToLine((r.top + r.bottom) / 2f)
                    r.left < Float.MAX_VALUE
                }
                Log.d(TAG, "Hover: AUCUN groupe — x=$x y=$y, ${sg.size} groupes, " +
                    "candidats(valides)=${nearby.size}, " +
                    "snapLines=[${nearby.take(3).joinToString(",") { gi ->
                        val r = sb[gi]
                        val sl = snapToLine((r.top + r.bottom) / 2f).toInt()
                        "G$gi:Y${sl}r${r.left.toInt()}-${r.right.toInt()} dY=${(y - sl).toInt()}"
                    }}]")
            }
            invalidate()
        }
        // Survol long → sélection via GroupManager
        checkLongHoverReactivation()
    }

    // ── Cache spatial (évite de recalculer computeWordGroups à chaque frame) ──
    private var cachedSpatialGroups: List<List<Int>>? = null
    private var cachedSpatialBounds: List<android.graphics.RectF>? = null
    private var cachedGMCacheSize: Int = -1
    // Groupes chargés depuis .groups — si non-null, computeWordGroups() les restitue
    // sans recalcul spatial. null = mode écriture live (blob 2D normal).
    private var seedGroups: List<List<Int>>? = null

    /** Retourne les groupes spatiaux depuis GroupManager (source unique), avec cache. */
    internal fun getSpatialGroups(): List<List<Int>> {
        val fullSize = groupManager.allGroupsFull().size
        if (cachedGMCacheSize != fullSize) {
            val groups = getSpatialGroupsFromGM()
            cachedSpatialGroups = groups
            cachedSpatialBounds = groups.map { group ->
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
        return cachedSpatialGroups!!
    }

    /** [PHASE 0] Groupes spatiaux depuis GroupManager (source unique).
     *  Convertit les groupes GroupManager (inkStrokeIds) → indices strokeRegistry.
     *  Lit TOUS les groupes (cache + persistance). */
    internal fun getSpatialGroupsFromGM(): List<List<Int>> {
        val allGmGroups = groupManager.allGroupsFull()  // cache + persistance
        return allGmGroups.mapNotNull { group ->
            val indices = group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
            if (indices.isEmpty()) null else indices
        }
    }

    /** Retourne les bounds précalculées des groupes spatiaux. */
    private fun getSpatialBounds(): List<android.graphics.RectF> {
        getSpatialGroups()  // assure le cache
        return cachedSpatialBounds!!
    }

    /** Invalide le cache spatial — force un recalcul au prochain getSpatialGroups(). */
    private fun invalidateSpatialCache() {
        cachedGMCacheSize = -1
        cachedSpatialGroups = null
        cachedSpatialBounds = null
    }

    /** Rafraîchit uniquement les bounds (sans recalculer les groupes).
     *  À appeler après un drag — les strokes ont bougé mais les groupes sont inchangés. */
    private fun refreshSpatialBounds() {
        val groups = cachedSpatialGroups ?: return
        cachedSpatialBounds = groups.map { group ->
            val r = android.graphics.RectF(Float.MAX_VALUE, Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_VALUE)
            for (idx in group) {
                if (idx >= strokeRegistry.size) continue
                val s = strokeRegistry[idx]
                for ((px, py) in s.points.take(s.activePoints)) {
                    if (px < r.left) r.left = px
                    if (px > r.right) r.right = px
                    if (py < r.top) r.top = py
                    if (py > r.bottom) r.bottom = py
                }
            }
            r
        }
    }

    /**
     * Survol long : timer basé sur System.currentTimeMillis() — résistant au jitter e-ink.
     */
    private var longHoverStartMs: Long = 0
    private var longHoverFirstStroke: Int = -1

    private fun checkLongHoverReactivation() {
        if (!isBlocnoteMode) return
        if (currentMode == CaptureMode.EDIT_TEMPORAL) return  // pas de selection hover en mode effacement
        if (!isHovering) { longHoverStartMs = 0; longHoverFirstStroke = -1; return }

        val hx = hoverX; val hy = hoverY
        val spatialGroups = getSpatialGroups()
        val spatialBounds = getSpatialBounds()
        val targetGroup = spatialGroups.withIndex().firstOrNull { (gi, group) ->
            val r = spatialBounds[gi]
            // Sélection par poignée d'interligne : Y proche de la ligne, X dans l'emprise du groupe
            val groupLine = snapToLine((r.top + r.bottom) / 2f)
            r.left < Float.MAX_VALUE && hx >= r.left && hx <= r.right && Math.abs(hy - groupLine) < 50f
        }
        val targetIndices = targetGroup?.value ?: run {
            longHoverStartMs = 0; longHoverFirstStroke = -1; return
        }

        val firstStroke = targetIndices.firstOrNull() ?: run {
            longHoverStartMs = 0; longHoverFirstStroke = -1; return
        }

        // Même groupe ? sinon reset timer
        if (firstStroke != longHoverFirstStroke) {
            longHoverFirstStroke = firstStroke
            longHoverStartMs = System.currentTimeMillis()
            Log.d(TAG, "Survol long — nouveau groupe, timer reset (stroke=$firstStroke)")
            return
        }

        // Assez longtemps ?
        val delayMs = CalibrationActivity.getLongHoverDelay(context)
        if (System.currentTimeMillis() - longHoverStartMs < delayMs) return

        // Déclencher ! (one-shot)
        longHoverStartMs = Long.MAX_VALUE  // empêche de re-déclencher sur le même groupe
        Log.d(TAG, "Survol long — déclenché après ${delayMs}ms")

        // Désélectionner l'ancien
        deselectAllGroups()
        // temporalEraseAvailable SURVIT au changement de groupe (outil du mode)

        // Trouver le stroke dans GroupManager et sélectionner
        val anyStrokeId = registryIndexToInkStrokeId[firstStroke] ?: return
        val g = groupManager.reactivateGroup(anyStrokeId) ?: run {
            Log.w(TAG, "Survol long — reactivateGroup échec pour stroke $firstStroke")
            return
        }
        if (groupManager.selectGroup(g.id)) {
            // ═══ Synchroniser les bounds du groupe GroupManager avec le groupe spatial ═══
            // On calcule les bounds DIRECTEMENT depuis le strokeRegistry (registre = source unique).
            // Les strokeIds GroupManager sont instables (changement de session), mais les indices
            // du strokeRegistry sont la vérité spatiale.
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for (idx in targetIndices) {
                if (idx >= strokeRegistry.size) continue
                for ((px, py) in strokeRegistry[idx].points) {
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }
            }
            if (minX < Float.MAX_VALUE) {
                g.bounds.set(minX, minY, maxX, maxY)
                Log.i(TAG, "Survol long — bounds synchronisées: [${minX.toInt()},${minY.toInt()}][${maxX.toInt()},${maxY.toInt()}]")
            }
            // Synchroniser selectedWordGroup — la SURCOUCHE ÉDITION en a besoin pour le VERT
            selectedWordGroup = targetIndices
            Log.i(TAG, "Survol long — groupe ${g.id} SELECTED (${targetIndices.size} strokes)" )
            onActiveGroupChanged?.invoke()
            invalidate()
        } else {
            Log.w(TAG, "Survol long — selectGroup ÉCHEC pour ${g.id} (state=${g.state}, groups contient=${groupManager.allGroups().any { it.id == g.id }})")
        }
    }

    /** Désélectionne tous les groupes SELECTED → STORED (appelé au HOVER_EXIT) */
    private fun deselectAllGroups() {
        val selected = groupManager.groupsInState(GroupState.SELECTED)
        val allGroups = groupManager.allGroups()
        Log.d(TAG, "deselectAllGroups: ${selected.size} SELECTED / ${allGroups.size} total en cache")
        for (g in selected) {
            groupManager.deselectGroup(g.id)
            Log.d(TAG, "Déselection — groupe ${g.id} SELECTED → STORED")
        }
        // ⚠️ Ne plus reset les params — le blob reste calibré.
        // Le survol long ne change plus les params (plus de minOverlapPercent=0 temporaire),
        // donc le reset à 1px n'est plus nécessaire et cassait l'absorption.
        // Restaurer les params calibrés (au cas où un post-drag les a réduits).
        syncGroupManagerParams()
        if (selected.isNotEmpty()) {
            onActiveGroupChanged?.invoke()
            postInvalidate()
        }
    }

    /** ID du groupe en attente de survol long (pour éviter les réarmements) */
    private var longHoverGroupId: String? = null  // unused, kept for compatibility

    /** Annule le timer de survol long */
    private fun cancelLongHover() {
        longHoverStartMs = 0
        longHoverFirstStroke = -1
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
    private var longPressDisabled = false   // armé si le stylet bouge hors zone → pas de long-press
    // ═══ Effacement temporel (phase 1: structure, phase 2: mécanique) ═══
    private var temporalEraseAvailable = false  // armé après un drag → prochain long-press = effacement
    val temporalMode: Boolean get() = currentMode == CaptureMode.EDIT_TEMPORAL
    private var scrubTimelinePos = 0f            // 0=tout visible, 1=tout neutralise
    private var scrubStartX = 0f                 // X au debut du scrub (repere absolu)
    private var scrubInitialPos = 0f             // timelinePos au debut du scrub
    private var scrubGroupIndices: List<Int>? = null  // groupe en cours de scrub
    private var scrubGroupWidth = 100f              // largeur pixels du groupe scrubbe (pour scale 1:1)
    private var scrubHappened = false              // true si un scrub a eu lieu pendant ce gesture
    private var isWritingInEdit = false          // ecriture en cours depuis le mode EDIT

    /** Largeur pixel du groupe pour le scale 1:1 du scrub (min 50px). */
    private fun computeScrubWidth(indices: List<Int>): Float {
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        for (si in indices) {
            if (si >= strokeRegistry.size) continue
            val s = strokeRegistry[si]
            for (k in 0 until s.activePoints) {
                val px = s.points[k].first
                if (px < minX) minX = px
                if (px > maxX) maxX = px
            }
        }
        return if (minX < Float.MAX_VALUE) (maxX - minX).coerceAtLeast(50f) else 100f
    }

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

        // Double-tap : délégué à GroupManager (hover long gère la sélection)
        if (dt in 100..400 && dx < 40f && dy < 40f) {
            Log.d(TAG, "Double-tap détecté (géré par GroupManager)")
            lastTapTime = 0
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
        val actionName = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            else -> "?${event.actionMasked}"
        }
        Log.d(TAG, "handleEditEvent $actionName @ (${event.x}, ${event.y}) dragWordGroup=${dragWordGroup?.size ?: 0}s wasDrag=$wasDrag longPress=$longPressTriggered")
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editStartX = x; editStartY = y
                wasDrag = false
                // ═══ En mode temporel : nouveau PENDOWN = nouveau scrub ═══
                
                if (currentMode == CaptureMode.EDIT_TEMPORAL) {
                    scrubStartX = x
                    val hitIdx = hitTest(x, y)
                    if (hitIdx == null) {
                        currentMode = CaptureMode.CAPTURE
                        onModeChanged?.invoke(currentMode)
                        invalidate()
                        applyScrubTruncation()
                        Log.d(TAG, "⏳ Tap vide -> sortie EDIT_TEMPORAL")
                        return
                    }
                    scrubInitialPos = scrubTimelinePos
                    scrubHappened = false  // reset pour ce gesture
                    // Garder le meme groupe cible
                    longPressStartX = x; longPressStartY = y
                    longPressStartTime = System.currentTimeMillis()
                    longPressDisabled = false
                    // Recalculer la largeur du groupe cible (a pu changer apres absorption)
                    scrubGroupWidth = scrubGroupIndices?.let { computeScrubWidth(it) } ?: scrubGroupWidth
                    Log.d(TAG, "⏳ PENDOWN en mode temporel — pret pour scrub (initPos=$scrubInitialPos, largeur=${scrubGroupWidth.toInt()}px)")
                    return
                }
                // Armer le timer long-press pour le toggle EDIT_SPATIAL ↔ TEMPORAL
                longPressStartX = x
                longPressStartY = y
                longPressStartTime = System.currentTimeMillis()
                longPressDisabled = false
                scrubHappened = false  // reset pour ce gesture
                val hitIdx = hitTest(x, y)

                // Tap espace vide : defer au ACTION_UP (un vrai tap = retour CAPTURE,
                // un MOVE = ecriture ou long-press temporel)
                if (hitIdx == null && mergeMode) {
                    mergeSourceGroup = null
                    mergeMode = false
                    currentMode = CaptureMode.CAPTURE
                    onModeChanged?.invoke(currentMode)
                    Log.d(TAG, "🔗 Merge annulé (tap espace vide)")
                    invalidate()
                    return
                }

                // 🪄 Mode décomposition : un tap décompose le groupe ciblé
                if (decomposeMode && hitIdx != null) {
                    decomposeGroupAt(hitIdx)
                    invalidate()
                    return
                }

                // 🔗 Mode fusion : premier tap stocke, deuxième tap fusionne
                if (mergeMode && hitIdx != null) {
                    val tappedGroup = findWordGroup(hitIdx) ?: return
                    val source = mergeSourceGroup
                    if (source == null) {
                        // Premier tap : stocker le groupe source
                        mergeSourceGroup = tappedGroup
                        Log.i(TAG, "🔗 Merge mode — groupe source: [${tappedGroup.joinToString(",")}]")
                        invalidate()
                        return
                    } else if (source.any { it in tappedGroup } || tappedGroup.any { it in source }) {
                        // Même groupe → annuler
                        mergeSourceGroup = null
                        Log.i(TAG, "🔗 Merge annulé (même groupe)")
                        invalidate()
                        return
                    } else {
                        // Deuxième tap sur un groupe différent → fusionner
                        mergeGroups(source, tappedGroup)
                        mergeSourceGroup = null
                        mergeMode = false
                        Log.i(TAG, "🔗 Fusion: [${source.joinToString(",")}] + [${tappedGroup.joinToString(",")}]")
                        invalidate()
                        return
                    }
                }

                if (hitIdx != null) {
                    selectedWordGroup = findWordGroup(hitIdx)
                    initReflow(hitIdx)
                    // Sauvegarder l'offset Y du mot par rapport a l'interligne
                    dragWordYOffset = computeGroupCenterY(selectedWordGroup!!) - snapToLine(computeGroupCenterY(selectedWordGroup!!))
                    dragWordGroup = selectedWordGroup
                } else {
                    // Touche le vide — attendre MOVE pour decider :
                    //   mouvement = ecriture, immobile 500ms = long-press temporel, UP sec = tap → CAPTURE
                    selectedWordGroup = null
                    flowState = null
                    dragWordYOffset = 0f
                    dragWordGroup = null
                    Log.d(TAG, "EDIT DOWN — vide, attente MOVE/UP pour decider")
                }
                selectedStrokeIndex = hitIdx
                val wgMsg = if (selectedWordGroup != null) " (mot: [${selectedWordGroup!!.joinToString(",")}])" else ""
                Log.d(TAG, "EDIT DOWN @ ($x, $y) hit=$hitIdx${wgMsg} (registry=${strokeRegistry.size})")
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                // ═══ Ecriture en cours depuis EDIT → forward au pipeline capture ═══
                if (isWritingInEdit) {
                    longPressTriggered = false  // eviter boucle infinie handleCaptureEvent<->handleEditEvent
                    handleCaptureEvent(event)
                    return
                }
                // ═══ Long-press en EDIT : entrer/sortir EDIT_TEMPORAL ═══
                if (!longPressTriggered && !longPressDisabled && temporalEraseAvailable && currentMode == CaptureMode.EDIT) {
                    val dt = System.currentTimeMillis() - longPressStartTime
                    val dx = Math.abs(x - longPressStartX)
                    val dy = Math.abs(y - longPressStartY)
                    // Log diagnostic une fois par gesture (dt ~200ms)
                    if (dt > 200 && dt < 220) {
                        Log.d(TAG, "⏳ LP check: dt=${dt}ms dx=${dx} dy=${dy} trig=${longPressTriggered} dis=${longPressDisabled} avail=${temporalEraseAvailable}")
                    }
                    // Désactiver le long-press si mouvement (avant le timer 350ms)
                    if (dt > 100 && (dx > 15f || dy > 15f)) {
                        longPressDisabled = true
                    }
                    // Seuils assouplis pour e-ink (jitter + MOVE rares si stylet immobile)
                    if (!longPressDisabled && dt > 350 && dx < 40f && dy < 40f) {
                        longPressTriggered = true
                        if (currentMode == CaptureMode.EDIT) {
                            // Entrer en EDIT_TEMPORAL : initialiser le scrub
                            currentMode = CaptureMode.EDIT_TEMPORAL
                            scrubStartX = x
                            scrubInitialPos = scrubTimelinePos
                            // Trouver le groupe sous le stylet (hitTest → spatialGroups)
                            val hitIdx = hitTest(x, y)
                            scrubGroupIndices = if (hitIdx != null) findWordGroup(hitIdx)
                                                else dragWordGroup ?: selectedWordGroup
                            if (scrubGroupIndices == null) {
                                // Fallback: chercher dans les groupes spatiaux
                                val sg = getSpatialGroups()
                                val sb = getSpatialBounds()
                                for (gi in sg.indices) {
                                    val r = sb[gi]
                                    if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                                        scrubGroupIndices = sg[gi]
                                        break
                                    }
                                }
                            }
                            scrubGroupWidth = scrubGroupIndices?.let { computeScrubWidth(it) } ?: 100f
                            Log.i(TAG, "⏳ EDIT_TEMPORAL active (scrubInitPos=$scrubInitialPos, groupe=${scrubGroupIndices?.size}s, largeur=${scrubGroupWidth.toInt()}px)")
                        } else {
                            // Sortir d'EDIT_TEMPORAL → retour EDIT_SPATIAL
                            currentMode = CaptureMode.EDIT
                            dragWordGroup = null
                            selectedWordGroup = null
                            wasDrag = false
                            longPressDisabled = true
                            scrubGroupIndices = null
                            rebuildBitmap()  // restaurer tout le mot
                            throttledInvalidate()
                            Log.i(TAG, "✋ Retour EDIT_SPATIAL (scrubPos=$scrubTimelinePos preserve)")
                        }
                        return  // ce MOVE ne produit pas de drag
                    }
                }
                // ═══ Mode temporel actif : scrub timeline ═══
                if (currentMode == CaptureMode.EDIT_TEMPORAL) {
                    // deltaX negatif = stylet a gauche = reculer dans le temps = effacer
                    val deltaX = scrubStartX - x
                    val scrubScale = scrubGroupWidth  // largeur reelle du groupe → 1:1
                    val prevPos = scrubTimelinePos
                    scrubTimelinePos = (scrubInitialPos + deltaX / scrubScale).coerceIn(0f, 1f)
                    if (Math.abs(scrubTimelinePos - prevPos) > 0.01f) {
                        scrubHappened = true  // un scrub a eu lieu
                        Log.d(TAG, "⏳ scrub: pos=$scrubTimelinePos deltaX=$deltaX groupe=${scrubGroupIndices?.size}s")
                    }
                    // Appliquer au rendu : rebuildBitmap avec filtre temporel
                    rebuildBitmap()
                    refreshSpatialBounds()
                    // Invalidation directe (pas de throttling) — le scrub doit etre reactif
                    invalidate()
                    return
                }
                // ═══ Pas de groupe selectionne + mouvement → debut d'ecriture ═══
                if (dragWordGroup == null && currentMode != CaptureMode.EDIT_TEMPORAL) {
                    val moveDx = Math.abs(x - longPressStartX)
                    val moveDy = Math.abs(y - longPressStartY)
                    if (moveDx > 8f || moveDy > 8f) {
                        // L'utilisateur ecrit dans le vide → forward au pipeline capture
                        isWritingInEdit = true
                        // Rejouer le DOWN pour initialiser le stroke dans handleCaptureEvent
                        val downEvent = MotionEvent.obtain(
                            event.downTime, event.eventTime,
                            MotionEvent.ACTION_DOWN, longPressStartX, longPressStartY, 0
                        )
                        downEvent.setSource(event.source)
                        handleCaptureEvent(downEvent)
                        downEvent.recycle()
                        // Puis forward les MOVE accumules
                        handleCaptureEvent(event)
                        Log.d(TAG, "EDIT — ecriture declenchee (mouvement detecte)")
                        return
                    }
                }
                // ═══ Déplacement spatial (drag normal) ═══
                // Comparer avec la position d'origine (longPressStart), pas editStart
                // car les MOVE consécutifs ont des deltas <1px à 100Hz
                if (dragWordGroup != null && (Math.abs(x - longPressStartX) > 8 || Math.abs(y - longPressStartY) > 8)) {
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

                    // Pendant un long-press drag, utiliser currentPath pour le rendu visible
                    if (longPressTriggered) {
                        updateDragCurrentPath()
                        throttledInvalidate()
                    } else {
                        rebuildBitmap()
                        postInvalidate()
                    }
                }
                // Mettre à jour la référence pour le prochain delta (incrémental)
                editStartX = x
                editStartY = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // ═══ Fallback long-press temporel au UP (e-ink: pas de MOVE si stylet immobile) ═══
                if (!wasDrag && currentMode != CaptureMode.EDIT_TEMPORAL && !isWritingInEdit && !longPressTriggered && !longPressDisabled && temporalEraseAvailable && currentMode != CaptureMode.EDIT_TEMPORAL) {
                    val dt = System.currentTimeMillis() - longPressStartTime
                    val dx = Math.abs(x - longPressStartX)
                    val dy = Math.abs(y - longPressStartY)
                    if (dt > 350 && dx < 40f && dy < 40f) {
                        longPressTriggered = true
                        currentMode = CaptureMode.EDIT_TEMPORAL
                        scrubStartX = x
                        scrubInitialPos = scrubTimelinePos
                        val hitIdx = hitTest(x, y)
                        scrubGroupIndices = if (hitIdx != null) findWordGroup(hitIdx)
                                            else dragWordGroup ?: selectedWordGroup
                        if (scrubGroupIndices == null) {
                            val sg = getSpatialGroups(); val sb = getSpatialBounds()
                            for (gi in sg.indices) {
                                val r = sb[gi]
                                if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom)
                                { scrubGroupIndices = sg[gi]; break }
                            }
                        }
                        // Pas de rebuild — on entre en mode temporel, le prochain MOVE fera le scrub
                        Log.i(TAG, "⏳ EDIT_TEMPORAL active depuis UP (fallback, stylet immobile)")
                        invalidate()
                        return
                    }
                }
                // Tap sur un mot -> toggle EDIT_SPATIAL / EDIT_TEMPORAL
                if (!wasDrag && dragWordGroup != null && !isWritingInEdit && !longPressTriggered && !scrubHappened) {
                    if (currentMode == CaptureMode.EDIT) {
                        currentMode = CaptureMode.EDIT_TEMPORAL
                        scrubStartX = x
                        scrubInitialPos = 0f
                        scrubGroupIndices = selectedWordGroup
                        Log.i(TAG, "Tap mot -> EDIT_TEMPORAL")
                    } else if (currentMode == CaptureMode.EDIT_TEMPORAL) {
                        currentMode = CaptureMode.EDIT
                        // Rafraîchir selectedWordGroup depuis le mot sous le stylet
                        val hitIdx = hitTest(x, y)
                        if (hitIdx != null) {
                            selectedWordGroup = findWordGroup(hitIdx)
                        }
                        Log.i(TAG, "Tap mot -> EDIT_SPATIAL")
                    }
                    onModeChanged?.invoke(currentMode)
                    invalidate()
                    return
                }
                // ═══ Tap sur espace vide (pas de drag, pas d'ecriture, pas de long-press) → CAPTURE ═══
                if (!wasDrag && dragWordGroup == null && currentMode != CaptureMode.EDIT_TEMPORAL && !isWritingInEdit && !longPressTriggered) {
                    currentMode = CaptureMode.CAPTURE
                    onModeChanged?.invoke(currentMode)
                    deselectAllGroups()
                    temporalEraseAvailable = false
                    Log.d(TAG, "EDIT → CAPTURE (tap vide, pas de drag)")
                    invalidate()
                    return
                }
                // ═══ Fin d'écriture depuis EDIT → forward + rester en EDIT ═══
                if (isWritingInEdit) {
                    isWritingInEdit = false
        modeIndicatorLogged = false
                    handleCaptureEvent(event)
                    // Le stroke est absorbé dans le groupe SELECTED (si proche)
                    // On reste en EDIT_SPATIAL, le groupe reste sélectionné
                    rebuildBitmap()
                    throttledInvalidate()
                    Log.d(TAG, "EDIT UP — ecriture terminee, reste en EDIT")
                    return
                }
                // Réordonnancement : après un drag, l'ordre visuel des mots
                // devient l'ordre de lecture dans le fichier.
                if (wasDrag && dragWordGroup != null && flowState != null) {
                    val newOrder = computeVisualOrder(flowState!!.words)
                    if (newOrder != null) {
                        // wordGroupsCache removed — groups from GroupManager
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
                        postInvalidate()
                        Log.i(TAG, "Réordonnancement: ${newOrder.size} groupes")
                    }
                    // Après un drag réussi, retour en CAPTURE
                    // ═══ Réouvrir le groupe pour absorption (mot ouvert) ═══
                    val movedGroup = dragWordGroup
                    val firstIdx = movedGroup?.firstOrNull()
                    if (firstIdx != null) {
                        val inkId = registryIndexToInkStrokeId[firstIdx]
                        if (inkId != null) {
                            val gmGroup = groupManager.reactivateGroup(inkId)
                            if (gmGroup != null) {
                                deselectAllGroups()  // une seule session SELECTED
                                groupManager.selectGroup(gmGroup.id)
                                val wordSpatial = (CalibrationActivity.getSpatialDistanceX(context) * 0.5f).coerceIn(15f, 40f)
                                groupManager.params = groupManager.params.copy(spatialDistancePx = wordSpatial)
                                Log.d(TAG, "Groupe ${gmGroup.id} réouvert après drag (absorption ON, seuil=$wordSpatial)")
                            }
                        }
                    }
                    // ═══ Phase 1: armer l'effacement temporel pour le prochain long-press ═══
                    temporalEraseAvailable = true
                    Log.d(TAG, "Effacement temporel disponible (prochain long-press = ⏳)")
                    dragWordGroup = null
                    flowState = null
                    flowBackup = null
                    // selectedWordGroup conservé — le groupe déplacé garde sa surbrillance verte
                    // Rester en ÉDITION — le PENDOWN ou HOVER_EXIT ramènera en CAPTURE
                    // (isHovering est false pendant le drag, le hover arrive après UP)
                    Log.d(TAG, "ÉDITION maintenu (hover actif, re-drag possible)")
                    refreshSpatialBounds()  // strokes déplacés → bounds à jour, groupes inchangés
                }
                // ═══ Mode temporel TIENT au UP — sortie uniquement par long-press toggle ═══
                // Si pas de drag, garder la sélection active (re-grab possible)
                // Mais si on vient d'un long-press, nettoyer currentPath et rebuild
                if (longPressTriggered) {
                    longPressTriggered = false
                    currentPath.clear()
                    // Ne pas rebuild si on sort de temporalMode (deja fait)
                    if (currentMode != CaptureMode.EDIT_TEMPORAL) rebuildBitmap()
                    postInvalidate()
                }
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
        // Si un long-press a basculé en mode ÉDITION, forwarder au handler EDIT
        if (longPressTriggered && (currentMode == CaptureMode.EDIT || currentMode == CaptureMode.EDIT_TEMPORAL)) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                longPressTriggered = false
            }
            handleEditEvent(event)
            return
        }
        logRawOnTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // cancelAutoInferTimeout (GroupManager gere)

                // Absorption gérée par GroupManager.onStrokeSealed() avec
                // les params par défaut. Le survol est purement visuel.

                // Enregistrer la position pour détection d'appui long
                longPressStartX = event.x
                longPressStartY = event.y
                longPressStartTime = System.currentTimeMillis()
                longPressTriggered = false
                longPressDisabled = false

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
                // ═══ V4 CONDUIT : écriture delta V★ ═══
                vstarWriter?.writePoint(x, y, t, 0.5f, isPenDown = true)
            }

            MotionEvent.ACTION_MOVE -> {
                // Si le stylet sort significativement de la zone de stabilité,
                // on est en train d'écrire — PAS un long-press. Désactiver pour ce stroke.
                if (!longPressDisabled && !longPressTriggered && isBlocnoteMode) {
                    val moveDx = Math.abs(event.x - longPressStartX)
                    val moveDy = Math.abs(event.y - longPressStartY)
                    if (moveDx > 30f || moveDy > 30f) {
                        longPressDisabled = true
                    }
                }
                // Détection d'appui long (>500ms sans bouger) → basculer en mode ÉDITION
                if (!longPressTriggered && !longPressDisabled && isBlocnoteMode && currentMode == CaptureMode.CAPTURE) {
                    val dt = System.currentTimeMillis() - longPressStartTime
                    val dx = Math.abs(event.x - longPressStartX)
                    val dy = Math.abs(event.y - longPressStartY)
                    if (dt > 500 && dx < 15f && dy < 15f) {
                        longPressTriggered = true
                        // ═══ LONG-PRESS → DRAG : annuler le stroke, préparer le drag ═══
                        drawingStroke = null
                        currentPath.clear()
                        currentMode = CaptureMode.EDIT
                        currentMode = CaptureMode.EDIT_TEMPORAL  // mode effacement par defaut
                        deselectAllGroups()  // quitter SELECTED, groupe en mode scrub
                        currentPath.clear()  // nettoyer les residus de currentPath
                        onModeChanged?.invoke(currentMode)
                        temporalEraseAvailable = true  // arme des l'entree en EDIT
                        scrubTimelinePos = 0f          // reset scrub
                        // Trouver le mot sous le stylet
                        val hitIdx = hitTest(longPressStartX, longPressStartY)
                        if (hitIdx != null) {
                            selectedWordGroup = findWordGroup(hitIdx)
                            initReflow(hitIdx)
                            if (selectedWordGroup != null) {
                            scrubGroupIndices = selectedWordGroup
                            scrubStartX = longPressStartX
                                dragWordYOffset = computeGroupCenterY(selectedWordGroup!!) - snapToLine(computeGroupCenterY(selectedWordGroup!!))
                            }
                            dragWordGroup = selectedWordGroup
                            selectedStrokeIndex = hitIdx
                            editStartX = longPressStartX
                            editStartY = longPressStartY
                            wasDrag = false
                            if (currentMode == CaptureMode.EDIT_TEMPORAL) {
                                // Mode effacement: le mot reste dans le bitmap
                                Log.i(TAG, "Long-press -> TEMPORAL: mot ${selectedWordGroup?.size ?: 0}s")
                            } else {
                                updateDragCurrentPath()
                                rebuildBitmap()
                                Log.i(TAG, "Long-press -> DRAG: mot ${selectedWordGroup?.size ?: 0}s")
                            }
                        } else {
                            Log.d(TAG, "Long-press → ÉDITION: aucun mot sous le stylet")
                        }
                        throttledInvalidate()
                        return  // Ce MOVE ne produit pas de point de capture
                    }
                }
                // ═══ Long-press actif → drag direct via currentPath ═══
                if (longPressTriggered) {
                    val dx = event.x - editStartX
                    val dy = event.y - editStartY
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                        wasDrag = true
                    }
                    val group = dragWordGroup
                    if (group != null && wasDrag) {
                        // Déplacer dans strokeRegistry
                        for (idx in group) {
                            if (idx < strokeRegistry.size) {
                                strokeRegistry[idx].translate(dx, dy)
                            }
                        }
                        editStartX = event.x
                        editStartY = event.y
                        // Snap Y
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
                        // Mettre à jour currentPath pour le rendu visible
                        updateDragCurrentPath()
                        throttledInvalidate()
                    }
                    return
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
                    // ═══ V4 CONDUIT ═══
                    vstarWriter?.writePoint(hx, hy, ht, hp)
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
                // ═══ V4 CONDUIT ═══
                vstarWriter?.writePoint(x, y, t, p)
                throttledInvalidate()
            }

            MotionEvent.ACTION_UP -> {
                // Si un long-press a eu lieu, finaliser le drag et revenir en CAPTURE
                if (longPressTriggered) {
                    longPressTriggered = false
                    hasPrevPoint = false
                    currentPath.clear()
                    // Réordonnancement après drag
                    if (wasDrag && dragWordGroup != null && flowState != null) {
                        val newOrder = computeVisualOrder(flowState!!.words)
                        if (newOrder != null) {
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
                            Log.i(TAG, "Long-press drag terminé: ${newOrder.size} groupes réordonnés")
                        }
                    }
                    // Nettoyer et revenir en CAPTURE
                    currentPath.clear()  // effacer le currentPath du drag
                    dragWordGroup = null
                    flowState = null
                    flowBackup = null
                    selectedWordGroup = null
                    // Rester en ÉDITION — le PENDOWN ou HOVER_EXIT ramènera en CAPTURE
                    // (isHovering est false pendant le drag, le hover arrive après UP)
                    Log.d(TAG, "ÉDITION maintenu après long-press (hover actif)")
                    // Reconstruire le bitmap avec le mot à sa position finale
                    rebuildBitmap()
                    // Réactiver les timers par groupe (groupes non inférés seulement)
                    val inferDelay = CalibrationActivity.getAutoInferDelay(context)
                    val groups = getSpatialGroups()
                    for ((gi, group) in groups.withIndex()) {
                        if (group.isEmpty()) continue
                        val firstIdx = group.first()
                        if (firstIdx !in inferredGroups && !groupTimers.containsKey(firstIdx)) {
                            val timer = inferExecutor.schedule({
                                armGroupInference(firstIdx)
                            }, inferDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
                            groupTimers[firstIdx] = timer
                        }
                    }
                    Log.d(TAG, "ÉDITION → CAPTURE (stylet levé après long-press)")
                    refreshSpatialBounds()  // strokes déplacés → bounds à jour
                    invalidate()
                    return
                }
                if (!hasPrevPoint) return
                val x = event.x; val y = event.y; val t = event.eventTime; val p = event.getPressure()

                pointInStroke++
                prevX = x; prevY = y; prevT = t
                currentPath.add(Pair(x, y))
                drawingStroke?.points?.add(Pair(x, y))
                drawingStroke?.timestamps?.add(t)
                drawingStroke?.pressures?.add(p)
                writeRawPoint("UP", x, y, p, t)
                // ═══ V4 CONDUIT ═══
                vstarWriter?.writePoint(x, y, t, p, isPenUp = true)

                hasPrevPoint = false
                rasterizeCurrentPath()
                registerCompletedStroke()
                logProgress()
                throttledInvalidate()
                // ═══ Maintenir le mode DU après lever du stylet ═══
                // Le driver Onyx bascule GU→500ms sur penUp. On le force à rester en DU.
                try {
                    EpdController.setScreenHandWritingPenState(this, 1)
                    EpdController.enablePost(this, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "EPD re-assert échoué: ${e.message}")
                }
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
        // Groupes depuis GroupManager (ignore l'archivage)
        // ⚠️ Pas de computeVisualOrder — l'ordre des seedGroups (JSON) est préservé.
        // Le réordonnancement visuel ne se fait qu'après un drag spatial (repaginate).
        val words = computeWordGroupsForSave()
        if (words.isEmpty()) return null
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
            // ═══ Transcription depuis groupTranscriptions (stable, firstIdx) — pas le paramètre (corrompu par .transcription)
            val firstIdx = group.firstOrNull()
            val tx = if (firstIdx != null) groupTranscriptions[firstIdx] ?: "" else ""
            wordObj.put("transcription", tx)
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
            // ═══ Enregistrer les groupes dans GroupManager (survol, reactivation) ═══
            for (groupIndices in loadedGroups) {
                val inkGroup = InkGroup.create()
                for (idx in groupIndices) {
                    val inkStrokeId = (idx + 1).toLong()
                    inkStrokeIdToRegistryIndex[inkStrokeId] = idx
                    registryIndexToInkStrokeId[idx] = inkStrokeId
                    inkGroup.strokeIds.add(inkStrokeId)
                }
                if (inkGroup.strokeIds.isNotEmpty()) {
                    groupManager.registerLoadedGroup(inkGroup)
                }
            }
            Log.i(TAG, "Note chargee: ${loadedGroups.size} groupes enregistres dans GroupManager")

            // ═══ seedGroups : identité préservée au rechargement ═══
            seedGroups = loadedGroups.toList()
            invalidateSpatialCache()

            // ═══ Peupler inferredGroups + groupTranscriptions depuis le .note ═══
            for (wi in 0 until words.length()) {
                val word = words.getJSONObject(wi)
                val transcription = word.optString("transcription", "")
                if (wi < loadedGroups.size && loadedGroups[wi].isNotEmpty()) {
                    val firstIdx = loadedGroups[wi].first()
                    inferredGroups.add(firstIdx)
                    if (transcription.isNotEmpty()) {
                        groupTranscriptions[firstIdx] = transcription
                    }
                }
            }
            Log.i(TAG, "Note chargée: ${inferredGroups.size} groupes marqués inférés, ${groupTranscriptions.size} transcriptions")

            // Log : ordre des seedGroups après chargement
            val seedPreview = seedGroups?.take(8)?.mapIndexed { i, g ->
                val fi = g.firstOrNull()
                val tx = if (fi != null) groupTranscriptions[fi] else "?"
                "[$i]${g.size}s:$tx"
            }?.joinToString(" | ") ?: "null"
            Log.i(TAG, "📋 seedGroups ordre: $seedPreview")

            currentNotePath = file.absolutePath
            rebuildBitmap()
            // Rester en CAPTURE (le mode était forcé à EDIT — bug)
            // L'utilisateur choisit son mode via l'interface
            Log.i(TAG, "Note chargee: ${file.name} — ${strokeRegistry.size} strokes, ${loadedGroups.size} groupes")
            throttledInvalidate(); true
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
        // ═══ V4 : ouvrir le conduit V★ ═══
        vstarWriter?.close()
        vstarWriter = VStarWriter(context)
        vstarWriter?.openNewSession()
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
    /** Callback appelé quand un groupe de mots est complété par la détection spatiale.
     * @param strokes Snapshot de strokeRegistry
     * @param group Indices des strokes dans ce groupe
     * @param groupIndex Index du groupe dans l'ordre visuel (pour ordre d'écriture stable) */
    var onWordGroupCompleted: ((strokes: List<StrokeRecord>, group: List<Int>, groupIndex: Int) -> Unit)? = null

    // inferredGroupCount supprimé — GroupManager gère l'inférence

    // activeStrokeBase supprimé — GroupManager gère l'archivage

    // ── Timers d'inférence par groupe (thread séparé, async) ──
    // Chaque groupe a SON timer, indépendant. Un groupe fermé infère
    // même si l'utilisateur écrit activement dans un autre groupe.
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    // Timers par firstStrokeIndex
    private val groupTimers = mutableMapOf<Int, java.util.concurrent.ScheduledFuture<*>>()
    // Groupes déjà inférés (identifié par l'index du premier stroke — stable)
    private val inferredGroups = mutableSetOf<Int>()

    private fun registerCompletedStroke() {
        val ds = drawingStroke ?: return
        strokeRegistry.add(ds)

        Log.d(TAG, "Stroke #${strokeRegistry.size}: ${ds.activePoints} pts")
        drawingStroke = null

        // ═══ GroupManager : convertir, mapper, injecter ═══
        val registryIdx = strokeRegistry.size - 1  // index 0-based
        val inkStrokeId = (registryIdx + 1).toLong()
        val inkStroke = strokeRecordToInkStroke(ds, inkStrokeId)
        inkStrokeIdToRegistryIndex[inkStrokeId] = registryIdx
        registryIndexToInkStrokeId[registryIdx] = inkStrokeId
        groupManager.onStrokeSealed(inkStroke)
        Log.d(TAG, "GroupManager: stroke #$inkStrokeId → ${groupManager.allGroups().size} groupes actifs")

        // ═══ Nettoyer le cache : évincer les groupes STORED et les LOADED non-actifs ═══
        groupManager.evictInactive()

        throttledInvalidate()

        // ═══ Timers par groupe ═══
        val groups = getSpatialGroups()
        val openIdx = groups.size - 1
        val inferDelay = CalibrationActivity.getAutoInferDelay(context)

        for ((gi, group) in groups.withIndex()) {
            if (group.isEmpty()) continue
            val firstIdx = group.first()
            val isOpen = (gi == openIdx)

            // Groupe ouvert modifié → retirer de inferredGroups pour ré-inférence
            if (isOpen) {
                inferredGroups.remove(firstIdx)
            }

            // Groupe déjà inféré et non modifié → juste nettoyer le timer
            if (firstIdx in inferredGroups) {
                groupTimers.remove(firstIdx)?.cancel(false)
                continue
            }

            // Démarrer ou réarmer le timer pour ce groupe
            groupTimers.remove(firstIdx)?.cancel(false)
            val timer = inferExecutor.schedule({
                armGroupInference(firstIdx)
            }, inferDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            groupTimers[firstIdx] = timer

            if (isOpen) {
                Log.d(TAG, "⏱️ Groupe ouvert G$gi ($firstIdx) → timer ${inferDelay}ms")
            } else {
                Log.d(TAG, "📦 Groupe fermé G$gi ($firstIdx) → timer ${inferDelay}ms indépendant")
            }
        }
    }

    /** Infère un groupe identifié par son firstStrokeIndex (appelé par le timer). */
    private fun armGroupInference(firstIdx: Int) {
        if (firstIdx in inferredGroups) return
        val latest = getSpatialGroups()
        val group = latest.find { it.firstOrNull() == firstIdx } ?: return
        if (group.isEmpty()) return
        val snapshot = strokeRegistry.toList()
        val seq = groupSequenceCounter.getAndIncrement()
        val gi = latest.indexOf(group)
        val count = (groupInferenceCount[firstIdx] ?: 0) + 1
        groupInferenceCount[firstIdx] = count
        lastInferenceTime = System.currentTimeMillis()
        Log.i(TAG, "🧠 Inférer groupe $gi (${group.size}s) → seq=$seq · #$count · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastInferenceTime))}")
        mapSpatialGroupToSeq(group, seq)
        onWordGroupCompleted?.invoke(snapshot, group, seq)
        inferredGroups.add(firstIdx)
        groupTimers.remove(firstIdx)
    }

    /**
     * Convertit un StrokeRecord (V3) en InkStroke (modèle WIP migré).
     * Pont temporaire pour la cohabitation GroupManager ↔ CaptureView.
     * Les timestamps sont convertis ms → ns (×1_000_000).
     */
    private fun strokeRecordToInkStroke(sr: StrokeRecord, id: Long): InkStroke {
        val pts = sr.points.mapIndexed { i, p ->
            InkPoint(
                x = p.first,
                y = p.second,
                pressure = sr.pressures.getOrElse(i) { 0.5f },
                tilt = 0f,
                orientation = 0f,
                distance = 0f,
                timestamp = (sr.timestamps.getOrElse(i) { 0L }) * 1_000_000L,  // ms → ns
                action = when (i) {
                    0 -> InkPoint.ACTION_DOWN
                    sr.points.lastIndex -> InkPoint.ACTION_UP
                    else -> InkPoint.ACTION_MOVE
                },
                toolType = InkPoint.TOOL_STYLUS
            )
        }
        // ⚠️ Utiliser System.currentTimeMillis() (epoch) pour la base de temps,
        // pas event.eventTime (boot) qui est incompatible avec group.modifiedAt.
        val nowMs = System.currentTimeMillis()
        return InkStroke(
            id = id,
            sessionId = 0L,  // sera câblé au Cap 7
            points = pts.toMutableList(),
            startNano = nowMs * 1_000_000L,
            endNano = nowMs * 1_000_000L,
            isSealed = true,
            wasCanceled = false,
            toolParams = null  // nullable dans V3 — rétabli au Cap 7
        )
    }

    /** Handler pour le timeout d'inference auto (thread UI) */
    /** Recharge les paramètres depuis les préférences (appelé après calibration) */
    fun reloadAutoInferDelay() {
        syncGroupManagerParams()
    }

    /** Annule le timeout d'inférence auto (appelé quand un nouveau trait commence) */


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

    /**
     * Invalide la vue avec throttling (max ~33 Hz au lieu de 60 Hz).
     * Le mode DU est activé globalement via setViewDefaultUpdateMode → ~30ms par refresh.
     */
    private fun throttledInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateTime >= minInvalidateIntervalMs) {
            lastInvalidateTime = now
            super.postInvalidate()
        }
    }

    /**
     * Rafraîchissement partiel — uniquement la zone du stroke en cours.
     * Utilise handwritingRepaint (optimisé stylet Onyx) au lieu d'un refresh complet.
     */
    private fun repaintStrokeZone(left: Int, top: Int, right: Int, bottom: Int) {
        val now = System.currentTimeMillis()
        if (now - lastInvalidateTime >= minInvalidateIntervalMs) {
            lastInvalidateTime = now
            try {
                // Marge de 20px autour du stroke pour le rendu anti-aliasé
                EpdController.handwritingRepaint(this,
                    left - 20, top - 20, right + 20, bottom + 20)
            } catch (e: Exception) {
                invalidate(left - 20, top - 20, right + 20, bottom + 20)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ═══ ARRIÈRE-PLAN : blob (derrière les strokes) + guides ═══
        // Blob — derrière les strokes pour ne pas les cacher
        if (showVisualOverlays) drawActiveGroupBlob(canvas)
        // Lignes guides (cahier)
        val spacing = height.toFloat() / (guideLines + 1)
        for (i in 1..guideLines) {
            canvas.drawLine(0f, spacing * i, width.toFloat(), spacing * i, guidePaint)
        }

        // ═══ STROKES : bitmap (complétés) + tracé courant ═══
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // ═══ GHOST TEMPOREL — points neutralises en gris pale (debug 👁) ═══
        if (showVisualOverlays && currentMode == CaptureMode.EDIT_TEMPORAL && scrubTimelinePos > 0f && scrubGroupIndices != null) {
            val scrubSet = scrubGroupIndices!!.toSet()
            // Recalculer le cutoff (meme logique que rebuildBitmap)
            var tMin = Long.MAX_VALUE; var tMax = Long.MIN_VALUE
            for (si in scrubSet) {
                if (si < strokeRegistry.size) {
                    for (t in strokeRegistry[si].timestamps) {
                        if (t < tMin) tMin = t; if (t > tMax) tMax = t
                    }
                }
            }
            if (tMin < tMax) {
                val cutoff = tMax - (scrubTimelinePos * (tMax - tMin)).toLong()
                for (si in scrubSet) {
                    if (si >= strokeRegistry.size) continue
                    val sr = strokeRegistry[si]
                    for (i in 0 until sr.activePoints) {
                        if (i < sr.timestamps.size && sr.timestamps[i] <= cutoff) continue
                        val (px, py) = sr.points[i]
                        // Petit cercle pour chaque point neutralise
                        canvas.drawCircle(px, py, 3f, ghostPaint)
                    }
                }
            }
        }

        // ═══ POIGNÉES D'INTERLIGNE — au-dessus du bitmap, sous le blob ═══
        val groups = getSpatialGroups()
        val bounds = getSpatialBounds()
        for (gi in groups.indices) {
            val r = bounds[gi]
            if (r.left >= Float.MAX_VALUE) continue
            val lineY = snapToLine((r.top + r.bottom) / 2f)
            canvas.drawLine(r.left, lineY, r.right, lineY, handlePaint)
        }

        // Stroke en cours de dessin — au premier plan
        // Supporte les sentinelles NaN pour séparer les strokes (drag multi-stroke)
        if (currentPath.size >= 2) {
            val path = Path()
            var needsMove = true
            for (i in currentPath.indices) {
                val (px, py) = currentPath[i]
                if (px.isNaN() || py.isNaN()) {
                    needsMove = true  // sentinelle: prochain point fera moveTo
                    continue
                }
                if (needsMove) {
                    path.moveTo(px, py)
                    needsMove = false
                } else {
                    path.lineTo(px, py)
                }
            }
            canvas.drawPath(path, strokePaint)
        }

        // Curseur de survol en mode CAPTURE — contour bleu-violet pointillé
        drawHoverFeedback(canvas)

        // Point clignotant du groupe actif (à l'origine du 1er stroke)
        drawActiveGroupCursor(canvas)

        // ── DEBUG : indices des groupes ────────────────────────────────
        if (showVisualOverlays) drawGroupDebugInfo(canvas)

        // SURCOUCHE EDITION
        if (currentMode == CaptureMode.EDIT) {
            // Points d'ancrage : tous les strokes montrent leur premier/dernier point
            if (showVisualOverlays) {
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

        // Indicateur de mode — bateau / phare / montre (toujours au premier plan)
        drawModeIndicator(canvas)
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
    /** Met à jour currentPath avec les points du groupe en cours de drag.
     *  Ainsi le pipeline de rendu normal (DU/handwriting) affiche le mot
     *  comme un tracé en cours, visible pendant le déplacement.
     *  Chaque stroke est séparé pour éviter les lignes inter-strokes. */
    private fun updateDragCurrentPath() {
        currentPath.clear()
        val group = dragWordGroup ?: return
        var firstStroke = true
        for (idx in group) {
            if (idx >= strokeRegistry.size) continue
            val s = strokeRegistry[idx]
            if (s.activePoints < 1) continue
            if (!firstStroke) {
                // Séparateur : point sentinelle avec Float.NaN pour indiquer un saut
                currentPath.add(Pair(Float.NaN, Float.NaN))
            }
            firstStroke = false
            for (i in 0 until s.activePoints) {
                currentPath.add(s.points[i])
            }
        }
    }

    /** Applique definitivement la troncature du scrub temporel aux strokes */
    private fun applyScrubTruncation() {
        val indices = scrubGroupIndices ?: return
        if (scrubTimelinePos <= 0f) return
        var tMin = Long.MAX_VALUE; var tMax = Long.MIN_VALUE
        for (si in indices) {
            if (si >= strokeRegistry.size) continue
            for (t in strokeRegistry[si].timestamps) {
                if (t < tMin) tMin = t
                if (t > tMax) tMax = t
            }
        }
        if (tMin >= tMax) return
        val span = tMax - tMin
        val cutoff = tMax - (scrubTimelinePos * span).toLong()
        var removed = 0
        for (si in indices) {
            if (si >= strokeRegistry.size) continue
            val stroke = strokeRegistry[si]
            var keepIdx = -1
            val newCount = stroke.activePoints
            for (i in 0 until stroke.activePoints) {
                if (i < stroke.timestamps.size && stroke.timestamps[i] <= cutoff) keepIdx = i
            }
            val keep = keepIdx + 1
            removed += (newCount - keep)
            while (stroke.points.size > keep) { stroke.points.removeAt(stroke.points.size - 1) }
            while (stroke.timestamps.size > keep) { stroke.timestamps.removeAt(stroke.timestamps.size - 1) }
            while (stroke.pressures.size > keep) { stroke.pressures.removeAt(stroke.pressures.size - 1) }
        }
        Log.i(TAG, "Scrub permanent: $removed points retires de ${indices.size} strokes")
        scrubGroupIndices = null
        scrubTimelinePos = 0f
        rebuildBitmap()
    }

    private fun rebuildBitmap() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val path = Path()
        // Pendant un drag, exclure le groupe déplacé du bitmap (affiché via currentPath)
        val dragIndices: Set<Int> = if (longPressTriggered && dragWordGroup != null) {
            dragWordGroup!!.toSet()
        } else emptySet()
        // ═══ Filtre temporel : points neutralises par le scrub ═══
        val scrubIndices = scrubGroupIndices?.toSet() ?: emptySet()
        // Calculer le cutoff temporel pour le groupe scrubbé
        var scrubCutoffMs = Long.MAX_VALUE
        if (currentMode == CaptureMode.EDIT_TEMPORAL && scrubTimelinePos > 0f && scrubIndices.isNotEmpty()) {
            // Trouver le timestamp du point le plus recent et le plus ancien du groupe
            var tMin = Long.MAX_VALUE; var tMax = Long.MIN_VALUE
            for (si in scrubIndices) {
                if (si < strokeRegistry.size) {
                    val sr = strokeRegistry[si]
                    for (t in sr.timestamps) {
                        if (t < tMin) tMin = t; if (t > tMax) tMax = t
                    }
                }
            }
            if (tMin < tMax) {
                val span = tMax - tMin
                scrubCutoffMs = tMax - (scrubTimelinePos * span).toLong()
                Log.v(TAG, "⏳ rebuildBitmap: tMin=$tMin tMax=$tMax span=$span cutoff=$scrubCutoffMs pos=$scrubTimelinePos indices=${scrubIndices.size}")
            } else {
                Log.w(TAG, "⏳ rebuildBitmap: tMin=$tMin tMax=$tMax → PAS de span, cutoff=MAX_VALUE")
            }
        }
        var idx = 0
        for (stroke in strokeRegistry) {
            if (stroke.activePoints < 2) { idx++; continue }
            if (idx in dragIndices) { idx++; continue }  // skip dragged word
            // ═══ Filtre temporel : les strokes hors du groupe scrub sont normaux ═══
            val isScrubStroke = idx in scrubIndices && currentMode == CaptureMode.EDIT_TEMPORAL && scrubTimelinePos > 0f
            path.rewind()
            var started = false
            for (i in 0 until stroke.activePoints) {
                if (isScrubStroke && i < stroke.timestamps.size) {
                    val t = stroke.timestamps[i]
                    if (t > scrubCutoffMs) continue  // point neutralise
                }
                if (!started) {
                    path.moveTo(stroke.points[i].first, stroke.points[i].second)
                    started = true
                } else {
                    path.lineTo(stroke.points[i].first, stroke.points[i].second)
                }
            }
            if (started) {
                bitmapCanvas?.drawPath(path, strokePaint)
            }
            idx++
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
    // =========================================================================
    // DEBUG — Décomposition de groupe
    // =========================================================================

    /** Flag activé par le bouton 🪄 : un tap sur un groupe le décompose */
    var decomposeMode = false

    // =========================================================================
    // 🔗 FUSION SÉQUENTIELLE — mode merge
    // =========================================================================

    /** Flag activé par le bouton 🔗 : tap groupe A puis tap groupe B → fusion */
    var mergeMode = false
    /** Groupe source en attente de fusion (premier tap en mode merge) */
    var mergeSourceGroup: List<Int>? = null

    /** Décompose le groupe contenant le stroke ciblé en strokes individuels */
    fun decomposeGroupAt(strokeIndex: Int): Boolean {
        val groups = getSpatialGroups()  // cache réconcilié
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
        // wordGroupsCache removed — groups from GroupManager
        Log.i(TAG, "🪄 Décompose: groupe [$targetGroupIdx] (${targetGroup.size} strokes) → ${targetGroup.size} groupes unitaires")
        throttledInvalidate()
        return true
    }

    /**
     * 🔗 Fusionne deux groupes en un seul.
     * Appelé en mode merge après deux taps sur des groupes différents.
     * Déclenche la ré-inférence du groupe fusionné.
     */
    private fun mergeGroups(groupA: List<Int>, groupB: List<Int>) {
        val currentGroups = getSpatialGroups().map { it.toMutableList() }.toMutableList()
        val idxA = currentGroups.indexOfFirst { it.any { s -> s in groupA } }
        val idxB = currentGroups.indexOfFirst { it.any { s -> s in groupB } }
        if (idxA < 0 || idxB < 0 || idxA == idxB) {
            Log.w(TAG, "🔗 MergeGroups: groupes introuvables ou identiques (A=$idxA, B=$idxB)")
            return
        }

        // Fusionner les deux groupes
        val merged = (currentGroups[idxA] + currentGroups[idxB]).distinct().toMutableList()
        // Retirer les deux anciens groupes (index le plus haut d'abord pour éviter le décalage)
        val highIdx = maxOf(idxA, idxB)
        val lowIdx = minOf(idxA, idxB)
        currentGroups.removeAt(highIdx)
        currentGroups.removeAt(lowIdx)
        // Insérer le groupe fusionné à la position du plus bas
        currentGroups.add(lowIdx, merged)

        // Mettre à jour le cache spatial et les bounds
        cachedSpatialGroups = currentGroups
        cachedSpatialBounds = currentGroups.map { group ->
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

        // Ré-inférence du groupe fusionné
        val seq = groupSequenceCounter.getAndIncrement()
        val snapshot = strokeRegistry.toList()
        mapSpatialGroupToSeq(merged, seq)
        Log.i(TAG, "🔗 MergeGroups: [${groupA.joinToString(",")}] + [${groupB.joinToString(",")}] → [${merged.joinToString(",")}] (seq=$seq)")
        onWordGroupCompleted?.invoke(snapshot, merged, seq)

        rebuildBitmap()
    }

    /** Groupement spatial UNIFIÉ — source unique pour blob/survol/EDIT/sauvegarde.
     *  seedGroups (non-null au rechargement) = contrainte, pas contournement :
     *  les strokes d'un seedGroup restent ensemble, le blob 2D groupe les orphelins. */
    private fun computeWordGroups(): List<List<Int>> {
        val seed = seedGroups
        if (seed == null) {
            // ── MODE ÉCRITURE LIVE : blob 2D spatial + absorption SELECTED ──
            return absorbSelectedGroup(computeSpatialGroupsRaw())
        }
        // ── MODE RECHARGEMENT : seedGroups = contrainte ──
        // La note chargée est une photographie : les groupes sont préservés
        // tels que sauvegardés. Pas d'absorption automatique entre groupes
        // existants — la fusion reste manuelle (bouton 🔗).
        val covered = seed.flatten().toSet()
        val allIndices = strokeRegistry.indices.toSet()
        if (allIndices == covered) return seed

        // Nouveaux strokes forment leurs propres groupes (pas d'absorption)
        val newIndices = (allIndices - covered).toList()
        val newGroups = computeSpatialGroupsFor(newIndices)

        // Pas d'absorption entre seedGroups et newGroups.
        // Les nouveaux strokes restent indépendants jusqu'à ce que
        // l'utilisateur les sélectionne (hover long → SELECTED → absorption live).
        val allGroups = (seed.map { it.toMutableList() } + newGroups.map { it.toMutableList() }).toMutableList()
        return absorbSelectedGroup(allGroups)
    }

    /** Vrai si les bounds (RectF) des deux groupes se chevauchent avec les marges blob 2D. */
    private fun blobIntersects(groupA: List<Int>, groupB: List<Int>): Boolean {
        val distX = CalibrationActivity.getSpatialDistanceX(context) * 0.5f
        val distY = CalibrationActivity.getSpatialDistanceY(context)
        val marginX = maxOf(distX, 5f)
        val marginY = maxOf(distY * 0.3f, 5f)
        fun boundsOf(indices: List<Int>): android.graphics.RectF {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = Float.MIN_VALUE; var b = Float.MIN_VALUE
            for (idx in indices) {
                if (idx >= strokeRegistry.size) continue
                for ((px, py) in strokeRegistry[idx].points.take(strokeRegistry[idx].activePoints)) {
                    if (px < l) l = px; if (px > r) r = px
                    if (py < t) t = py; if (py > b) b = py
                }
            }
            return android.graphics.RectF(l, t, r, b)
        }
        val ba = boundsOf(groupA); if (ba.isEmpty) return false
        val bb = boundsOf(groupB); if (bb.isEmpty) return false
        val expanded = android.graphics.RectF(
            ba.left - marginX, ba.top - marginY,
            ba.right + marginX, ba.bottom + marginY
        )
        return android.graphics.RectF.intersects(expanded, bb)
    }

    /** Blob 2D restreint à un sous-ensemble d'indices (strokes orphelins au rechargement). */
    private fun computeSpatialGroupsFor(indices: List<Int>): List<MutableList<Int>> {
        if (indices.isEmpty()) return emptyList()
        if (indices.size == 1) return mutableListOf(mutableListOf(indices[0]))
        // Rayons DIRECTS du blob — identiques à l'absorption et au groupement
        val rx = CalibrationActivity.getSpatialDistanceX(context)
        val ry = CalibrationActivity.getSpatialDistanceY(context)
        val idxToBounds = mutableMapOf<Int, android.graphics.RectF>()
        for (i in indices) {
            val s = strokeRegistry[i]
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = Float.MIN_VALUE; var b = Float.MIN_VALUE
            for ((px, py) in s.points.take(s.activePoints)) {
                if (px < l) l = px; if (px > r) r = px
                if (py < t) t = py; if (py > b) b = py
            }
            idxToBounds[i] = android.graphics.RectF(l, t, r, b)
        }
        // Test point-contre-point avec le blob (rx, ry)
        fun isNear(idx: Int, groupIndices: List<Int>): Boolean {
            val sp = strokeRegistry.getOrNull(idx) ?: return false
            for ((sx, sy) in sp.points.take(sp.activePoints)) {
                for (gi in groupIndices) {
                    val gs = strokeRegistry.getOrNull(gi) ?: continue
                    for ((gx, gy) in gs.points.take(gs.activePoints)) {
                        val dx = (sx - gx) / rx
                        val dy = (sy - gy) / ry
                        if (dx * dx + dy * dy <= 1.0f) return true
                    }
                }
            }
            return false
        }
        val sorted = indices.toList()
        var current = mutableListOf(sorted[0])
        var gLeft = idxToBounds[sorted[0]]!!.left; var gTop = idxToBounds[sorted[0]]!!.top
        var gRight = idxToBounds[sorted[0]]!!.right; var gBottom = idxToBounds[sorted[0]]!!.bottom
        val groups = mutableListOf<MutableList<Int>>()
        for (k in 1 until sorted.size) {
            val idx = sorted[k]
            // Fast-reject rectangulaire : les bounds gonflees (rx, ry) se touchent-elles ?
            val expanded = android.graphics.RectF(gLeft - rx, gTop - ry, gRight + rx, gBottom + ry)
            if (!android.graphics.RectF.intersects(expanded, idxToBounds[idx]!!)) {
                groups.add(current)
                current = mutableListOf(idx)
                gLeft = idxToBounds[idx]!!.left; gRight = idxToBounds[idx]!!.right
                gTop = idxToBounds[idx]!!.top; gBottom = idxToBounds[idx]!!.bottom
            } else if (isNear(idx, current)) {
                current.add(idx)
                if (idxToBounds[idx]!!.left < gLeft) gLeft = idxToBounds[idx]!!.left
                if (idxToBounds[idx]!!.right > gRight) gRight = idxToBounds[idx]!!.right
                if (idxToBounds[idx]!!.top < gTop) gTop = idxToBounds[idx]!!.top
                if (idxToBounds[idx]!!.bottom > gBottom) gBottom = idxToBounds[idx]!!.bottom
            } else {
                groups.add(current)
                current = mutableListOf(idx)
                gLeft = idxToBounds[idx]!!.left; gRight = idxToBounds[idx]!!.right
                gTop = idxToBounds[idx]!!.top; gBottom = idxToBounds[idx]!!.bottom
            }
        }
        groups.add(current)
        return groups
    }

    /** Groupement spatial pur (distance horizontale + retour à la ligne). */
    /** Groupement spatial 2D par blob — les bounds des strokes qui se chevauchent
     *  (avec marge = distX horizontal, distY*0.4 vertical) sont groupés ensemble.
     *  Indépendant de l'ordre d'écriture — une croix (+) est toujours un seul groupe. */
    /** Groupement spatial 2D par blob, trié par X, séquentiel (pas de fermeture transitive).
     *  marginX = distX plafonné à 25px (assez pour les lettres, pas pour l.espace entre mots). */
    private fun computeSpatialGroupsRaw(): List<MutableList<Int>> {
        if (strokeRegistry.isEmpty()) return emptyList()
        if (strokeRegistry.size == 1) return mutableListOf(mutableListOf(0))

        // Rayons DIRECTS du blob — mêmes rx, ry que l'absorption (isStrokeNearGroup)
        val rx = CalibrationActivity.getSpatialDistanceX(context)
        val ry = CalibrationActivity.getSpatialDistanceY(context)

        // Précalculer les bounds et points de chaque stroke
        val bounds = Array(strokeRegistry.size) { i ->
            val s = strokeRegistry[i]
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE
            var r = Float.MIN_VALUE; var b = Float.MIN_VALUE
            for ((px, py) in s.points.take(s.activePoints)) {
                if (px < l) l = px; if (px > r) r = px
                if (py < t) t = py; if (py > b) b = py
            }
            android.graphics.RectF(l, t, r, b)
        }

        // Test point-contre-point : le nouveau stroke touche-t-il le blob du groupe courant ?
        fun isNearGroup(strokeIdx: Int, groupIndices: List<Int>): Boolean {
            if (strokeIdx >= strokeRegistry.size) return false
            val sp = strokeRegistry[strokeIdx]
            for ((sx, sy) in sp.points.take(sp.activePoints)) {
                for (gi in groupIndices) {
                    val gs = strokeRegistry.getOrNull(gi) ?: continue
                    for ((gx, gy) in gs.points.take(gs.activePoints)) {
                        val dx = (sx - gx) / rx
                        val dy = (sy - gy) / ry
                        if (dx * dx + dy * dy <= 1.0f) return true
                    }
                }
            }
            return false
        }

        // Ordre d.écriture (chronologique) — les strokes d.une même ligne
        // sont écrits ensemble, puis on passe à la ligne suivante.
        // Pas de tri X (casserait les groupes quand un stroke de L2
        // s.intercale horizontalement entre deux strokes de L1).
        val sortedIndices = strokeRegistry.indices.toList()
        var current = mutableListOf(sortedIndices[0])
        var gLeft = bounds[current[0]].left; var gTop = bounds[current[0]].top
        var gRight = bounds[current[0]].right; var gBottom = bounds[current[0]].bottom

        val groups = mutableListOf<MutableList<Int>>()
        for (k in 1 until sortedIndices.size) {
            val idx = sortedIndices[k]
            // Fast-reject rectangulaire : les bounds gonflees (rx, ry) se touchent-elles ?
            val expanded = android.graphics.RectF(gLeft - rx, gTop - ry, gRight + rx, gBottom + ry)
            if (!android.graphics.RectF.intersects(expanded, bounds[idx])) {
                // Trop loin → nouveau groupe, sans test point-contre-point
                groups.add(current)
                current = mutableListOf(idx)
                gLeft = bounds[idx].left; gRight = bounds[idx].right
                gTop = bounds[idx].top; gBottom = bounds[idx].bottom
            } else if (isNearGroup(idx, current)) {
                current.add(idx)
                if (bounds[idx].left < gLeft) gLeft = bounds[idx].left
                if (bounds[idx].right > gRight) gRight = bounds[idx].right
                if (bounds[idx].top < gTop) gTop = bounds[idx].top
                if (bounds[idx].bottom > gBottom) gBottom = bounds[idx].bottom
            } else {
                groups.add(current)
                current = mutableListOf(idx)
                gLeft = bounds[idx].left; gRight = bounds[idx].right
                gTop = bounds[idx].top; gBottom = bounds[idx].bottom
            }
        }
        groups.add(current)

        Log.d(TAG, "computeSpatialGroupsRaw (blob point-contre-point, rx=$rx ry=$ry): ${groups.size} groupes")
        return groups
    }
    /** Fusionne les groupes spatiaux proches du groupe SELECTED (correction/absorption). */
    /** Fusionne les groupes spatiaux contenant des strokes du SELECTED (coherence visuelle).
     *  Ne fusionne PAS les groupes voisins — seule l.appartenance au SELECTED compte.
     *  La fusion entre groupes existants reste manuelle (bouton 🔗). */
    private fun absorbSelectedGroup(spatialGroups: List<MutableList<Int>>): List<List<Int>> {
        val selectedGroups = groupManager.groupsInState(GroupState.SELECTED)
        if (selectedGroups.isEmpty()) return spatialGroups

        val result = spatialGroups.map { it.toMutableList() }.toMutableList()
        for (sel in selectedGroups) {
            val selIndices = sel.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
            if (selIndices.isEmpty()) continue

            // Fusionner les groupes qui contiennent des strokes du SELECTED (appartenance, pas geometrie)
            val toMerge = mutableListOf<Int>()
            for (gi in result.indices) {
                if (result[gi].any { it in selIndices }) {
                    toMerge.add(gi)
                }
            }
            if (toMerge.size > 1) {
                val merged = toMerge.flatMap { result[it] }.distinct().toMutableList()
                for (gi in toMerge.sortedDescending()) { result.removeAt(gi) }
                result.add(toMerge.first(), merged)
                Log.d(TAG, "Absorption SELECTED (stroke membership): ${toMerge.size} groupes fusionnes")
            }
        }
        return result
    }

    /** Trouve le groupe spatial contenant le stroke (cache unifié). */
    private fun findWordGroup(strokeIndex: Int): List<Int>? {
        return getSpatialGroups().find { strokeIndex in it }
    }

    /**
     * le fichier VStar (tokens ps=4 separateurs).
     */
    /** Point d'accès pour la sauvegarde : groupes depuis GroupManager. */
    fun computeWordGroupsForSave(): List<List<Int>> {
        return getSpatialGroups()  // ordre des seedGroups (JSON) préservé
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
        val words = computeWordGroupsForSave()
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
            throttledInvalidate()
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
        throttledInvalidate()
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
     * 🔦 PHARE + BLOB — affichés ensemble pour le groupe SELECTED.
     * Chaque point du groupe est le centre de son ellipse (rx, ry).
     * L'union de toutes ces ellipses = le blob du groupe.
     * rx, ry = valeurs DIRECTES de la calibration. Aucune déformation image.
     */
    private fun drawActiveGroupBlob(canvas: Canvas) {
        if (currentMode != CaptureMode.CAPTURE) return
        if (!isBlocnoteMode) return

        // Blob UNIQUEMENT sur le groupe SELECTED
        val selectedIndices = groupManager.groupsInState(GroupState.SELECTED)
            .flatMap { it.strokeIds.mapNotNull { id -> inkStrokeIdToRegistryIndex[id] } }.toSet()
        if (selectedIndices.isEmpty()) return
        val groups = getSpatialGroups()
        if (groups.isEmpty()) return
        val groupsToDraw = groups.filter { it.any { idx -> idx in selectedIndices } }
        if (groupsToDraw.isEmpty()) return

        // Rayons DIRECTS — chaque point du groupe est le centre de son ellipse (rx, ry)
        blobRadiusX = CalibrationActivity.getSpatialDistanceX(context)
        blobRadiusY = CalibrationActivity.getSpatialDistanceY(context)
        val rx = blobRadiusX; val ry = blobRadiusY
        val sampleStep = ((rx + ry) / 10f).toInt().coerceIn(1, 6)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Forcer alpha=255 — pas de transparence, pas d'accumulation par superposition
            color = CalibrationActivity.getBlobColor(context) or 0xFF000000.toInt()
        }

        for (groupIndices in groupsToDraw) {
            for (idx in groupIndices) {
                if (idx >= strokeRegistry.size) continue
                val s = strokeRegistry[idx]
                var pi = 0
                for ((x, y) in s.points) {
                    if (pi % sampleStep == 0) {
                        // Chaque point est le centre de son ellipse (rx, ry)
                        // L'union de toutes ces ellipses = le blob du groupe
                        canvas.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
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

        // PROTECTION PERFORMANCE : ne jamais recalculer dans onDraw().
        // GroupManager gère les groupes — on vérifie juste que le groupe existe
        val allGroups = groupManager.allGroups().map { group ->
            group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
        }.filter { it.isNotEmpty() }
        val groupIndex = allGroups.indexOfFirst { it == group }
        if (groupIndex < 0) return

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
     * Symbole d'activation statique — tiret horizontal à gauche du groupe actif.
     * Remplace le clignotement (qui forçait un invalidate() toutes les 500ms).
     * Placé à 10px à gauche du premier stroke, aligné sur le snapY.
     */
    /**
     * 🔦 PHARE — point noir sur l'interligne 10px devant le groupe ouvert.
     * Priorité : groupe SELECTED (survol long), puis groupe LOADED (actif).
     * Le phare suit la création : quand un nouveau groupe est créé,
     * l'ancien est désélectionné et le phare passe au nouveau groupe LOADED.
     * Statique (pas de clignotement) pour éviter le refresh e-ink.
     */
    private fun drawActiveGroupCursor(canvas: Canvas) {
        if (currentMode != CaptureMode.CAPTURE) return
        if (!isBlocnoteMode) return

        // ═══ Phare uniquement pour le groupe SELECTED (survol long) ═══
        val phareGroup = groupManager.groupsInState(GroupState.SELECTED).firstOrNull()
        if (phareGroup == null) return

        val indices = phareGroup.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
        if (indices.isEmpty()) {
            Log.d(TAG, "Phare — groupe ${phareGroup.id} (${phareGroup.state}) a ${phareGroup.strokeIds.size} strokeIds mais 0 indices (map=${inkStrokeIdToRegistryIndex.size})")
            return
        }

        // ═══ Trouver le X le plus à gauche du groupe ENTIER ═══
        var minX = Float.MAX_VALUE
        var sumY = 0f; var count = 0
        for (si in indices) {
            if (si >= strokeRegistry.size) continue
            val stroke = strokeRegistry[si]
            for ((x, y) in stroke.points) {
                if (x < minX) minX = x
                sumY += y; count++
            }
        }
        if (count == 0) return

        // Position : 10px à gauche du bord gauche du groupe
        val phareX = minX - 10f
        // Snap à l'interligne (baseline)
        val avgY = sumY / count
        val phareY = snapToLine(avgY)

        Log.d(TAG, "Phare @ (${phareX.toInt()}, ${phareY.toInt()}) — groupe ${phareGroup.id} ${phareGroup.state} | ${indices.size} strokes, minX=${minX.toInt()}, indices=[${indices.sorted().joinToString(",")}]")

        // Point noir statique (rayon 6px, discret sur e-ink)
        canvas.drawCircle(phareX, phareY, 6f, cursorPaint)
    }

    /** Le phare est statique — pas d'animation nécessaire (e-ink friendly). */
    private fun startCursorAnimation() {
        // Phare statique : pas d'animation.
    }

    private fun stopCursorAnimation() {
        // Plus rien à arrêter.
    }

    /**
     * Indicateur visuel du mode actif — coin haut-droit.
     * CAPTURE: bateau (coque + mat + voile)
     * EDIT_SPATIAL: phare (tour + toit + lumiere)
     * EDIT_TEMPORAL: montre (cadran + aiguilles)
     */
    private var modeIndicatorLogged = false
    private var debugLabelsLogged = false  // log one-shot pour drawGroupDebugInfo
    private fun drawModeIndicator(canvas: Canvas) {
        if (!modeIndicatorLogged) {
            Log.i(TAG, "drawModeIndicator: width=$width height=$height mode=$currentMode temporal=$temporalMode")
            modeIndicatorLogged = true
        }
        // Position sous la barre d'outils (~380px du haut — descendu de 200px)
        val margin = 52f
        val cx = width - margin
        val cy = 380f
        // Taille doublée pour visibilité
        val logoScale = 2f
        val p = modeIndicatorPaint
        val pf = modeIndicatorFill

        when {
            currentMode == CaptureMode.EDIT_TEMPORAL -> {
                // MONTRE — cadran circulaire + 2 aiguilles (×logoScale)
                val r = 18f * logoScale
                p.strokeWidth = 3f * logoScale
                canvas.drawCircle(cx, cy, r, p)
                canvas.drawLine(cx, cy, cx - r * 0.4f, cy - r * 0.55f, p)
                canvas.drawLine(cx, cy, cx + r * 0.55f, cy - r * 0.6f, p)
                canvas.drawCircle(cx, cy, 4f * logoScale, pf)
                p.strokeWidth = 2.5f * logoScale
            }
            currentMode == CaptureMode.EDIT -> {
                // PHARE — tour + toit + lumiere (×logoScale)
                val bw = 12f * logoScale; val bh = 22f * logoScale
                val tx = cx - bw; val ty = cy - bh / 2
                canvas.drawRect(tx, ty + 5f * logoScale, cx + bw, cy + bh / 2, p)
                val roofPath = Path()
                roofPath.moveTo(tx - 4f * logoScale, ty + 5f * logoScale)
                roofPath.lineTo(cx, ty - 8f * logoScale)
                roofPath.lineTo(cx + bw + 4f * logoScale, ty + 5f * logoScale)
                roofPath.close()
                canvas.drawPath(roofPath, pf)
                canvas.drawPath(roofPath, p)
                canvas.drawCircle(cx, ty - 3f * logoScale, 5f * logoScale, pf)
                canvas.drawCircle(cx, ty - 3f * logoScale, 5f * logoScale, p)
            }
            else -> {
                // BATEAU — coque + mat + voile (×logoScale)
                val boatY = cy + 10f * logoScale
                val hullPath = Path()
                hullPath.moveTo(cx - 20f * logoScale, boatY)
                hullPath.quadTo(cx - 12f * logoScale, boatY + 9f * logoScale, cx, boatY + 10f * logoScale)
                hullPath.quadTo(cx + 12f * logoScale, boatY + 9f * logoScale, cx + 20f * logoScale, boatY)
                canvas.drawPath(hullPath, p)
                canvas.drawLine(cx, boatY - 24f * logoScale, cx, boatY, p)
                val sailPath = Path()
                sailPath.moveTo(cx + 2f * logoScale, boatY - 22f * logoScale)
                sailPath.lineTo(cx + 17f * logoScale, boatY - 3f * logoScale)
                sailPath.lineTo(cx + 2f * logoScale, boatY - 3f * logoScale)
                sailPath.close()
                canvas.drawPath(sailPath, pf)
                canvas.drawPath(sailPath, p)
            }
        }
    }

    private fun drawGroupDebugInfo(canvas: Canvas) {
        val groups = getSpatialGroups()
        for ((gi, groupIndices) in groups.withIndex()) {
            if (groupIndices.isEmpty()) continue
            // Calculer le centre Y du groupe, puis le snap à l'interligne
            var sumY = 0f; var count = 0
            for (si in groupIndices) {
                if (si >= strokeRegistry.size) continue
                val s = strokeRegistry[si]
                for (k in 0 until s.activePoints) {
                    sumY += s.points[k].second; count++
                }
            }
            if (count == 0) continue
            val avgY = sumY / count
            val lineY = snapToLine(avgY)
            val labelY = lineY + 28f  // sous l'interligne
            // Position X : bord gauche du groupe
            val bounds = getSpatialBounds()[gi]
            val labelX = bounds.left

            // État SELECTED ?
            val selectedIndices = groupManager.groupsInState(GroupState.SELECTED)
                .flatMap { it.strokeIds.mapNotNull { id -> inkStrokeIdToRegistryIndex[id] } }.toSet()
            val isSelected = groupIndices.any { it in selectedIndices }
            val state = if (isSelected) "★" else "G$gi"
            val firstIdx = groupIndices.first()
            val transcription = groupTranscriptions[firstIdx]
            val infCount = groupInferenceCount[firstIdx]
            val countSuffix = if (infCount != null && infCount > 1) " #$infCount" else ""
            val label = "*$state-${groupIndices.size}S${transcription?.let { " $it" } ?: ""}$countSuffix"
            // Log : position + transcription (8 premiers groupes, une seule fois)
            if (gi < 8 && !debugLabelsLogged) {
                Log.i(TAG, "🎯 label G$gi: @(${labelX.toInt()},${labelY.toInt()}) firstIdx=$firstIdx tx=\"${transcription ?: "?"}\"")
            }
            canvas.drawText(label, labelX, labelY, debugTextPaint)
        }
        if (!debugLabelsLogged) {
            debugLabelsLogged = true
            Log.i(TAG, "🎯 drawGroupDebugInfo: ${groups.size} groupes affichés")
        }
        // Timecode dernière inférence
        if (lastInferenceTime > 0) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(lastInferenceTime))
            canvas.drawText("Dernière inférence: $ts", 20f, height - 20f, debugTextPaint)
        }
    }

    fun clear() {
        stopCursorAnimation()
        closeRawWriter()
        closeDebugLog()
        // V4 : fermer le conduit
        vstarWriter?.writeEnd()
        vstarWriter?.close()
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        currentPath.clear()
        strokeRegistry.clear()
        drawingStroke = null
        selectedStrokeIndex = null
        dragStrokeIndex = null
        selectedWordGroup = null
        hoverWordGroup = null
        dragWordGroup = null
        strokeCount = 0
        pointSeq = 0
        // reactivatedGroupIndex + activeStrokeBase supprimés — GroupManager gère
        // ═══ Nettoyer les maps GroupManager (sinon accumulation → lag) ═══
        inkStrokeIdToRegistryIndex.clear()
        registryIndexToInkStrokeId.clear()
        // ═══ seedGroups : retour au mode écriture live ═══
        seedGroups = null
        inferredGroups.clear()
        groupTranscriptions.clear()
        // Nettoyer les timers par groupe
        groupTimers.values.forEach { it.cancel(false) }
        groupTimers.clear()
        groupInferenceCount.clear()
        lastInferenceTime = 0
        invalidateSpatialCache()
        pointInStroke = 0
        hasPrevPoint = false
        insertPending = false
        temporalEraseAvailable = false  // reset effacement temporel
        currentMode = CaptureMode.EDIT
        scrubTimelinePos = 0f
        scrubGroupIndices = null
        isWritingInEdit = false
        modeIndicatorLogged = false
        debugLabelsLogged = false
        throttledInvalidate()
    }
}