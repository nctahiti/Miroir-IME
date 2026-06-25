package com.parnasse.miroir

/**
 * Miroir IME — portail d'écriture manuscrite universel.
 *
 * Architecture : voir ARCHITECTURE.md
 *
 * Points d'entrée pour le lecteur :
 *   1. onCreateInputView() — initialisation (TouchHelper, GroupManager, ML Kit)
 *   2. onTouchEvent()       — boucle d'événements stylet
 *   3. onStylusUp()         — fin d'un trait : rastérisation, groupement, timer
 *   4. scheduleGroupInference() — timers par groupe
 *   5. recognizeGroup()     — inférence ML Kit → label + blob + injection
 *   6. injectReadingOrder() — tri spatial → texte dans le champ cible
 *   7. onDraw()             — rendu : bitmap, template, labels, blob
 *
 * Principes :
 *   - Blob = zone d'absorption (visuel). Label = témoin d'inférence.
 *   - Sélection purement visuelle (activeBlobGroupId), pas selectGroup().
 *   - Chaque groupe a son timer indépendant.
 *   - Ordre de lecture = tri spatial (interligne, x). Poésie binaire.
 *   - Pas d'éviction (transcriptionTimeoutMs = Long.MAX_VALUE).
 *   - EPD : refreshRect (pixellaire), pas invalidate() global.
 */

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

    private var correctionGroupFirstIdx: Int = -1  // firstIdx du groupe en cours de correction
    private var correctionOriginalLabel: String = ""  // label avant correction (pour la paire)
    private var correctLetterIndex: Int = -1  // index de la lettre ciblée
    private var correctionSavedGroup: InkGroup? = null  // groupe original sauvegardé (ré-enregistré après correction)
    private val correctionPaths = mutableListOf<android.graphics.Path>()  // paths des strokes de correction (dessin uniquement)
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
        color = Color.BLACK  // noir pur pour mode DU
        textSize = 42f
        isAntiAlias = false  // mode DU : pas de gris
        typeface = Typeface.DEFAULT_BOLD
    }
    // ── Dessin ────────────────────────────────────────────────────────
    private var imeView: CaptureSurfaceView? = null

     /** Séquenceur de modes EPD (État A) — initialisé quand la surface IME est créée. */
     private var displayController: DisplayController? = null

     /** Témoin de mode dans la barre d'outils (✍ plume · ⌛ montre · ↕ déplacement). */
     private var modeIndicator: android.widget.TextView? = null

     /** Panneau overlay pour menus contextuels (affiche par-dessus la surface de capture). */
     private var overlayPanel: android.widget.LinearLayout? = null

     /** Index des strokes neutralisés visuellement pendant le scrub. Accessible depuis le rendu et les gestes. */
     private var erasedStrokes = mutableSetOf<Int>()

    // ── Barre d'outils ─────────────────────────────────────────────────
    private var showOverlays = true  // 👁 toggle
    private var toolbarHeightPx = 120f  // estimé, sera mesuré après layout

    // ── Blob par groupe inféré ─────────────────────────────────────────
    // Chaque groupe inféré a son blob (chemin + bounds), calculé UNE fois.
    private val groupBlobs = mutableMapOf<String, BlobData>()
    private var activeBlobGroupId: String? = null  // groupe en absorption active

    data class BlobData(val path: Path, val bounds: android.graphics.RectF)

    // ── Rafraîchissement EPD ciblé ──
    private var cachedSpatialGroups: List<List<Int>>? = null
    private var cachedSpatialBounds: List<android.graphics.RectF>? = null
    private var cachedGMCacheSize: Int = -1

    // ── Ancrage des groupes ───────────────────────────────────────────
    // anchor = premier point du premier stroke du groupe.
    // Clé = firstIdx (même que groupLabels), pas groupId.
    private val groupAnchor = mutableMapOf<Int, Pair<Float, Float>>()  // firstIdx → (x, y)

    private var currentPageIndex = 0
    private val pagesDir by lazy { java.io.File(cacheDir, "ime-pages").also { it.mkdirs() } }
    private var pageLabel: android.widget.TextView? = null

    // ── Bloc / Session ────────────────────────────────────────────────
    // Structure : blocks/<appName>_<timestamp>/page_<n>/
    // Le bloc est créé à l'ouverture de l'IME et fermé au ✕.
    private var hostAppName: String = "unknown"
    private var blockTimestamp: Long = 0L
    private var blockDir: java.io.File? = null  // null = pas de bloc actif

    /** Initialise (ou réutilise) le bloc courant pour l'app hôte. */
    private fun ensureBlockDir(appName: String, ts: Long): java.io.File {
        if (blockDir == null || hostAppName != appName) {
            hostAppName = appName
            blockTimestamp = ts
            val safeName = appName.replace(".", "_")
            blockDir = java.io.File(cacheDir, "blocks/${safeName}_$ts").also { it.mkdirs() }
            currentPageIndex = 0
            Log.i(TAG, "Bloc ouvert: ${blockDir!!.name}")
        }
        return blockDir!!
    }

    /** Ferme le bloc courant — sauvegarde la page active et libère. */
    private fun closeBlock() {
        savePage()
        currentPageIndex = 0
        blockDir = null
        hostAppName = "unknown"
        Log.i(TAG, "Bloc fermé — pageIndex remis à 0")
    }

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
        groupAnchor.clear()
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
        inferredGroupFirstIdxs.clear()
        groupBlobs.clear()
        activeBlobGroupId = null
        cachedSpatialBounds = null
        resetSyncedNote()
        // Supprimer le dossier de page fantôme s'il existe
        val bd = blockDir ?: return
        val dir = java.io.File(bd, "page_$currentPageIndex")
        if (dir.exists()) {
            dir.deleteRecursively()
            Log.i(TAG, "clearPage: dossier page $currentPageIndex supprimé")
        }
    }

    /** Sauvegarde la page active sur disque (bitmap + strokes + labels). */
    private fun savePage() {
        val bd = blockDir ?: run {
            Log.w(TAG, "savePage: pas de bloc actif — ignoré")
            return
        }
        // ═══ Ne pas sauvegarder une page vide (protège les pages existantes) ═══
        if (strokeRegistry.isEmpty()) {
            // Supprimer le dossier s'il existe (page fantôme)
            val dir = java.io.File(bd, "page_$currentPageIndex")
            if (dir.exists()) {
                dir.deleteRecursively()
                Log.i(TAG, "savePage: page $currentPageIndex vide — dossier supprimé")
            }
            return
        }
        try {
            val dir = java.io.File(bd, "page_$currentPageIndex")
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
            for ((index, sr) in strokeRegistry.withIndex()) {
                val obj = org.json.JSONObject()
                obj.put("id", sr.id)
                // Sauvegarder l'inkId pour restaurer inkStrokeIdToRegistryIndex
                val inkId = inkStrokeIdToRegistryIndex.entries.find { it.value == index }?.key
                if (inkId != null) obj.put("inkId", inkId)
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
            // Anchors: firstIdx → (x, y)
            val anchorsObj = org.json.JSONObject()
            for ((k, v) in groupAnchor) {
                val arr = org.json.JSONArray()
                arr.put(v.first.toDouble())
                arr.put(v.second.toDouble())
                anchorsObj.put(k.toString(), arr)
            }
            json.put("anchors", anchorsObj)
            java.io.FileWriter(java.io.File(dir, "state.json")).use { it.write(json.toString()) }
            // Persister TOUS les groupes directement (pas de copie de fichier perime)
            val allGroups = groupManager?.allGroups() ?: emptyList()
            if (allGroups.isNotEmpty()) {
                GroupPersistence(java.io.File(dir, "groups.json")).writeAllGroups(allGroups)
            }
            Log.i(TAG, "Page $currentPageIndex sauvegardée: ${strokeRegistry.size} strokes, ${groupLabels.size} labels, ${allGroups.size} groupes")
        } catch (e: Exception) {
            Log.w(TAG, "Erreur sauvegarde page: ${e.message}")
        }
    }

    /** Charge une page depuis le disque. */
    private fun loadPage(index: Int): Boolean {
        val bd = blockDir ?: return false
        try {
            val dir = java.io.File(bd, "page_$index")
            if (!dir.exists()) return false
            // Nettoyer l'etat avant de charger la nouvelle page
            groupManager?.clearAll()
            groupBlobs.clear()
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
                inkStrokeIdToRegistryIndex.clear()
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
                        if (sr.points.isNotEmpty()) {
                            strokeRegistry.add(sr)
                            // Restaurer le mapping inkId -> index (depuis le JSON)
                            val savedInkId = obj.optLong("inkId", -1)
                            if (savedInkId >= 0) {
                                inkStrokeIdToRegistryIndex[savedInkId] = strokeRegistry.size - 1
                            }
                        }
                    }
                }
                // Fallback pour les strokes sans inkId (format ancien) : mapping sequentiel
                for (i in strokeRegistry.indices) {
                    val inkId = (i + 1).toLong()
                    if (!inkStrokeIdToRegistryIndex.containsKey(inkId)) {
                        inkStrokeIdToRegistryIndex[inkId] = i
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
                // Anchors: firstIdx → (x, y)
                val anchorsObj = json.optJSONObject("anchors")
                groupAnchor.clear()
                if (anchorsObj != null) {
                    for (key in anchorsObj.keys()) {
                        val arr = anchorsObj.optJSONArray(key) ?: continue
                        if (arr.length() >= 2) {
                            groupAnchor[key.toInt()] = Pair(
                                arr.optDouble(0).toFloat(),
                                arr.optDouble(1).toFloat()
                            )
                        }
                    }
                }
            }
            // GroupManager: charger depuis persistance specifique a la page
            // ⚠️ NE PAS changer groupManager.persistence — le GM doit toujours
            //    ecrire dans ime-groups/current.groups (fichier canonique).
            //    On lit les groupes avec une persistence temporaire et on les
            //    enregistre en memoire via registerLoadedGroup().
            val pageGroupsFile = java.io.File(dir, "groups.json")
            if (pageGroupsFile.exists()) {
                val tempPersistence = GroupPersistence(pageGroupsFile)
                val groups = tempPersistence.readAllGroups()
                for (group in groups) {
                    groupManager?.registerLoadedGroup(group)
                }
                cachedGMCacheSize = -1
                // Initialiser le compteur de sequence apres chargement
                val maxSeq = groups.mapNotNull { it.orderIndex }.maxOrNull() ?: -1
                groupManager?.initSequenceCounter(maxSeq)
            }
            currentPageIndex = index
            rebuildBitmap()
            // ═══ Recalculer tous les blobs pour les groupes chargés ═══
            groupBlobs.clear()
            groupManager?.allGroups()?.forEach { group ->
                if (group.strokeIds.isNotEmpty()) {
                    computeBlobPath(group)?.let { blob ->
                        groupBlobs[group.id] = blob
                    }
                }
            }
            Log.i(TAG, "Page $index chargée: ${strokeRegistry.size} strokes, ${groupLabels.size} labels, ${groupBlobs.size} blobs")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement page $index: ${e.message}")
            return false
        }
    }

    // ── Blob ───────────────────────────────────────────────────────────
    private val blobPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE  // contour seul — comme un booléen
        strokeWidth = 2.5f
        isAntiAlias = false  // mode DU : pas de gris
    }

    // ── Cache performance ──────────────────────────────────────────────
    private var cachedTemplateLines: List<Float> = emptyList()
    private var cachedTemplateHeight: Int = -1
    private val cachedLabelPositions = mutableMapOf<Int, Pair<Float, Float>>()

    // ═══ Rafraîchissement EPD ciblé (remplace throttledInvalidate global) ═══

    /** Rafraîchit une zone précise (EVITE le redraw complet).
     *  @param isStroke true = tracé stylet (handwritingRepaint), false = overlay (invalidate DU) */
    private fun refreshRect(left: Int, top: Int, right: Int, bottom: Int, isStroke: Boolean = false) {
        val v = imeView
        if (v == null) { Log.w(TAG, "refreshRect: imeView null"); return }
        Log.d(TAG, "refreshRect: ($left,$top)-($right,$bottom) view=${v.width}x${v.height} stroke=$isStroke")
        v.postInvalidate(left, top, right, bottom)
        try {
            if (isStroke) {
                EpdController.handwritingRepaint(v, left, top, right, bottom)
            } else {
                // ═══ refreshScreen(GU) pour les overlays ═══
                // handwritingRepaint ignore drawText. invalidate() est parfois no-op.
                // refreshScreen avec GU fait un rafraîchissement complet ponctuel,
                // sans changer le mode par défaut (DU reste actif pour le tracé).
                EpdController.refreshScreen(v, UpdateMode.GU)
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshRect: EpdController error: ${e.message}")
        }
    }

    /** Rafraîchit toute la surface (UNIQUEMENT pour changements globaux). */
    private fun refreshAll() {
        val v = imeView ?: return
        Log.d(TAG, "refreshAll: view=${v.width}x${v.height}")
        v.postInvalidate()
        try {
            EpdController.refreshScreen(v, UpdateMode.GU)
        } catch (e: Exception) {
            Log.w(TAG, "refreshAll: EpdController error: ${e.message}")
        }
    }

    // ═══ Jonglage de modes EPD ═══
    private var isWriteMode = true  // true = DU (écriture), false = REGAL/GU (vue)

    /** Active le mode écriture (DU) — tracé fluide 16ms, overlays invisibles. */
    private fun enterWriteMode() {
        if (isWriteMode) return  // déjà en DU, ne pas rafraîchir inutilement
        val v = imeView ?: return
        try {
            EpdController.setScreenHandWritingPenState(v, 1)
            EpdController.enablePost(v, 0)
            EpdController.setViewDefaultUpdateMode(v, UpdateMode.DU)
            isWriteMode = true
        } catch (e: Exception) {
            Log.w(TAG, "enterWriteMode: EpdController error: ${e.message}")
        }
    }

    /** Active le mode vue (REGAL) — overlays visibles, texte optimisé ~120ms. */
    private fun enterViewMode() {
        if (!isWriteMode) return  // déjà en vue
        val v = imeView ?: return
        try {
            EpdController.setScreenHandWritingPenState(v, 0)
            EpdController.enablePost(v, 1)
            EpdController.setViewDefaultUpdateMode(v, UpdateMode.REGAL)
            v.postInvalidate()
            isWriteMode = false
        } catch (e: Exception) {
            Log.w(TAG, "enterViewMode: EpdController error: ${e.message}")
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
            // ═══ DÉSACTIVER le timer interne de GroupManager AVANT toute création de groupe ═══
            it.params = it.params.copy(transcriptionTimeoutMs = Long.MAX_VALUE)
            Log.i(TAG, "GroupManager params: timeout=${it.params.transcriptionTimeoutMs} rx=${it.params.spatialDistancePx} ry=${it.params.spatialDistanceY}")
            it.pointProvider = { strokeId ->
                inkStrokeIdToRegistryIndex[strokeId]
                    ?.let { strokeRegistry.getOrNull(it)?.points ?: emptyList() }
                    ?: emptyList()
            }
            val tmpDir = java.io.File(cacheDir, "ime-groups")
            tmpDir.mkdirs()
            it.persistence = GroupPersistence(java.io.File(tmpDir, "current.groups"))
        }
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "onCreateInputView — plein écran")

        val density = resources.displayMetrics.density
        val toolbarHeight = (80 * density).toInt()  // hauteur adaptée aux grands boutons

        // ═══ Conteneur principal (FrameLayout pour superposer l'overlay) ═══
        val root = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
        }

        // ── Contenu principal (toolbar + surface) ─────────────────────
        val mainContent = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(mainContent)

        // ── Panneau overlay (caché par défaut) ────────────────────────
        overlayPanel = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        root.addView(overlayPanel)

        // ── Barre d'outils EN HAUT ──────────────────────────────────
        val toolbar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, toolbarHeight)
            setBackgroundColor(Color.argb(180, 240, 240, 240))
            gravity = android.view.Gravity.CENTER
        }

        fun makeButton(label: String, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): android.widget.Button {
            return android.widget.Button(this).apply {
                text = label
                textSize = 22f  // ×5 par rapport à 12f
                setTextColor(Color.DKGRAY)
                setBackgroundColor(Color.TRANSPARENT)
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener { onClick() }
                if (onLongClick != null) {
                    setOnLongClickListener { onLongClick(); true }
                }
            }
        }

        toolbar.addView(makeButton("✓") {
            savePage()
            val ic = currentInputConnection
            if (ic != null) {
                val fullText = buildAllPagesText()
                ic.commitText(fullText.ifEmpty { "\n" }, 1)
            }
            requestHideSelf(0)
        })

        toolbar.addView(makeButton("⚙") {
            // Ouvrir CalibrationActivity sans cacher l'IME
            val intent = android.content.Intent(this@MiroirIME, CalibrationActivity::class.java)
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        })

        toolbar.addView(makeButton("◀", {
            showBlockList()
        }) {
            if (currentPageIndex > 0) {
                savePage()
                currentPageIndex--
                loadPage(currentPageIndex)
                refreshAll()
                updatePageIndicator()
            }
        })

        // ═══ Indicateur de page (numéro page courante / totale) ═══
        val pageLabel = android.widget.TextView(this).apply {
            text = "1/1"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }.also { this.pageLabel = it }
        toolbar.addView(pageLabel)

        toolbar.addView(makeButton("▶", {
            showAllTranscriptions()
        }) {
            savePage()
            currentPageIndex++
            if (!loadPage(currentPageIndex)) {
                clearPage()  // page inexistante → page vierge
            }
            refreshAll()
            updatePageIndicator()
        })

        toolbar.addView(makeButton("+") {
            newPage()
            refreshAll()
            updatePageIndicator()
        })

        toolbar.addView(makeButton("✕") {
            // ═══ Fermer le bloc (vider le champ + sauvegarder + libérer) ═══
            val ic = currentInputConnection
            if (ic != null) {
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1)
            }
            closeBlock()   // sauvegarde et ferme le bloc
            clearPage()    // nettoie la RAM
            refreshAll()
            requestHideSelf(0)
        })
        // ═══ Témoin de mode (plume/montre/déplacement) ═══
        toolbar.addView(android.widget.TextView(this).apply {
            text = "✍"
            textSize = 22f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            modeIndicator = this
        })

        mainContent.addView(toolbar)  // barre EN HAUT

        // ── Surface de capture (reste de l'écran) ───────────────────
        val surface = CaptureSurfaceView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, 1f)  // weight=1 → prend tout l'espace restant
        }
        imeView = surface
         // Initialisation du séquenceur de modes (État A) — la surface IME est la cible EPD
         displayController = DisplayController(OnyxEpdPort(surface))
        mainContent.addView(surface)

        root.post {
            // Mesurer la toolbar pour les taps stylet
            if (toolbar.height > 0) toolbarHeightPx = toolbar.height.toFloat()
            if (surface.width > 0 && surface.height > 0) {
                bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
                bitmapCanvas = Canvas(bitmap!!)
                bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)  // transparent
                updateTemplateSpacing(surface.height)
                // ═══ Premier affichage — le template est visible en DU (raw drawing déjà ouvert par initTouchHelper) ═══
                surface.postInvalidate()  // forcer le dessin du template
            }
        }

        initTouchHelper(surface)
        return root
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val app = info?.packageName ?: "unknown"
        Log.i(TAG, "onStartInputView — app=$app field=${info?.fieldName ?: "?"} inputType=${info?.inputType ?: 0}")
        // ═══ Ouvrir un bloc pour cette app ═══
        ensureBlockDir(app, System.currentTimeMillis())
        updatePageIndicator()
        // ═══ Forcer la réinitialisation du TouchHelper à chaque ouverture ═══
        touchHelper = null
        isWriteMode = false
        syncGroupManagerParams()
        rebuildBitmap()
        val v = imeView
        if (v != null) initTouchHelper(v)
        rebuildBitmap()
    }

    /** Redessine tous les strokes du registre dans le bitmap. */
    private fun rebuildBitmap() {
        val canvas = bitmapCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for ((idx, sr) in strokeRegistry.withIndex()) {
            if (idx in erasedStrokes) continue  // ═══ stroke neutralisé visuellement ═══
            if (sr.points.size < 2) continue    // point isolé (tap dans le vide)
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
    }

    /** Lit les paramètres de calibration et les applique au GroupManager. */
    private fun syncGroupManagerParams() {
        val gm = groupManager ?: return
        val calX = CalibrationActivity.getSpatialDistanceX(this)
        val calY = CalibrationActivity.getSpatialDistanceY(this)
        gm.params = gm.params.copy(
            spatialDistancePx = calX,
            spatialDistanceY = calY,
            transcriptionTimeoutMs = Long.MAX_VALUE  // NE PAS écraser avec le défaut 2000ms !
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

        private var drawCount = 0
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            drawCount++
            if (drawCount % 30 == 1) Log.d(TAG, "onDraw #$drawCount showOverlays=$showOverlays labels=${groupLabels.size} template=${cachedTemplateLines.size}")
            if (showOverlays) {
                // Blob du groupe actif (sélection visuelle, pas transition GM)
                if (showOverlays) {
                    activeBlobGroupId?.let { gid ->
                        groupBlobs[gid]?.let { canvas.drawPath(it.path, blobPaint) }
                    }
                }
            }
            bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            // ═══ Mode correction : cadre-tampon + filtre ═══
            if (isCorrecting()) {
                val firstIdx = this@MiroirIME.correctionGroupFirstIdx
                val label = groupLabels[firstIdx]
                val anchor = groupAnchor[firstIdx]
                if (label != null && anchor != null && label.isNotEmpty()) {
                    val spacing = CalibrationActivity.getTemplateSpacing(this@MiroirIME)
                    val letterW = spacing * 0.7f
                    val totalW = letterW * label.length
                    val startX = anchor.first - totalW / 2f
                    val startY = snapToLine(anchor.second) - spacing * 0.8f
                    // Cadre blanc = tampon qui efface la zone
                    val bgPaint = android.graphics.Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
                    canvas.drawRect(startX - 20f, startY - 10f, startX + totalW + 20f, startY + letterW + 10f, bgPaint)
                    val borderPaint = android.graphics.Paint().apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 2f }
                    canvas.drawRect(startX - 20f, startY - 10f, startX + totalW + 20f, startY + letterW + 10f, borderPaint)
                    // Grand label (entre cadre et strokes)
                    val letterPaint = android.graphics.Paint().apply { color = Color.DKGRAY; textSize = letterW * 0.8f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
                    val activePaint = android.graphics.Paint().apply { color = Color.BLUE; textSize = letterW * 0.8f; isAntiAlias = true; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
                    for (i in label.indices) {
                        val cx = startX + letterW * i + letterW / 2f
                        val cy = startY + letterW * 0.75f
                        val casePaint = if (i == this@MiroirIME.correctLetterIndex)
                            android.graphics.Paint().apply { color = Color.argb(40, 0, 0, 255); style = Paint.Style.FILL }
                        else
                            android.graphics.Paint().apply { color = Color.argb(20, 0, 0, 0); style = Paint.Style.FILL }
                        canvas.drawRect(startX + letterW * i, startY, startX + letterW * (i + 1), startY + letterW, casePaint)
                        val p = if (i == this@MiroirIME.correctLetterIndex) activePaint else letterPaint
                        canvas.drawText(label[i].toString(), cx, cy, p)
                    }
                }
                // Filtre : pas de labels normaux en mode correction, mais le template oui
                // Lignes de template (au-dessus du cadre, pour rester visibles)
                for (y in cachedTemplateLines) {
                    canvas.drawLine(0f, y, width.toFloat(), y, Template.GUIDE_PAINT)
                }
                // Strokes de correction accumulés (au-dessus du cadre)
                for (p in correctionPaths) {
                    canvas.drawPath(p, strokePaint)
                }
                canvas.drawPath(currentPath, strokePaint)
                return  // ← filtre : on ne continue PAS le dessin normal
            }
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
                    // ═══ Suivi du tap et long-press — on diffère onStylusDown ═══
                    tapStartX = event.x; tapStartY = event.y
                    tapStartTime = System.currentTimeMillis()
                    tapMoved = false
                    tapStrokeStarted = false
                    longPressTriggered = false

                    // ═══ Si on était en mode édition, vérifier si on reste dans le blob ═══
                    if (editMode != EditMode.NONE) {
                        if (editMode == EditMode.CORRECT_TRANSCRIPTION) {
                            // Détecter clic sur une lettre de l'overlay
                            val idx = hitTestLetter(event.x, event.y)
                            if (idx >= 0) {
                                correctLetterIndex = idx
                                correctionPaths.clear()  // nouveau caractère → vider les anciens strokes
                                Log.i(TAG, "Correction: lettre #$idx ciblée")
                                imeView?.postInvalidate()
                                return true
                            }
                            // Clic hors des lettres → sortir du mode
                            exitEditMode()
                            currentPath.reset()  // ═══ pas de trait fantôme ═══
                            tapStrokeStarted = true  // ═══ pas de groupe parasite au prochain MOVE ═══
                            return true
                        }
                        if (!isInBlob(event.x, event.y)) {
                            exitEditMode()  // tap dans le vide → pose la montre, reprend la plume
                            currentPath.reset()  // ═══ pas de trait fantôme ═══
                            tapStrokeStarted = true  // ═══ pas de groupe parasite au prochain MOVE ═══
                            return true
                        }
                        // On reste dans le blob → mode édition immédiat, pas de new long-press
                        gestureStartX = event.x; gestureStartY = event.y
                        if (editMode == EditMode.ERASE) scrubBaseX = event.x
                        longPressTriggered = true  // réactiver le mode pour le geste suivant
                        return true
                    }

                    // ═══ Sélection visuelle (sans transition GroupManager) ═══
                    activeBlobGroupId = null
                    for ((gid, data) in groupBlobs) {
                        if (data.bounds.contains(event.x, event.y)) {
                            activeBlobGroupId = gid
                            break
                        }
                    }
                    // ═══ Armer le long-press (500ms) pour sélection + absorption ═══
                    // Si le stylet reste immobile sur un blob → selectGroup()
                    cancelLongPressRunnable()
                    if (activeBlobGroupId != null) {
                        val gid = activeBlobGroupId!!
                        longPressRunnable = Runnable {
                            if (!tapMoved && activeBlobGroupId == gid) {
                                val gid = activeBlobGroupId ?: return@Runnable
                                longPressTriggered = true
                                val gm = groupManager
                                if (gm != null) {
                                    if (gm.selectGroup(gid)) {
                                        Log.i(TAG, "Long-press: groupe ${gid.take(8)} SELECTED")
                                    } else {
                                        Log.i(TAG, "Long-press: groupe ${gid.take(8)} déjà actif")
                                    }
                                    gestureStartX = tapStartX; gestureStartY = tapStartY
                                    editMode = EditMode.NONE
                                    enterViewMode()  // afficher le blob
                                }
                            }
                        }
                        uiHandler.postDelayed(longPressRunnable!!, CalibrationActivity.getSelectionDelay(this@MiroirIME))
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    // Annuler le long-press si on bouge (sauf si déjà déclenché → geste d'édition)
                    if (Math.abs(event.x - tapStartX) > 10f || Math.abs(event.y - tapStartY) > 10f) {
                        if (!longPressTriggered) {
                            cancelLongPressRunnable()
                            tapMoved = true
                        }
                    }
                    // ═══ Détection de geste après long-press (États B/C) ═══
                    if (longPressTriggered) {
                        val dx = event.x - gestureStartX
                        val dy = event.y - gestureStartY
                        if (editMode == EditMode.NONE) {
                            if (dx < -SWIPE_THRESHOLD) {
                                editMode = EditMode.ERASE
                                scrubBaseX = event.x
                                scrubTimelinePos = 1f
                                updateModeIndicator()
                                Log.i(TAG, "→ Mode EFFACEMENT (←)")
                            } else if (dy > SWIPE_THRESHOLD) {
                                editMode = EditMode.MOVE
                                updateModeIndicator()
                                Log.i(TAG, "→ Mode DÉPLACEMENT (↓)")
                            } else if (dy < -SWIPE_THRESHOLD) {
                                try {
                                Log.i(TAG, "SWIPE HAUT détecté dy=$dy gid=$activeBlobGroupId longPress=$longPressTriggered")
                                editMode = EditMode.CORRECT_TRANSCRIPTION
                                correctLetterIndex = -1
                                val gid = activeBlobGroupId
                                Log.i(TAG, "SWIPE HAUT: gid=$gid")
                                if (gid != null) {
                                    val gm = this@MiroirIME.groupManager
                                    Log.i(TAG, "SWIPE HAUT: gm=$gm allGroups=${gm?.allGroups()?.size}")
                                    val group = gm?.allGroups()?.find { it.id == gid }
                                    Log.i(TAG, "SWIPE HAUT: group=$group strokeIds=${group?.strokeIds}")
                                    val firstIdx = group?.strokeIds?.firstOrNull()
                                        ?.let { sid -> this@MiroirIME.inkStrokeIdToRegistryIndex[sid] }
                                    Log.i(TAG, "SWIPE HAUT: firstIdx=$firstIdx labels=${groupLabels.size}")
                                    if (firstIdx != null) {
                                        correctionGroupFirstIdx = firstIdx
                                        correctionOriginalLabel = groupLabels[firstIdx] ?: ""
                                        correctionSavedGroup = group  // sauvegarder pour ré-enregistrement
                                        val label = groupLabels[firstIdx] ?: ""
                                        Log.i(TAG, "SWIPE HAUT: label='$label'")
                                        if (label.isNotEmpty()) {
                                            imeView?.postInvalidate()
                                        }
                                    }
                                }
                                updateModeIndicator()
                                Log.i(TAG, "→ Mode CORRECTION TRANSCRIPTION (↑)")
                                } catch (e: Exception) {
                                    Log.e(TAG, "SWIPE HAUT CRASH: ${e.message}", e)
                                }
                            }
                        } else if (editMode == EditMode.ERASE && dx < 0f) {
                            // ═══ Effacement proportionnel (scrub) — comme remonter l'aiguille ═══
                            scrubGroup(event.x)
                        } else if (editMode == EditMode.MOVE) {
                            // ═══ Déplacement continu ═══
                            moveGroup(event.x, event.y)
                        }
                        return true  // pas de tracé pendant le geste d'édition
                    }
                    // Premier mouvement → c'est un tracé, pas un tap
                    if (!tapStrokeStarted) {
                        tapStrokeStarted = true
                        onStylusDown(tapStartX, tapStartY)
                    }
                    val historySize = event.historySize
                    for (i in 0 until historySize) {
                        onStylusPoint(event.getHistoricalX(i), event.getHistoricalY(i), event.getHistoricalPressure(i))
                    }
                    onStylusPoint(event.x, event.y, event.pressure)
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPressRunnable()
                    // ═══ Gestes d'édition : le mode tient après pen-up ═══
                    if (longPressTriggered) {
                        when (editMode) {
                            EditMode.ERASE -> {
                                if (!tapMoved) {
                                    // ═══ Tap dans le vide (pas de geste) → sortie du mode ═══
                                    exitEditMode()
                                    currentPath.reset()
                                    tapStrokeStarted = true
                                } else {
                                    // Mettre à jour la position de départ pour le prochain geste
                                    gestureStartX = event.x
                                    currentPath.reset()  // ═══ nettoie le path fantôme ═══
                                    redrawBitmapOnly()
                                    imeView?.postInvalidate()
                                }
                            }
                            EditMode.MOVE -> {
                                gestureStartX = event.x; gestureStartY = event.y
                                currentPath.reset()  // ═══ nettoie le path fantôme ═══
                                redrawBitmapOnly()
                                imeView?.postInvalidate()
                            }
                            EditMode.CORRECT_TRANSCRIPTION -> {
                                // Mode correction : attend un clic sur une lettre
                                gestureStartX = event.x; gestureStartY = event.y
                                currentPath.reset()
                                redrawBitmapOnly()
                                imeView?.postInvalidate()
                            }
                            EditMode.NONE -> {
                                // Tap sans mouvement → désactiver
                                exitEditMode()
                            }
                        }
                        return true
                    }
                    // ═══ Détection de tap (clic court sans mouvement) → boutons ═══
                    if (!tapMoved && System.currentTimeMillis() - tapStartTime < 300) {
                        if (handleToolbarTap(event.x, event.y)) {
                            return true  // tap consommé par un bouton — pas de stroke
                        }
                    }
                    // Si le stroke n'a jamais commencé (UP sans MOVE), ne rien faire
                    if (!tapStrokeStarted) return true
                    onStylusUp()
                    // Si absorption active → rafraîchir le blob du groupe absorbeur
                    activeBlobGroupId?.let { gid ->
                        val gm = groupManager ?: return@let
                        val group = gm.allGroups().find { it.id == gid } ?: return@let
                        computeBlobPath(group)?.let { blob ->
                            groupBlobs[gid] = blob
                            val b = blob.bounds; val pad = 10
                            imeView?.postInvalidate(
                                (b.left - pad).toInt(), (b.top - pad).toInt(),
                                (b.right + pad).toInt(), (b.bottom + pad).toInt())
                        }
                    }
                }
            }
            return true
        }

        // ── Long-press (clic long pour sélection + absorption) ──────────
        private var longPressRunnable: Runnable? = null
        private var longPressTriggered: Boolean = false

        // ═══ États B/C — édition par gestes ═══
        private var editMode = EditMode.NONE
        private var gestureStartX = 0f
        private var gestureStartY = 0f
        private val SWIPE_THRESHOLD = 30f  // px minimum pour détecter un glissement
        private var scrubBaseX = 0f        // début du geste d'effacement (pour le scrub proportionnel)
        private var scrubTimelinePos = 1f  // 0=rien gardé, 1=tout visible
        private var scrubbedGroupFirstIdx: Int? = null  // groupe modifié → ré-inférence à la sortie

        // ═══ États B/C — effacement et déplacement ═══
        private fun scrubGroup(currentX: Float) {
            val gid = activeBlobGroupId ?: return
            val group = groupManager?.allGroups()?.find { it.id == gid } ?: return
            if (group.strokeIds.isEmpty()) return
            val dx = scrubBaseX - currentX  // delta depuis la dernière frame (non cumulé)
            if (dx < 3f) return
            scrubBaseX = currentX  // le delta est consommé, la base avance
            // ═══ Effacement à rebours : chaque pixel de mouvement supprime un pixel de tracé ═══
            val strokes = group.strokeIds.mapNotNull { sid ->
                val idx = inkStrokeIdToRegistryIndex[sid]
                if (idx != null && idx < strokeRegistry.size) idx to strokeRegistry[idx] else null
            }.filter { (idx, sr) -> idx !in erasedStrokes && sr.points.size >= 2 }
            var remaining = dx.toDouble()
            for ((idx, sr) in strokes.reversed()) {
                if (remaining <= 0.0) break
                if (idx in erasedStrokes) continue
                val pts = sr.points
                // Longueur totale du stroke (pour le ratio 1:1)
                var strokeLen = 0.0
                for (i in 1 until pts.size)
                    strokeLen += Math.hypot(
                        (pts[i].first - pts[i-1].first).toDouble(),
                        (pts[i].second - pts[i-1].second).toDouble())
                if (strokeLen <= remaining) {
                    // Stroke entièrement effacé
                    sr.points.clear(); sr.timestamps.clear(); sr.pressures.clear()
                    erasedStrokes.add(idx)
                    remaining -= strokeLen
                } else {
                    // Effacement partiel : supprimer les points les plus récents par la fin
                    var accum = 0.0; var cutIdx = pts.size
                    for (i in pts.size - 1 downTo 1) {
                        accum += Math.hypot(
                            (pts[i].first - pts[i-1].first).toDouble(),
                            (pts[i].second - pts[i-1].second).toDouble())
                        if (accum >= remaining) { cutIdx = i; break }
                    }
                    if (cutIdx > 0) {
                        val kept = pts.take(cutIdx)
                        sr.points.clear(); sr.points.addAll(kept)
                        sr.timestamps.clear(); sr.timestamps.addAll(sr.timestamps.take(cutIdx))
                        sr.pressures.clear(); sr.pressures.addAll(sr.pressures.take(cutIdx))
                    }
                    remaining = 0.0
                }
            }
            Log.i(TAG, "⏳ ${"%.0f".format(dx - remaining)}px effacés (delta=dx)")
            if (scrubbedGroupFirstIdx == null) {
                scrubbedGroupFirstIdx = gid.let { groupId ->
                    val g = groupManager?.allGroups()?.find { it.id == groupId }
                    g?.strokeIds?.firstOrNull()
                        ?.let { sid -> inkStrokeIdToRegistryIndex[sid] }
                }
            }
            // ═══ Recalculer le blob après effacement (pour qu'isInBlob soit correct au prochain tap) ═══
            groupManager?.allGroups()?.find { it.id == gid }?.let { group ->
                computeBlobPath(group)?.let { blob ->
                    groupBlobs[gid] = blob
                    group.bounds.set(blob.bounds)
                }
            }
            redrawBitmapOnly()
            imeView?.postInvalidate()
        }

        /** Vrai si un geste d'édition est actif. */
        fun isEditing(): Boolean = editMode != EditMode.NONE
        fun isCorrecting(): Boolean = editMode == EditMode.CORRECT_TRANSCRIPTION

        /** Retourne l'index de la lettre touchée, ou -1. */
        private fun hitTestLetter(x: Float, y: Float): Int {
            val firstIdx = this@MiroirIME.correctionGroupFirstIdx
            val label = groupLabels[firstIdx] ?: return -1
            val anchor = groupAnchor[firstIdx] ?: return -1
            if (label.isEmpty()) return -1
            val spacing = CalibrationActivity.getTemplateSpacing(this@MiroirIME)
            val letterW = spacing * 0.7f
            val totalW = letterW * label.length
            val startX = anchor.first - totalW / 2f
            val startY = snapToLine(anchor.second) - spacing * 0.8f
            if (x < startX || x > startX + totalW || y < startY || y > startY + letterW) return -1
            return ((x - startX) / letterW).toInt().coerceIn(0, label.length - 1)
        }

        /** Redessine tous les strokes dans le bitmap, SANS refreshScreen. */
        private fun redrawBitmapOnly() {
            val canvas = bitmapCanvas ?: return
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for ((idx, sr) in strokeRegistry.withIndex()) {
                if (idx in erasedStrokes) continue
                if (sr.points.size < 2) continue
                val path = Path()
                path.moveTo(sr.points[0].first, sr.points[0].second)
                for (i in 1 until sr.points.size) {
                    path.lineTo(sr.points[i].first, sr.points[i].second)
                }
                canvas.drawPath(path, strokePaint.apply { style = Paint.Style.STROKE })
            }
        }

        private fun moveGroup(endX: Float, endY: Float) {
            val gid = activeBlobGroupId ?: run { Log.w(TAG, "move: pas de groupe actif"); return }
            val dx = endX - gestureStartX; val dy = endY - gestureStartY
            gestureStartX = endX; gestureStartY = endY  // continuer le mouvement
            Log.i(TAG, "↕ Déplacement groupe ${gid.take(8)} dx=$dx dy=$dy")
            val gm = groupManager
            val group = gm?.allGroups()?.find { it.id == gid } ?: return
            for (sid in group.strokeIds) {
                val idx = inkStrokeIdToRegistryIndex[sid] ?: continue
                if (idx < strokeRegistry.size) {
                    strokeRegistry[idx].points.replaceAll { (x, y) -> Pair(x + dx, y + dy) }
                }
            }
            // ═══ Déplacer l'ancre du label (groupAnchor) ═══
            group.strokeIds.firstOrNull()
                ?.let { sid -> inkStrokeIdToRegistryIndex[sid] }
                ?.let { firstIdx ->
                    groupAnchor[firstIdx]?.let { anchor ->
                        groupAnchor[firstIdx] = Pair(anchor.first + dx, anchor.second + dy)
                    }
                }
            // ═══ Déplacer les bounds du groupe (ordre de lecture) ═══
            group.bounds.offset(dx, dy)
            // ═══ Déplacer le blob visuel (sélection) ═══
            groupBlobs[gid]?.let { blob ->
                blob.path.offset(dx, dy)
                blob.bounds.offset(dx, dy)
            }
            cachedGMCacheSize = -1
            redrawBitmapOnly()
            imeView?.postInvalidate()
        }

        private fun isInBlob(x: Float, y: Float): Boolean {
            val gid = activeBlobGroupId ?: return false
            return groupBlobs[gid]?.bounds?.contains(x, y) == true
        }

        fun exitEditMode() {
            editMode = EditMode.NONE
            longPressTriggered = false
            // ═══ Effacement définitif des strokes neutralisés ═══
            val erasedSids = mutableListOf<Long>()
            if (erasedStrokes.isNotEmpty()) {
                val gm = groupManager
                val animatedGroupId = activeBlobGroupId
                // val erasedSids = mutableListOf<Long>()  ← déplacé avant le bloc
                // Parcourir les strokes du registre pour trouver les vidés
                for ((sid, idx) in inkStrokeIdToRegistryIndex.entries.toList()) {
                    if (idx in erasedStrokes && idx < strokeRegistry.size) {
                        strokeRegistry[idx] = StrokeRecord(id = "")  // vider
                        erasedSids.add(sid)
                        // ═══ Nettoyer le label du groupe (avant de retirer de la map) ═══
                        groupLabels.remove(idx)
                        inferredGroupFirstIdxs.remove(idx)
                        groupStrokeCountAtInference.remove(idx)
                        inkStrokeIdToRegistryIndex.remove(sid)
                    }
                }
                // Retirer les strokeIds effacés du groupe (GroupManager)
                animatedGroupId?.let { gid ->
                    gm?.allGroups()?.find { it.id == gid }?.strokeIds?.removeAll(erasedSids)
                }
                erasedStrokes.clear()
                Log.i(TAG, "🧹 ${erasedSids.size} strokes définitivement effacés")
            }
            // ═══ Si le groupe est vidé de tous ses strokes → supprimer label + blob ═══
            val erasedGroupId = activeBlobGroupId
            if (erasedGroupId != null) {
                val g = groupManager?.allGroups()?.find { it.id == erasedGroupId }
                // Ne supprimer que si le groupe existe ET est vraiment vide (pas juste évincé)
                if (g != null && g.strokeIds.isEmpty()) {
                    groupBlobs.remove(erasedGroupId)
                    // Nettoyer les labels AVANT d'avoir retiré inkStrokeIdToRegistryIndex
                    for (sid in erasedSids) {
                        val firstIdxInMap = inkStrokeIdToRegistryIndex[sid]
                        if (firstIdxInMap != null) {
                            groupLabels.remove(firstIdxInMap)
                            inferredGroupFirstIdxs.remove(firstIdxInMap)
                            groupStrokeCountAtInference.remove(firstIdxInMap)
                        }
                    }
                    scrubbedGroupFirstIdx = null
                    Log.i(TAG, "🗑️ Groupe ${erasedGroupId.take(8)} vidé → supprimé")
                }
            }
            // ═══ Réactiver le groupe modifié comme SELECTED pour GroupManager ═══
            val reactivateId = activeBlobGroupId
            if (reactivateId != null) {
                // ═══ Laisser le groupe absorber (SELECTED) — fonctionnement normal ═══
                // Le refresh des labels est géré par recognizeGroup() après l'inférence
                // Synchroniser les bounds avec la position visuelle actuelle
                val existingGroup = groupManager?.getGroup(reactivateId)
                if (existingGroup != null) {
                    groupBlobs[reactivateId]?.let { blob ->
                        existingGroup.bounds.set(blob.bounds)
                    }
                    // Maintenir le groupe SELECTED pour l'absorption
                    if (existingGroup.state != GroupState.SELECTED) {
                        try { groupManager?.selectGroup(reactivateId) } catch (_: Exception) {}
                    }
                }
                // ═══ Étendre les bounds du groupe pour couvrir le blob visuel (plus large que le rectangle strict) ═══
                groupBlobs[reactivateId]?.bounds?.let { blobBounds ->
                    val gm = groupManager
                    val g = gm?.allGroups()?.find { it.id == reactivateId }
                    if (g != null && !blobBounds.isEmpty) {
                        val rx = gm?.params?.spatialDistancePx?.toFloat() ?: 40f
                        val ry = gm?.params?.spatialDistanceY?.toFloat() ?: 40f
                        g.bounds.union(blobBounds.left - rx, blobBounds.top - ry)
                        g.bounds.union(blobBounds.right + rx, blobBounds.bottom + ry)
                    }
                }
                Log.i(TAG, "🔄 Groupe ${reactivateId.take(8)} réactivé comme SELECTED")
            }
            activeBlobGroupId = null
            // ═══ Nettoyer le stroke en cours (évite un tracé de sortie parasite) ═══
            currentStroke = null
            currentPath.reset()
            isStylusDown = false  // ═══ réinitialiser le flag du stylet (mode édition ne l'a pas reset) ═══
            // ═══ Recalculer le blob et forcer la ré-inférence ═══
            val firstIdx = scrubbedGroupFirstIdx
            if (firstIdx != null) {
                inferredGroupFirstIdxs.remove(firstIdx)
                groupStrokeCountAtInference.remove(firstIdx)
                val gm = groupManager
                val group = gm?.allGroups()?.find { g ->
                    g.strokeIds.firstOrNull()?.let { sid -> inkStrokeIdToRegistryIndex[sid] } != null
                }
                if (group != null) {
                    computeBlobPath(group)?.let { blob -> groupBlobs[group.id] = blob }
                    Log.i(TAG, "🔄 Blob recalculé pour ${group.id.take(8)}")
                }
                uiHandler.post { scheduleGroupInference() }
            }
            scrubbedGroupFirstIdx = null
            // ═══ Mettre à jour le bitmap AVANT le refresh unique ═══
            // poserLabelPuisDU(GU) fait déjà un refreshScreen(GU) — inutile de le dupliquer
            redrawBitmapOnly()
            cachedGMCacheSize = -1
            displayController?.poserLabelPuisDU(DisplayMode.GU)
            updateModeIndicator()
            Log.i(TAG, "🔚 Sortie édition → retour DU")
        }

        private fun updateModeIndicator() {
            val symbol = when {
                editMode == EditMode.ERASE -> "⌛"
                editMode == EditMode.MOVE -> "↕"
                editMode == EditMode.CORRECT_TRANSCRIPTION -> "🔤"
                else -> "✍"
            }
            modeIndicator?.text = symbol
        }

        private fun cancelLongPressRunnable() {
            longPressRunnable?.let { uiHandler.removeCallbacks(it) }
            longPressRunnable = null
        }

        // ── Détection de tap stylet ────────────────────────────────────
        private var tapStartX = 0f; private var tapStartY = 0f
        private var tapStartTime = 0L; private var tapMoved = false
        private var tapStrokeStarted = false  // true si onStylusDown a été appelé

        /** Vérifie si le tap est dans la toolbar et déclenche l'action. */
        private fun handleToolbarTap(x: Float, y: Float): Boolean {
            if (y > toolbarHeightPx) return false  // pas dans la toolbar
            // 4 boutons : ✓ ⚙ 👁 + puis ✕
            val btnWidth = width / 4f
            val index = (x / btnWidth).toInt().coerceIn(0, 3)
            Log.i(TAG, "Tap stylet bouton #$index (x=$x, y=$y)")
            when (index) {
                0 -> { savePage(); currentInputConnection?.commitText("\n", 1); requestHideSelf(0) }
                1 -> {
                    val intent = android.content.Intent(this@MiroirIME, CalibrationActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                2 -> { showOverlays = !showOverlays; refreshAll() }
                3 -> {
                    currentInputConnection?.let { ic ->
                        ic.performContextMenuAction(android.R.id.selectAll)
                        ic.commitText("", 1)
                    }
                    clearPage(); refreshAll(); requestHideSelf(0)
                }
            }
            return true
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SÉLECTION — appui long (remplace le survol, inopérant dans l'IME)
    // ═══════════════════════════════════════════════════════════════════

    // (fireLongPress, armLongPress, cancelLongPress définis plus haut)

    /** Cale Y sur la ligne de portée la plus proche — 20% au-dessus, 80% en dessous. */
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
        // Frontière à 20% depuis la ligne du haut (80% en dessous)
        // La majorité de l'espace appartient à la ligne du bas.
        // Seulement les points très hauts (20% supérieurs) snap vers le haut.
        val spacing = lower - upper
        val boundary = upper + spacing * 0.2f
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
                    override fun onBeginRawDrawing(p0: Boolean, p1: OnyxTouchPoint) {
                        // ═══ Activer DU au premier contact du stylet (quel que soit le chemin d'accès) ═══
                        // Ne pas dupliquer si onStylusDown déjà déclenché par onTouchEvent
                        // Ne pas créer de stroke si on est en mode édition (geste de sortie)
                        if (!isStylusDown && !(imeView?.isEditing() == true)) onStylusDown(p1.x, p1.y)
                    }
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

            enterWriteMode()  // mode DU pour l'écriture
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
            enterViewMode()  // mode REGAL pour voir les overlays
        }
        isWriteMode = false  // ⚠️ forcer GU même si enterViewMode n'a pas été appelé (vue détruite)
        touchHelper = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // GESTION DES POINTS STYLET
    // ═══════════════════════════════════════════════════════════════════

    private var lastPointRefresh = 0L
    private var isStylusDown = false

    private fun onStylusDown(x: Float, y: Float) {
        isStylusDown = true
        // Mode correction → désélectionner le groupe original pour que les strokes forment un NOUVEAU groupe
        if (imeView?.isCorrecting() == true && correctLetterIndex >= 0) {
            groupManager?.allGroups()?.find { it.state == GroupState.SELECTED }?.let {
                groupManager?.deselectGroup(it.id)
            }
            // Garder le GU/REGAL pour voir l'overlay
        } else {
            enterWriteMode()  // basculer en DU pour le tracé fluide
        }
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
        if (imeView?.isCorrecting() == true) {
            // Mode correction : invalidate logiciel + handwritingRepaint ciblé cadre uniquement
            val now = System.currentTimeMillis()
            if (now - lastPointRefresh >= 33L) {  // ~30 FPS
                lastPointRefresh = now
                imeView?.invalidate()  // redessine le canvas (currentPath mis à jour)
            }
        } else {
            val interval = CalibrationActivity.getRefreshInterval(this@MiroirIME)
            val now = System.currentTimeMillis()
            if (now - lastPointRefresh >= interval) {
                lastPointRefresh = now
                val r = 10
                refreshRect(x.toInt() - r, y.toInt() - r, x.toInt() + r, y.toInt() + r, isStroke = true)
            }
        }
    }

    private fun onStylusUp() {
        isStylusDown = false
        val stroke = currentStroke
        currentStroke = null
        if (stroke == null || stroke.points.isEmpty()) return
        // ═══ Ignorer les taps dans le vide (un seul point) ═══
        if (stroke.points.size < 2) {
            currentPath.reset()
            return
        }

        // ═══ Mode correction → chemin normal (strokeRegistry + GroupManager + inférence standard) ═══
        val isCorrection = imeView?.isCorrecting() == true && correctLetterIndex >= 0

        if (isCorrection) {
            // Sauvegarder le path pour dessin au-dessus du cadre (pas dans le bitmap principal)
            correctionPaths.add(android.graphics.Path(currentPath))
            currentPath.reset()
        } else {
            // Rastériser le stroke dans le bitmap
            val canvas = bitmapCanvas ?: return
            if (stroke.points.size < 2) {
                val p = stroke.points.first()
                canvas.drawCircle(p.first, p.second, 1.5f, strokePaint.apply { style = Paint.Style.FILL })
            } else {
                canvas.drawPath(currentPath, strokePaint.apply { style = Paint.Style.STROKE })
            }
            currentPath.reset()
        }

        // Ajouter au registre
        strokeRegistry.add(stroke)
        val registryIdx = strokeRegistry.size - 1
        val inkId = ++inkStrokeIdCounter
        inkStrokeIdToRegistryIndex[inkId] = registryIdx

        // ═══ GroupManager : groupement spatial ═══
        val inkStroke = strokeRecordToInkStroke(stroke, inkId)
        groupManager?.onStrokeSealed(inkStroke)

        // Armer le timer d'inférence pour les groupes modifiés
        scheduleGroupInference()

        // ═══ Rafraîchir uniquement la zone du stroke ═══
        // En mode correction → pas de refreshRect (le cadre tampon gère l'affichage)
        if (imeView?.isCorrecting() != true) {
            if (stroke.points.size >= 2) {
                var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
                var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
                for ((x, y) in stroke.points) {
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
                refreshRect(
                    (minX - 10).toInt(), (minY - 10).toInt(),
                    (maxX + 10).toInt(), (maxY + 10).toInt(), isStroke = true)
            }
            if (isWriteMode) {
                displayController?.reasserterDU()
            }
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

    // ═══ États d'édition B/C ═══
    private enum class EditMode { NONE, ERASE, MOVE, CORRECT_TRANSCRIPTION }

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

        val loadedGroups = gm.groupsInState(GroupState.LOADED) + gm.groupsInState(GroupState.SELECTED)
        var armed = 0; var skipped = 0
        val allStates = gm.allGroups().joinToString { "${it.id.take(8)}:${it.state}" }
        Log.d(TAG, "SCHEDULE: ${loadedGroups.size} groupes LOADED/SELECTED (total=${gm.allGroups().size}: $allStates)")
        for (group in loadedGroups) {
            if (group.strokeIds.isEmpty()) continue
            val firstIdx = inkStrokeIdToRegistryIndex[group.strokeIds.first()] ?: continue
            val strokeCount = group.strokeIds.size

            // Déjà inféré mais modifié → permettre la ré-inférence
            val infCount = groupStrokeCountAtInference[firstIdx]
            if (firstIdx in inferredGroupFirstIdxs) {
                if (infCount != null && strokeCount == infCount) continue  // vraiment inchangé
                // Modifié depuis l'inférence → ré-ouvrir pour ré-inférence
                inferredGroupFirstIdxs.remove(firstIdx)
            }

            // ═══ Réarmer : chaque nouveau trait dans le groupe reset le compte à rebours ═══
            // Le timer ne tire qu'après inferDelay d'inactivité DANS CE GROUPE.
            val hadTimer = groupTimers.containsKey(firstIdx)
            val countChanged = timerArmedStrokeCount[firstIdx] != strokeCount
            if (hadTimer && !countChanged) { skipped++; continue }  // groupe inchangé
            armed++
            groupLastModifiedMs[firstIdx] = now
            groupTimers.remove(firstIdx)?.cancel(false)
            // Ancrer le groupe si nouveau (premier timer)
            // ═══ Utiliser le CENTRE de la boîte englobante, pas le premier point ═══
            // Le premier point du premier stroke est trop sensible aux hampes :
            // un 't' commence haut → label placé sur la ligne du dessus.
            // Le centre des bounds est stable, comme dans le Miroir classique.
            if (groupAnchor[firstIdx] == null) {
                val cx = if (!group.bounds.isEmpty) group.bounds.centerX()
                         else strokeRegistry.getOrNull(firstIdx)?.points?.firstOrNull()?.first ?: 0f
                val cy = if (!group.bounds.isEmpty) group.bounds.centerY()
                         else strokeRegistry.getOrNull(firstIdx)?.points?.firstOrNull()?.second ?: 0f
                groupAnchor[firstIdx] = Pair(cx, cy)
            }
            timerArmedAt[firstIdx] = now
            timerArmedStrokeCount[firstIdx] = strokeCount
            val timer = inferExecutor.schedule({
                armGroupInference(firstIdx)
            }, inferDelay, java.util.concurrent.TimeUnit.MILLISECONDS)
            groupTimers[firstIdx] = timer
            Log.d(TAG, "TIMER ARM firstIdx=$firstIdx → ${inferDelay}ms (${strokeCount}s)")
        }
        if (armed > 0 || skipped > 0) Log.d(TAG, "SCHEDULE: ${loadedGroups.size} groupes, $armed armés, $skipped skip")
    }

    /** Appelé par le timer — vérifie que le stylet n'est pas en train d'écrire. */
    private fun armGroupInference(firstIdx: Int) {
        Log.d(TAG, "TIMER CHECK firstIdx=$firstIdx isStylusDown=$isStylusDown timerExists=${groupTimers.containsKey(firstIdx)}")
        // ═══ Ne pas inférer si le stylet est encore posé (écriture en cours) ═══
        if (isStylusDown) {
            groupTimers.remove(firstIdx)
            // ═══ Réarmer après 800ms au lieu de perdre l'inférence ═══
            val timer = inferExecutor.schedule({
                armGroupInference(firstIdx)
            }, 800L, java.util.concurrent.TimeUnit.MILLISECONDS)
            groupTimers[firstIdx] = timer
            Log.d(TAG, "TIMER DEFERRED firstIdx=$firstIdx — réarmé dans 800ms")
            return
        }
        // ═══ Garde-fou : groupe modifié depuis l'armement du timer → ignorer ═══
        val armedAt = timerArmedAt[firstIdx] ?: run { Log.w(TAG, "TIMER FAIL firstIdx=$firstIdx: armedAt null"); return }
        val lastMod = groupLastModifiedMs[firstIdx] ?: run { Log.w(TAG, "TIMER FAIL firstIdx=$firstIdx: lastMod null"); return }
        if (lastMod > armedAt) {
            Log.d(TAG, "TIMER SKIP firstIdx=$firstIdx — modifié (armed=$armedAt, lastMod=$lastMod)")
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
        Log.i(TAG, "TIMER FIRED firstIdx=$firstIdx -> inference group ${group.id} (${indices.size} strokes)")
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

                    // ═══ Mode correction → corriger la lettre du groupe ORIGINAL ═══
                    if (imeView?.isCorrecting() == true && correctLetterIndex >= 0) {
                        val origFirstIdx = correctionGroupFirstIdx
                        val origLabel = groupLabels[origFirstIdx] ?: return@post
                        if (correctLetterIndex < origLabel.length) {
                            val corrected = result.first().toString()
                            val newLabel = origLabel.substring(0, correctLetterIndex) + corrected +
                                           origLabel.substring(correctLetterIndex + 1)
                            groupLabels[origFirstIdx] = newLabel
                            Log.i(TAG, "Correction: '$origLabel' → '$newLabel' (lettre #$correctLetterIndex: '$corrected')")
                            // Supprimer le groupe temporaire et ses strokes du strokeRegistry
                            val gm = groupManager
                            val tempGroup = gm?.allGroups()?.find { g ->
                                g.strokeIds.firstOrNull()?.let { sid -> inkStrokeIdToRegistryIndex[sid] == firstIdx } == true
                            }
                            if (tempGroup != null) {
                                // Retirer les strokes du strokeRegistry
                                val removedIndices = tempGroup.strokeIds.mapNotNull { sid ->
                                    inkStrokeIdToRegistryIndex.remove(sid)
                                }.sortedDescending()
                                for (idx in removedIndices) {
                                    if (idx < strokeRegistry.size) strokeRegistry.removeAt(idx)
                                }
                                // Ré-indexer
                                for (entry in inkStrokeIdToRegistryIndex.entries) {
                                    val shift = removedIndices.count { it < entry.value }
                                    if (shift > 0) entry.setValue(entry.value - shift)
                                }
                                groupBlobs.remove(tempGroup.id)
                                gm.removeGroup(tempGroup.id)
                            }
                            // Ré-animer le blob du groupe original (le groupe peut être évincé)
                            val savedGroup = correctionSavedGroup
                            if (savedGroup != null) {
                                computeBlobPath(savedGroup)?.let { blob ->
                                    groupBlobs[savedGroup.id] = blob
                                }
                            }
                            correctionPaths.clear()
                            imeView?.postInvalidate()
                        }
                        return@post
                    }

                    groupLabels[firstIdx] = result
                    Log.i(TAG, "LABEL set: firstIdx=$firstIdx -> '$result' (${groupLabels.size} labels total)")
                    cachedGMCacheSize = -1
                    // Calculer et cacher le blob pour ce groupe
                    val gm = groupManager ?: return@post
                    val group = gm.allGroups().firstOrNull { g ->
                        val sid = g.strokeIds.firstOrNull() ?: return@firstOrNull false
                        inkStrokeIdToRegistryIndex[sid] == firstIdx
                    }
                    if (group != null) {
                        computeBlobPath(group)?.let { blob ->
                            groupBlobs[group.id] = blob
                        }
                        // ═══ PAS de sélection auto — le blob est la vue de sélection,
                        // pas le témoin d'inférence. Le label EST le témoin. ═══
                    }
                    // ═══ Plus d'injection à l'inférence — le texte est poussé uniquement à la validation (✓) ═══
                    Log.i(TAG, "Texte injecte: " + result)
                    // ═══ Label dessiné dans drawGroupLabels() — pas de doublon bitmap ═══
                    val anchor = groupAnchor[firstIdx]
                    // ═══ Rafraîchir les overlays PUIS revenir en DU ═══
                    displayController?.poserLabelPuisDU(DisplayMode.GU)
                    // ═══ handwritingRepaint ciblé sur la zone du label (pas tout l'écran !) ═══
                    try {
                        val v = imeView ?: return@post
                        v.postInvalidate()
                        if (anchor != null) {
                            val pad = 10
                            val labelX = (anchor.first - 30f - pad).toInt()
                            val labelY = (snapToLine(anchor.second) - pad).toInt()
                            val right = (labelX + labelPaint.measureText(result) + pad * 2).toInt()
                            val bottom = (snapToLine(anchor.second) + labelPaint.textSize * 3 + pad).toInt()
                            EpdController.handwritingRepaint(v, labelX, labelY, right, bottom)
                        } else {
                            EpdController.handwritingRepaint(v, 0, 0, v.width, v.height)
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reconnaissance groupe: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // COMMIT — injection dans le champ cible (setComposingText remplace)
    // ═══════════════════════════════════════════════════════════════════

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.setComposingText("$text ", 1)
        Log.i(TAG, "Texte injecté (composing): \"$text\"")
    }

    // ── Note synchronisée ─────────────────────────────────────────────
    private var syncedNoteText: String = ""  // copie de ce qui est dans le champ texte

    /** Construit le texte complet dans l'ordre de lecture (tri spatial).
     *  N'inclut que les groupes avec label (les groupes sans reconnaissance sont ignorés).
     *  Utilisé à la validation (bouton ✓) pour pousser le texte d'un coup. */
    private fun buildReadingOrderText(): String {
        if (groupLabels.isEmpty()) return ""
        data class Word(val line: Float, val x: Float, val text: String)
        val words = mutableListOf<Word>()
        for ((firstIdx, text) in groupLabels) {
            val anchor = groupAnchor[firstIdx] ?: continue
            words.add(Word(snapToLine(anchor.second), anchor.first, text))
        }
        if (words.isEmpty()) return ""
        // Trier les mots par ligne (Y) puis X
        words.sortWith(compareBy<Word> { it.line }.thenBy { it.x })
        // ═══ Parcourir toutes les lignes de la portée, y compris les lignes vides ═══
        // Une ligne vide = retour à la ligne simple. Deux lignes vides = saut de paragraphe.
        val sb = StringBuilder()
        var emptyLineCount = 0
        var wordIdx = 0
        for (ly in cachedTemplateLines) {
            val wordsOnThisLine = mutableListOf<Word>()
            while (wordIdx < words.size && Math.abs(words[wordIdx].line - ly) < 1f) {
                wordsOnThisLine.add(words[wordIdx])
                wordIdx++
            }
            if (wordsOnThisLine.isEmpty()) {
                // Ligne vide → compter
                emptyLineCount++
            } else {
                // Ligne avec mots → vider le compteur de lignes vides
                if (emptyLineCount > 0) {
                    for (i in 0 until emptyLineCount) sb.append("\n")
                    emptyLineCount = 0
                }
                for (w in wordsOnThisLine) {
                    sb.append(w.text).append(" ")
                }
            }
        }
        return sb.toString()
    }

    /** Met à jour l'indicateur de page (numéro page courante / totale). */
    private fun updatePageIndicator() {
        val total = maxOf(currentPageIndex + 1, countPages())
        pageLabel?.text = "${currentPageIndex + 1}/$total"
    }

    /** Compte le nombre total de pages sauvegardées (avec contenu). */
    private fun countPages(): Int {
        val dir = blockDir ?: return 0
        if (!dir.exists()) return 0
        return dir.listFiles()?.count { f ->
            f.isDirectory && f.name.startsWith("page_") &&
            java.io.File(f, "state.json").exists()
        } ?: 0
    }

    /** Construit le texte de TOUTES les pages dans l'ordre de lecture. */
    private fun buildAllPagesText(): String {
        val dir = blockDir ?: return buildReadingOrderText()
        if (!dir.exists() || !dir.isDirectory) return buildReadingOrderText()
        val pageDirs = dir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("page_") && java.io.File(it, "state.json").exists() }
            ?.sortedBy { it.name.removePrefix("page_").toIntOrNull() ?: -1 }
            ?: emptyList()
        val sb = StringBuilder()
        // Parcourir toutes les pages dans l'ordre (0, 1, 2...)
        for (pd in pageDirs) {
            val pi = pd.name.removePrefix("page_").toIntOrNull() ?: continue
            if (pi == currentPageIndex) {
                // Page courante → texte depuis la mémoire (avec mise en forme)
                val pageText = buildReadingOrderText()
                Log.i(TAG, "buildAllPages: page $pi (courante) = ${groupLabels.size} labels -> \"${pageText.take(60)}\"")
                if (pageText.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append("\n\n")  // ligne vide entre deux pages
                    sb.append(pageText)
                }
            } else {
                // Autre page → reconstruire l'ordre de lecture depuis les ancres
                val stateFile = java.io.File(pd, "state.json")
                if (!stateFile.exists()) continue
                try {
                    val json = org.json.JSONObject(stateFile.readText())
                    val labelsObj = json.optJSONObject("labels") ?: continue
                    val anchorsObj = json.optJSONObject("anchors")
                    data class Word(val y: Float, val x: Float, val text: String)
                    val words = mutableListOf<Word>()
                    for (key in labelsObj.keys()) {
                        val text = labelsObj.optString(key, "")
                        if (text.isBlank()) continue
                        val firstIdx = key.toIntOrNull() ?: continue
                        var x = 0f; var y = 0f
                        if (anchorsObj != null) {
                            val arr = anchorsObj.optJSONArray(key)
                            if (arr != null && arr.length() >= 2) {
                                x = arr.optDouble(0).toFloat()
                                y = arr.optDouble(1).toFloat()
                            }
                        }
                        words.add(Word(y, x, text))
                    }
                    if (words.isEmpty()) continue
                    // ═══ Reconstruire avec la grammaire des interlignes ═══
                    // Basé sur les Y bruts (pas snapToLine) pour détecter les interlignes vides.
                    // Regrouper en lignes (mots dont |Δy| < spacing*0.5), puis :
                    //   1 interligne vide → \n, 2+ interlignes vides → \n\n
                    words.sortWith(compareBy<Word> { it.y }.thenBy { it.x })
                    val spacing = CalibrationActivity.getTemplateSpacing(this@MiroirIME)
                    val pageSb = StringBuilder()
                    var lineIdx = 0
                    var prevLineY = words.first().y
                    while (lineIdx < words.size) {
                        // Grouper tous les mots sur cette ligne (Y proche)
                        val lineY = words[lineIdx].y
                        val lineWords = mutableListOf<Word>()
                        while (lineIdx < words.size && Math.abs(words[lineIdx].y - lineY) < spacing * 0.5f) {
                            lineWords.add(words[lineIdx])
                            lineIdx++
                        }
                        // Trier les mots de cette ligne par X
                        lineWords.sortBy { it.x }
                        // Ajouter un saut si ce n'est pas la première ligne
                        if (pageSb.isNotEmpty()) {
                            val gapRatio = (lineY - prevLineY) / spacing
                            val skippedLines = Math.round(gapRatio).toInt() - 1  // 0=adjacentes, 1=1 vide, 2+=paragraphe
                            when {
                                skippedLines >= 2 -> pageSb.append("\n\n")  // 2+ interlignes vides → paragraphe
                                skippedLines == 1 -> pageSb.append("\n")    // 1 interligne vide → retour ligne
                                else -> pageSb.append(" ")                  // lignes adjacentes → espace
                            }
                        }
                        prevLineY = lineY
                        // Joindre les mots de cette ligne
                        pageSb.append(lineWords.joinToString(" ") { it.text })
                    }
                    val pageText = pageSb.toString()
                    Log.i(TAG, "buildAllPages: page $pi (sauvegardée) = ${words.size} labels -> \"${pageText.take(60)}\"")
                    if (pageText.isNotBlank()) {
                        if (sb.isNotEmpty()) sb.append("\n\n")  // ligne vide entre deux pages
                        sb.append(pageText)
                    }
                } catch (_: Exception) {}
            }
        }
        // Si aucune page sauvegardée → utiliser la page courante uniquement
        if (sb.isEmpty()) return buildReadingOrderText()
        return sb.toString()
    }

    // ═══════════════════════════════════════════════════════════════════
    // MENUS CONTEXTUELS (clic long ◀ et ▶)
    // ═══════════════════════════════════════════════════════════════════

    /** Affiche un panneau overlay (cache la surface de capture). */
    private fun showOverlay(content: View, title: String) {
        val panel = overlayPanel ?: return
        panel.removeAllViews()
        // ── Barre titre + bouton fermer ──────────────────────────────
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.argb(220, 240, 240, 240))
        }
        val titleView = android.widget.TextView(this).apply {
            text = title
            textSize = 20f
            setTextColor(Color.DKGRAY)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)
        val closeBtn = android.widget.Button(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.DKGRAY)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hideOverlay() }
        }
        header.addView(closeBtn)
        panel.addView(header)
        // ── Contenu scrollable ───────────────────────────────────────
        panel.addView(content, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Afficher
        panel.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlayPanel?.visibility = View.GONE
        overlayPanel?.removeAllViews()
        imeView?.postInvalidate()
    }

    /** Clic long ◀ — Liste des blocs disponibles. */
    private fun showBlockList() {
        val blocksDir = java.io.File(cacheDir, "blocks")
        val blocks = blocksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        if (blocks.isEmpty()) {
            android.widget.Toast.makeText(this, "Aucun bloc sauvegardé", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val listView = android.widget.ListView(this).apply {
            setBackgroundColor(Color.WHITE)
            adapter = object : android.widget.BaseAdapter() {
                override fun getCount() = blocks.size
                override fun getItem(pos: Int) = blocks[pos]
                override fun getItemId(pos: Int) = pos.toLong()
                override fun getView(pos: Int, convertView: View?, parent: ViewGroup?): View {
                    val dir = blocks[pos]
                    val name = dir.name
                    val lastUnderscore = name.lastIndexOf('_')
                    val appName = if (lastUnderscore > 0) name.substring(0, lastUnderscore).replace("_", ".") else "inconnu"
                    val ts = if (lastUnderscore > 0) name.substring(lastUnderscore + 1).toLongOrNull() ?: 0L else 0L
                    val date = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))
                    val pageCount = dir.listFiles()?.count { it.isDirectory && it.name.startsWith("page_") } ?: 0
                    val current = dir == blockDir
                    val prefix = if (current) "▸ " else "  "
                    val tv = android.widget.TextView(this@MiroirIME).apply {
                        text = "$prefix$appName — $date ($pageCount p.)"
                        textSize = 18f
                        setTextColor(if (current) Color.BLACK else Color.DKGRAY)
                        setPadding(30, 20, 30, 20)
                    }
                    return tv
                }
            }
            setOnItemClickListener { _, _, pos, _ ->
                val selected = blocks[pos]
                closeBlock()
                val name = selected.name
                val lastUnderscore = name.lastIndexOf('_')
                val appName = if (lastUnderscore > 0) name.substring(0, lastUnderscore).replace("_", ".") else "unknown"
                val ts = if (lastUnderscore > 0) name.substring(lastUnderscore + 1).toLongOrNull() ?: System.currentTimeMillis() else System.currentTimeMillis()
                hostAppName = appName
                blockTimestamp = ts
                blockDir = selected
                currentPageIndex = 0
                clearPage()
                loadPage(0)
                refreshAll()
                updatePageIndicator()
                hideOverlay()
                Log.i(TAG, "Bloc chargé via menu: ${selected.name}")
            }
        }

        showOverlay(listView, "Blocs (${blocks.size})")
    }

    /** Clic long ▶ — Toutes les transcriptions formatées (tel qu'injecté à la validation). */
    private fun showAllTranscriptions() {
        val fullText = buildAllPagesText()

        if (fullText.isBlank()) {
            android.widget.Toast.makeText(this, "Aucune transcription", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val scrollView = android.widget.ScrollView(this@MiroirIME)
        val textView = android.widget.TextView(this@MiroirIME).apply {
            text = fullText
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(30, 20, 30, 20)
            setLineSpacing(4f, 1.2f)
        }
        scrollView.addView(textView)

        showOverlay(scrollView, "Transcriptions (${groupLabels.size} labels)")
    }

    // ═══ Overlay de correction de transcription ═══
    private fun showCorrectionOverlay(label: String) {
        val panel = overlayPanel ?: return
        panel.removeAllViews()
        val spacing = CalibrationActivity.getTemplateSpacing(this@MiroirIME)
        val letterH = spacing * 0.6f  // hauteur des cases

        // ── Header ──────────────────────────────────────────────────
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(20, 15, 20, 15)
            setBackgroundColor(Color.argb(220, 240, 240, 240))
        }
        val titleView = android.widget.TextView(this).apply {
            text = "✎ Corriger : $label"
            textSize = 20f; setTextColor(Color.DKGRAY)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(titleView)
        // Bouton valider
        val validateBtn = android.widget.Button(this).apply {
            text = "✓"; textSize = 22f; setTextColor(Color.BLACK); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Propager le label corrigé
                val firstIdx = correctionGroupFirstIdx
                val correctedLabel = groupLabels[firstIdx] ?: ""
                val ic = currentInputConnection
                if (ic != null) {
                    ic.commitText("$correctedLabel ", 1)
                    Log.i(TAG, "Correction validée: '$correctionOriginalLabel' → '$correctedLabel'")
                }
                correctionOriginalLabel = ""
                correctLetterIndex = -1
                imeView?.exitEditMode()
                hideOverlay()
            }
        }
        header.addView(validateBtn)
        // Bouton annuler
        val cancelBtn = android.widget.Button(this).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.DKGRAY); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // Restaurer le label original
                groupLabels[correctionGroupFirstIdx] = correctionOriginalLabel
                correctLetterIndex = -1
                imeView?.exitEditMode()
                hideOverlay()
            }
        }
        header.addView(cancelBtn)
        panel.addView(header)

        // ── Zone des lettres ─────────────────────────────────────────
        val lettersRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(10, 20, 10, 20)
        }
        for (i in label.indices) {
            val letterView = android.widget.TextView(this).apply {
                text = label[i].toString()
                textSize = letterH * 1.2f
                setTextColor(if (i == correctLetterIndex) Color.BLUE else Color.DKGRAY)
                gravity = android.view.Gravity.CENTER
                setBackgroundColor(Color.argb(30, 200, 200, 200))
                setPadding(12, 8, 12, 8)
                setOnClickListener {
                    correctLetterIndex = i
                    showCorrectionOverlay(groupLabels[correctionGroupFirstIdx] ?: label)
                }
            }
            lettersRow.addView(letterView)
        }
        panel.addView(lettersRow)

        // ── Zone de statut ───────────────────────────────────────────
        val statusView = android.widget.TextView(this).apply {
            text = if (correctLetterIndex >= 0) "Écris la lettre #${correctLetterIndex + 1}" else "Tape une lettre à corriger"
            textSize = 16f; setTextColor(Color.GRAY); gravity = android.view.Gravity.CENTER
            setPadding(10, 10, 10, 10)
        }
        panel.addView(statusView, android.widget.LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ═══ Forwarder les événements tactiles vers la surface de capture ═══
        val captureView = imeView
        panel.setOnTouchListener { _, event ->
            captureView?.dispatchTouchEvent(event)
            false  // laisse le panel traiter aussi (boutons, lettres)
        }

        // Fond blanc uniquement sur le header (pour lisibilité)
        header.setBackgroundColor(Color.WHITE)

        panel.visibility = View.VISIBLE
    }

    /** Ancienne injection continue — remplacée par buildReadingOrderText() à la validation. */
    private fun injectReadingOrder() {
        // Conservé pour compatibilité mais plus appelé par l'inférence
        // Le texte est maintenant poussé uniquement au clic sur ✓
    }

    /** Réinitialise la copie synchronisée (après clearPage ou ✕). */
    private fun resetSyncedNote() {
        syncedNoteText = ""
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLOB — ray casting
    // ═══════════════════════════════════════════════════════════════════

    /** Calcule le blob d'un groupe : chemin + bounds. */
    private fun computeBlobPath(group: InkGroup): BlobData? {
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
        if (first) return null
        path.close()
        return BlobData(path, android.graphics.RectF(minX, minY, maxX, maxY))
    }

    private fun clearCanvas() {
        bitmapCanvas?.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        currentPath.reset()
        currentStroke = null
        refreshAll()
    }

    /** Dessine les labels à la position du groupe. */
    private fun drawGroupLabels(canvas: Canvas) {
        if (groupLabels.isEmpty()) return
        var drawn = 0
        for ((firstIdx, label) in groupLabels) {
            val anchor = groupAnchor[firstIdx]
            if (anchor == null) { Log.d(TAG, "LABEL skip firstIdx=$firstIdx: anchor null"); continue }
            // Trouver le groupe correspondant pour aligner à gauche
            val leftEdge = groupManager?.allGroups()?.firstOrNull { g ->
                g.strokeIds.firstOrNull()?.let { sid ->
                    inkStrokeIdToRegistryIndex[sid] == firstIdx
                } ?: false
            }?.bounds?.left ?: anchor.first
            val x = leftEdge                               // aligné à gauche du groupe
            val y = snapToLine(anchor.second) + labelPaint.textSize - 4f
            canvas.drawText(label, x, y, labelPaint)
            drawn++
        }
        if (drawn < groupLabels.size) Log.d(TAG, "LABEL drawn: $drawn/${groupLabels.size}")
    }

    // ═══════════════════════════════════════════════════════════════════
    // CORRECTION DE TRANSCRIPTION
    // ═══════════════════════════════════════════════════════════════════
    // La correction utilise le circuit NORMAL : strokes → strokeRegistry → GroupManager → inférence standard.
    // Les strokes de correction sont ajoutés au groupe existant, l'inférence normale met à jour le label.
    // Le buffer isolé et l'inférence parallèle ont été supprimés — trop complexes, résultat vide sur 1 stroke.
}
