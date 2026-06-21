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
    private val inferExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
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
    // ── Dessin ────────────────────────────────────────────────────────
    private var imeView: CaptureSurfaceView? = null

    // ── Barre d'outils ─────────────────────────────────────────────────
    private var showOverlays = true  // 👁 toggle

    // ── Survol → Appui long (sélection par contact maintenu) ──────────
    private var selectedGroupId: String? = null
    private var longPressTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private var longPressArmed = false
    private var longPressX = 0f
    private var longPressY = 0f

    /** Déclenche la sélection par appui long (appelé par le timer). */
    private fun fireLongPress() {
        longPressArmed = false
        val gm = groupManager ?: return
        val groups = getSpatialGroups()
        val bounds = getSpatialBounds()
        val target = groups.withIndex().firstOrNull { (gi, group) ->
            val r = bounds[gi]
            val groupLine = snapToLine((r.top + r.bottom) / 2f)
            r.left < Float.MAX_VALUE && longPressX >= r.left && longPressX <= r.right 
                && Math.abs(longPressY - groupLine) < 50f
        } ?: return
        val firstStroke = target.value.firstOrNull() ?: return
        val anyStrokeId = inkStrokeIdToRegistryIndex.entries
            .firstOrNull { it.value == firstStroke }?.key ?: return
        val g = gm.reactivateGroup(anyStrokeId) ?: return
        selectedGroupId?.let { gm.deselectGroup(it) }
        if (gm.selectGroup(g.id)) {
            selectedGroupId = g.id
            updateBlobCache()
            val bnds = cachedBlobBounds
            if (bnds != null) {
                val pad = 10
                refreshRect(bnds.left.toInt()-pad, bnds.top.toInt()-pad, bnds.right.toInt()+pad, bnds.bottom.toInt()+pad)
            } else refreshAll()
            Log.i(TAG, "Appui long — groupe ${g.id} SELECTED")
        }
    }

    /** Arme le timer d'appui long. */
    private fun armLongPress(x: Float, y: Float) {
        cancelLongPress()
        longPressX = x; longPressY = y
        longPressArmed = true
        val delay = CalibrationActivity.getLongHoverDelay(this)
        longPressTimer = inferExecutor.schedule({ fireLongPress() }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun cancelLongPress() {
        longPressArmed = false
        longPressTimer?.cancel(false)
        longPressTimer = null
    }

    // ── Cache spatial (comme CaptureView) ──────────────────────────────
    private var cachedSpatialGroups: List<List<Int>>? = null
    private var cachedSpatialBounds: List<android.graphics.RectF>? = null
    private var cachedGMCacheSize: Int = -1

    // ── Pages ─────────────────────────────────────────────────────────
    private var currentPageIndex = 0
    private val pagesDir by lazy { java.io.File(cacheDir, "ime-pages").also { it.mkdirs() } }

    /** Sauvegarde la page active et en crée une nouvelle. */
    private fun newPage() {
        savePage()
        currentPageIndex++
        clearPage()
        Log.i(TAG, "Nouvelle page: $currentPageIndex")
    }

    /** Efface la page active (sans sauvegarde). */
    private fun clearPage() {
        strokeRegistry.clear()
        groupLabels.clear()
        inkStrokeIdToRegistryIndex.clear()
        inkStrokeIdCounter = 0L
        groupManager?.clearAll()
        clearCanvas()
        inferredGroupFirstIdxs.clear()
        groupStrokeCountAtInference.clear()
        groupLastModifiedMs.clear()
        groupTimers.values.forEach { it.cancel(false) }
        groupTimers.clear()
        timerArmedAt.clear()
        timerArmedStrokeCount.clear()
        cachedGMCacheSize = -1
        cachedBlobPath.reset()
        cachedBlobBounds = null
        cachedSpatialGroups = null
        cachedSpatialBounds = null
    }

    /** Sauvegarde la page active sur disque (bitmap + strokes + labels). */
    private fun savePage() {
        try {
            val dir = java.io.File(pagesDir, "page_$currentPageIndex")
            dir.mkdirs()
            // Bitmap
            bitmap?.let {
                java.io.FileOutputStream(java.io.File(dir, "bitmap.png")).use { out ->
                    it.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
            }
            // Strokes + labels (JSON simple)
            val json = org.json.JSONObject()
            json.put("inkIdCounter", inkStrokeIdCounter)
            // strokeRegistry : liste de [points, timestamps, pressures]
            val strokesArr = org.json.JSONArray()
            for (sr in strokeRegistry) {
                val obj = org.json.JSONObject()
                obj.put("id", sr.id)
                val ptsArr = org.json.JSONArray()
                for ((x, y) in sr.points) {
                    val pt = org.json.JSONArray(); pt.put(x.toDouble()); pt.put(y.toDouble()); ptsArr.put(pt)
                }
                obj.put("points", ptsArr)
                strokesArr.put(obj)
            }
            json.put("strokes", strokesArr)
            // Labels: firstIdx → text
            val labelsObj = org.json.JSONObject()
            for ((k, v) in groupLabels) labelsObj.put(k.toString(), v)
            json.put("labels", labelsObj)
            java.io.FileWriter(java.io.File(dir, "state.json")).use { it.write(json.toString()) }
            // Copier la persistance GroupManager
            val gmFile = java.io.File(cacheDir, "ime-groups/current.groups")
            if (gmFile.exists()) {
                gmFile.copyTo(java.io.File(dir, "groups.json"), overwrite = true)
            }
            Log.d(TAG, "Page $currentPageIndex sauvegardée: ${strokeRegistry.size} strokes")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur sauvegarde page: ${e.message}")
        }
    }

    /** Charge une page depuis le disque. */
    private fun loadPage(index: Int): Boolean {
        try {
            val dir = java.io.File(pagesDir, "page_$index")
            if (!dir.exists()) return false
            // Bitmap
            val bmpFile = java.io.File(dir, "bitmap.png")
            if (bmpFile.exists()) {
                val loaded = android.graphics.BitmapFactory.decodeFile(bmpFile.absolutePath)
                if (loaded != null) {
                    bitmap?.recycle()
                    bitmap = loaded.copy(Bitmap.Config.ARGB_8888, true)
                    bitmapCanvas = Canvas(bitmap!!)
                }
            }
            // State JSON
            val stateFile = java.io.File(dir, "state.json")
            if (stateFile.exists()) {
                val json = org.json.JSONObject(stateFile.readText())
                inkStrokeIdCounter = json.optLong("inkIdCounter", 0L)
                // Strokes
                val strokesArr = json.optJSONArray("strokes")
                strokeRegistry.clear()
                if (strokesArr != null) {
                    for (i in 0 until strokesArr.length()) {
                        val obj = strokesArr.optJSONObject(i) ?: continue
                        val sr = StrokeRecord(id = obj.optString("id", ""))
                        val ptsArr = obj.optJSONArray("points")
                        if (ptsArr != null) {
                            for (j in 0 until ptsArr.length()) {
                                val pt = ptsArr.optJSONArray(j) ?: continue
                                sr.points.add(Pair(pt.optDouble(0).toFloat(), pt.optDouble(1).toFloat()))
                            }
                        }
                        if (sr.points.isNotEmpty()) strokeRegistry.add(sr)
                    }
                }
                // Labels
                val labelsObj = json.optJSONObject("labels")
                groupLabels.clear()
                if (labelsObj != null) {
                    for (key in labelsObj.keys()) {
                        groupLabels[key.toInt()] = labelsObj.optString(key, "")
                    }
                }
            }
            // GroupManager: charger depuis persistance spécifique à la page
            val pageGroupsFile = java.io.File(dir, "groups.json")
            if (pageGroupsFile.exists()) {
                groupManager?.persistence = GroupPersistence(pageGroupsFile)
                cachedGMCacheSize = -1
            }
            currentPageIndex = index
            rebuildBitmap()
            Log.i(TAG, "Page $index chargée: ${strokeRegistry.size} strokes, ${groupLabels.size} labels")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement page $index: ${e.message}")
            return false
        }
    }

    // ── Blob ───────────────────────────────────────────────────────────
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE  // contour seul — comme un booléen
        strokeWidth = 2.5f
    }

    // ── Cache performance ──────────────────────────────────────────────
    // Blob: enveloppe convexe du groupe, expansée (plus léger que N ovales)
    private var cachedBlobGroupId: String? = null
    private val cachedBlobPath = Path()  // chemin de l'enveloppe
    private var cachedBlobBounds: android.graphics.RectF? = null  // pour refreshRect
    private var cachedBlobRx = 0f
    private var cachedBlobRy = 0f
    private var cachedTemplateLines: List<Float> = emptyList()
    private var cachedTemplateHeight: Int = -1
    private val cachedLabelPositions = mutableMapOf<Int, Pair<Float, Float>>()

    // ═══ Rafraîchissement EPD ciblé (remplace throttledInvalidate global) ═══

    /** Rafraîchit une zone précise (EVITE le redraw complet). */
    private fun refreshRect(left: Int, top: Int, right: Int, bottom: Int) {
        imeView?.apply {
            invalidate(android.graphics.Rect(left, top, right, bottom))
            try { EpdController.handwritingRepaint(this, left, top, right, bottom) } catch (_: Exception) {}
        }
    }

    /** Rafraîchit toute la surface (UNIQUEMENT pour changements globaux). */
    private fun refreshAll() {
        imeView?.apply {
            // Rafraîchir d'abord en DU (rapide) puis planifier un GU pour nettoyer
            invalidate()
            try { EpdController.handwritingRepaint(this, 0, 0, width, height) } catch (_: Exception) {}
        }
    }

    // ── Template (partition) ───────────────────────────────────────────
    // L'espacement est calculé dynamiquement selon la hauteur du canvas
    // pour garantir ~4-6 lignes visibles quelle que soit la densité d'écran.
    private var template: Template = Template.HorizontalStaff(spacingPx = 120f) // sera recalculé

    /** Recalcule l'espacement du template selon la hauteur réelle du canvas. */
    private fun updateTemplateSpacing(canvasHeight: Int) {
        if (canvasHeight <= 0) return
        // Lire les paramètres depuis la calibration
        val spacing = CalibrationActivity.getTemplateSpacing(this@MiroirIME)
        val sw = CalibrationActivity.getTemplateStrokeWidth(this@MiroirIME)
        template = Template.HorizontalStaff(spacingPx = spacing)
        Template.GUIDE_PAINT.strokeWidth = sw
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
            savePage()
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
            refreshAll()
        })

        toolbar.addView(makeButton("+") {
            newPage()
            refreshAll()
        })

        toolbar.addView(makeButton("✕") {
            clearPage()
            refreshAll()
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
        refreshAll()
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
                // Blob: enveloppe convexe (chemin pré-calculé)
                if (!cachedBlobPath.isEmpty) {
                    canvas.drawPath(cachedBlobPath, blobPaint)
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
                MotionEvent.ACTION_HOVER_MOVE -> { /* ignoré — IME ne reçoit pas ces événements */ }
                MotionEvent.ACTION_DOWN -> {
                    // Armer l'appui long (sera annulé si l'utilisateur bouge)
                    armLongPress(event.x, event.y)
                    onStylusDown(event.x, event.y)
                }
                MotionEvent.ACTION_MOVE -> {
                    // Si l'utilisateur bouge significativement → écriture, pas sélection
                    if (longPressArmed) {
                        val dx = event.x - longPressX; val dy = event.y - longPressY
                        if (dx*dx + dy*dy > 100f) cancelLongPress()  // >10px
                    }
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        onStylusPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i))
                    }
                    onStylusPoint(event.x, event.y, event.pressure)
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    onStylusUp()
                }
            }
            return true
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SÉLECTION — appui long (remplace le survol, inopérant dans l'IME)
    // ═══════════════════════════════════════════════════════════════════

    // (fireLongPress, armLongPress, cancelLongPress définis plus haut)

    /** Cale Y sur la ligne de portée la plus proche — 70% au-dessus, 30% en dessous. */
    private fun snapToLine(y: Float): Float {
        if (cachedTemplateLines.isEmpty()) return y
        // Trouver les deux lignes qui encadrent le point
        var upper = cachedTemplateLines.first()
        var lower = cachedTemplateLines.last()
        for (line in cachedTemplateLines) {
            if (line <= y && line > upper) upper = line
            if (line >= y && line < lower) lower = line
        }
        if (upper == lower) return upper
        // Frontière à 70% depuis la ligne du haut
        val spacing = lower - upper
        val boundary = upper + spacing * 0.7f
        return if (y <= boundary) upper else lower
    }

    /** Groupes spatiaux depuis GroupManager (source unique), avec cache. */
    private fun getSpatialGroups(): List<List<Int>> {
        val gm = groupManager ?: return emptyList()
        val fullSize = gm.allGroupsFull().size
        if (cachedGMCacheSize != fullSize) {
            val full = gm.allGroupsFull()
            cachedSpatialGroups = full.mapNotNull { group ->
                group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }.ifEmpty { null }
            }
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

    private fun getSpatialBounds(): List<android.graphics.RectF> {
        getSpatialGroups()
        return cachedSpatialBounds ?: emptyList()
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

    private var lastPointRefresh = 0L

    private fun onStylusDown(x: Float, y: Float) {
        currentPath.reset()
        currentPath.moveTo(x, y)
        currentStroke = StrokeRecord(
            id = UUID.randomUUID().toString()
        ).also { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(1.0f)
        }
    }

    private fun onStylusPoint(x: Float, y: Float, pressure: Float) {
        currentPath.lineTo(x, y)
        currentStroke?.let { stroke ->
            stroke.points.add(Pair(x, y))
            stroke.timestamps.add(System.currentTimeMillis())
            stroke.pressures.add(pressure.coerceIn(0f, 1f))
        }
        // Rafraîchir pendant le glissé (fréquence paramétrable via calibration)
        val interval = CalibrationActivity.getRefreshInterval(this@MiroirIME)
        val now = System.currentTimeMillis()
        if (now - lastPointRefresh >= interval) {
            lastPointRefresh = now
            val r = 10
            refreshRect(x.toInt() - r, y.toInt() - r, x.toInt() + r, y.toInt() + r)
        }
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

        // Armer le timer d'inférence pour les groupes modifiés
        scheduleGroupInference()

        // ═══ Rafraîchir uniquement la zone du stroke ═══
        if (stroke.points.size >= 2) {
            var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
            var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
            for ((x, y) in stroke.points) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            refreshRect(
                (minX - 10).toInt(), (minY - 10).toInt(),
                (maxX + 10).toInt(), (maxY + 10).toInt())
        }
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
    // INFÉRENCE PAR GROUPE — timers indépendants (comme Miroir V4)
    // ═══════════════════════════════════════════════════════════════════

    /** File d'attente FIFO de groupes à reconnaître. */
    private val inferenceQueue = java.util.concurrent.ConcurrentLinkedQueue<List<Int>>()
    private var isInferring = false

    // ═══ Timers par groupe (comme CaptureView) ═══
    private val groupTimers = mutableMapOf<Int, java.util.concurrent.ScheduledFuture<*>>()
    private val groupStrokeCountAtInference = mutableMapOf<Int, Int>()
    private val groupLastModifiedMs = mutableMapOf<Int, Long>()
    private val timerArmedAt = mutableMapOf<Int, Long>()  // timestamp d'armement du timer
    private val timerArmedStrokeCount = mutableMapOf<Int, Int>()  // strokes à l'armement
    private val inferredGroupFirstIdxs = mutableSetOf<Int>()

    /** Annule tous les timers d'inférence en attente. */
    private fun cancelAllGroupTimers() {
        for ((_, timer) in groupTimers) {
            timer.cancel(false)
        }
        groupTimers.clear()
    }

    /** Appelé après chaque stroke pour armer le timer du groupe modifié. */
    private fun scheduleGroupInference() {
        val gm = groupManager ?: return
        val inferDelay = CalibrationActivity.getAutoInferDelay(this)
        val now = System.currentTimeMillis()

        val loadedGroups = gm.groupsInState(GroupState.LOADED)
        for (group in loadedGroups) {
            if (group.strokeIds.isEmpty()) continue
            val firstIdx = inkStrokeIdToRegistryIndex[group.strokeIds.first()] ?: continue
            val strokeCount = group.strokeIds.size
            groupLastModifiedMs[firstIdx] = now

            // Déjà inféré mais modifié → permettre la ré-inférence
            val infCount = groupStrokeCountAtInference[firstIdx]
            if (firstIdx in inferredGroupFirstIdxs) {
                if (infCount != null && strokeCount == infCount) continue  // vraiment inchangé
                // Modifié depuis l'inférence → ré-ouvrir pour ré-inférence
                inferredGroupFirstIdxs.remove(firstIdx)
            }

            // ═══ Réarmer : chaque nouveau trait dans le groupe reset le compte à rebours ═══
            // Le timer ne tire qu'après inferDelay d'inactivité DANS CE GROUPE.
            groupTimers.remove(firstIdx)?.cancel(false)
            timerArmedAt[firstIdx] = now
            timerArmedStrokeCount[firstIdx] = strokeCount
            val timer = inferExecutor.schedule({
                armGroupInference(firstIdx)
            }, inferDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            groupTimers[firstIdx] = timer
            Log.d(TAG, "⏱️ Timer firstIdx=$firstIdx → ${inferDelay}ms (${strokeCount}s)")
        }
    }

    /** Appelé par le timer — vérifie que le groupe n'a pas changé depuis l'armement. */
    private fun armGroupInference(firstIdx: Int) {
        // ═══ Garde-fou : groupe modifié depuis l'armement du timer → ignorer ═══
        val armedAt = timerArmedAt[firstIdx] ?: return
        val lastMod = groupLastModifiedMs[firstIdx] ?: return
        if (lastMod > armedAt) {
            Log.d(TAG, "⏭️ Timer ignoré firstIdx=$firstIdx — groupe modifié depuis armement")
            return
        }
        if (firstIdx in inferredGroupFirstIdxs) return

        val gm = groupManager ?: return
        val group = gm.allGroups().find { g ->
            inkStrokeIdToRegistryIndex[g.strokeIds.firstOrNull() ?: return@find false] == firstIdx
        } ?: return
        val indices = group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
        if (indices.isEmpty()) return

        // Vérifier que le nombre de strokes n'a pas changé
        val armedCount = timerArmedStrokeCount[firstIdx]
        if (armedCount != null && group.strokeIds.size != armedCount) {
            Log.d(TAG, "⏭️ Timer ignoré firstIdx=$firstIdx — strokes ${armedCount}→${group.strokeIds.size}")
            return
        }

        inferredGroupFirstIdxs.add(firstIdx)
        groupStrokeCountAtInference[firstIdx] = group.strokeIds.size
        groupTimers.remove(firstIdx)

        inferenceQueue.add(indices)
        cachedGMCacheSize = -1
        Log.i(TAG, "Groupe à inférer: ${group.id} (${indices.size} strokes)")
        startInferencePipeline()
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
                    refreshAll()  // le label change → besoin du redraw complet (template+labels)
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
     * Calcule la vraie frontière du blob par ray casting.
     * Pour chaque angle, trouve l'intersection la plus lointaine avec toutes les ellipses.
     */
    private fun updateBlobCache() {
        cachedBlobPath.reset()
        cachedBlobBounds = null
        val gm = groupManager ?: return
        val selectedGroups = gm.groupsInState(GroupState.SELECTED)
        val group = if (selectedGroups.isNotEmpty()) selectedGroups.first()
                     else gm.allGroups().lastOrNull() ?: return
        computeBlobPath(group)
    }

    /** Calcule le chemin blob pour un groupe (peut être appelé pour n'importe quel groupe). */
    private fun computeBlobPath(group: InkGroup): Path? {
        val gm = groupManager ?: return null
        val rx = gm.params.spatialDistancePx
        val ry = gm.params.spatialDistanceY
        if (rx <= 0f && ry <= 0f) return null
        if (group.strokeIds.isEmpty()) return null

        // Collecter les points
        val pts = mutableListOf<Pair<Float, Float>>()
        for (sid in group.strokeIds) {
            val idx = inkStrokeIdToRegistryIndex[sid] ?: continue
            val sr = strokeRegistry.getOrNull(idx) ?: continue
            for ((x, y) in sr.points) pts.add(Pair(x, y))
        }
        if (pts.size < 2) return null

        // Centroïd
        var cx = 0f; var cy = 0f
        for ((px, py) in pts) { cx += px; cy += py }
        cx /= pts.size; cy /= pts.size

        // Ray casting : N rayons, intersection la plus lointaine
        val rayCount = CalibrationActivity.getBlobRayCount(this@MiroirIME)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        val path = Path()
        var first = true

        for (i in 0 until rayCount) {
            val angle = 2.0 * Math.PI * i / rayCount
            val dx = Math.cos(angle).toFloat()
            val dy = Math.sin(angle).toFloat()

            // Intersection maximale parmi toutes les ellipses
            var bestT = 0f
            for ((px, py) in pts) {
                val ox = cx - px; val oy = cy - py
                val a = (dx*dx)/(rx*rx) + (dy*dy)/(ry*ry)
                val b = dx*ox/(rx*rx) + dy*oy/(ry*ry)
                val c = (ox*ox)/(rx*rx) + (oy*oy)/(ry*ry) - 1f
                val disc = b*b - a*c
                if (disc <= 0f) continue
                val t = (-b + Math.sqrt(disc.toDouble()).toFloat()) / a
                if (t > bestT) bestT = t
            }
            if (bestT <= 0f) continue

            val bx = cx + bestT * dx
            val by = cy + bestT * dy
            if (first) { path.moveTo(bx, by); first = false }
            else path.lineTo(bx, by)
            if (bx < minX) minX = bx; if (bx > maxX) maxX = bx
            if (by < minY) minY = by; if (by > maxY) maxY = by
        }
        if (first) return null  // aucun point
        path.close()

        // Stocker dans le cache actif
        cachedBlobPath.set(path)
        cachedBlobBounds = android.graphics.RectF(minX, minY, maxX, maxY)
        return path
    }

    private fun clearCanvas() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        currentPath.reset()
        currentStroke = null
        refreshAll()
    }

    /** Dessine les labels de groupe à leur position d'interligne */
    private fun drawGroupLabels(canvas: Canvas) {
        if (groupLabels.isEmpty()) return
        val groups = getSpatialGroups()
        val bounds = getSpatialBounds()
        // Index: firstIdx → position dans groups (évite la recherche linéaire)
        val groupIndexByFirst = mutableMapOf<Int, Int>()
        for ((gi, g) in groups.withIndex()) {
            g.firstOrNull()?.let { groupIndexByFirst[it] = gi }
        }
        for ((firstIdx, label) in groupLabels) {
            val gi = groupIndexByFirst[firstIdx] ?: continue
            if (gi >= bounds.size) continue
            val r = bounds[gi]
            if (r.left >= Float.MAX_VALUE) continue
            val lineY = snapToLine((r.top + r.bottom) / 2f)
            val y = lineY + labelPaint.textSize + 4f
            canvas.drawText(label, r.left, y, labelPaint)
        }
    }
}
