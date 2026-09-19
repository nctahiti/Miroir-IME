package com.parnasse.miroir

// ============================================================================
// MiroirEngine — État et logique partagés entre IME et standalone.
// ============================================================================
// Cette classe contient les FIELDS et les fonctions CŒUR du Miroir
// (strokes, groupes, blocs, pages, V★, template, MDM).
// Elle ne dépend PAS d'Activity ou d'InputMethodService.
//
// Usage :
//   class CaptureActivity : Activity() {
//       val engine = MiroirEngine()
//       engine.ensureBlockDir(...)
//       engine.savePage()
//   }
// ============================================================================

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Deux maîtres de navigation : le Miroir (LOCAL) ou Parnasse (viewport). */
enum class NavMode { LOCAL, PARNASSE }

class MiroirEngine {

    companion object {
        const val TAG = "MiroirEngine"
    }

    // ── Blocs & Pages ──────────────────────────────────────────────────
    var blockDir: File? = null; private set
    var currentPageIndex = 0
    /** ⚓ MARÉE 30/08 — le drapeau du saut : la page a-t-elle changé depuis le
     *  dernier coucher ? Posé par l'écriture (endStroke), les archives
     *  (onGroupEvicted) et les effacements (CaptureSurfaceView). Le save
     *  conditionnel n'écrit que si la page a bougé — jamais 390 Ko de
     *  réécriture pour un battement d'horloge. */
    @Volatile var pageDirty = false
    private var appContext: android.content.Context? = null

    // ── Navigation viewport Parnasse ────────────────────────────────────
    // Quand navMode = PARNASSE, Parnasse est maître : il donne le nombre de
    // pages (parnasseTotal) et l'identité de la page courante (parnasseNoteId).
    // Le Miroir n'est plus qu'une surface — il reflète ce que Parnasse dicte.
    var navMode: NavMode = NavMode.LOCAL
    /** Gardien de l'œuvre — true dès que la page courante a été chargée
     *  (loadPageFull réussi) ; false au vide (clearPage, process neuf).
     *  Le savePageFull ne détruit jamais un vstar existant tant que la page
     *  n'a pas été chargée : un registre vide ne veut pas dire une page vide. */
    private var pageLoaded = false
    var coeurUrl: String = "http://127.0.0.1:8008"
    var parnasseBlockUuid: String? = null
    var parnasseTotal: Int = 0
    var parnasseNoteId: String? = null
    var parnassePageNumber: Int = -1
    var parnasseCapture: String? = null
    var parnasseUpdated: Long = 0L
    // ═══ Garde de séquence (26/08/2026) — la réponse fantôme ═══
    // Une réponse asynchrone tardive (navigation précédente) atterrissait
    // après le chargement de la nouvelle page et réécrivait l'état
    // (labels + barre) sans re-rasteriser le bitmap → encre d'une page,
    // étiquettes de la précédente : décalage d'une page, persistant.
    // Chaque requête porte un rang ; seule la dernière réponse s'applique.
    private var requestSeq = 0

    // Le Cœur répond sur le UI thread — jamais de blocage (ANR).
    private val uiHandler = Handler(Looper.getMainLooper())

    // ── Strokes ────────────────────────────────────────────────────────
    val strokeRegistry = mutableListOf<StrokeRecord>()
    val inkStrokeIdToRegistryIndex = mutableMapOf<Long, Int>()
    private var inkStrokeIdCounter: Long = 0
    var currentStrokeRecord: StrokeRecord? = null; private set
    var currentPath = Path(); private set

    /** Annule le stroke en cours sans le sauvegarder. */
    fun cancelStroke() {
        currentStrokeRecord = null
        currentPath.reset()
    }

    // ── Groupes ────────────────────────────────────────────────────────
    var groupManager: GroupManager? = null; private set
    // ── La relecture — l'encre et le sens ont deux régimes (voir Relecture.kt) ──
    // Une seule source : le label du groupe (nominal + courant) vit dans la sentinelle.
    val relecture = Relecture()
    val groupLabels get() = relecture.labels

    // ═══════════════════════════════════════════════════════════════════
    // 🎙️ LA VOIX DU CORRIGÉ (19/09/2026) — le pont (le Cœur) PROPOSE, le
    // Miroir AFFICHE, le Capitaine RATIFIE. Le pont ne touche RIEN : ni la
    // page, ni la V★, ni le label. Il n'a pas de plume — seulement une voix.
    //
    //   propositions      firstIdx → le mot proposé (le sommet de la liste)
    //   propositionsListe firstIdx → la liste FERMÉE (pour les puces de navigation)
    //
    // Elles se demandent UNE fois par page chargée, en arrière-plan (jamais sur
    // le fil d'interface) ; une ratification retire la sienne, la sentinelle
    // garde le nominal, et le pont n'en saura rien tant qu'on ne le lui dira pas.
    // ═══════════════════════════════════════════════════════════════════
    val propositions = mutableMapOf<Int, String>()
    val propositionsListe = mutableMapOf<Int, List<String>>()

    /** Demande au pont ses propositions pour la page courante. */
    fun demanderPropositions(onDone: () -> Unit = {}) {
        val uuid = parnasseBlockUuid ?: return
        val groupes = JSONArray()
        for ((firstIdx, label) in groupLabels) {
            if (label.isBlank()) continue
            groupes.put(JSONObject().apply {
                put("id", firstIdx.toString())
                put("label", label)
                put("rang", firstIdx)
            })
        }
        if (groupes.length() == 0) return
        val corps = JSONObject().apply {
            put("block_id", uuid)
            parnasseNoteId?.let { if (it.isNotEmpty()) put("note_id", it) }
            put("groupes", groupes)
        }.toString()

        Log.i(TAG, "▸ Correcteur: demande de propositions — ${groupes.length()} groupes")
        Thread {
            try {
                val conn = URL("$coeurUrl/api/correcteur/proposer").openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 20000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(corps.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val json = JSONObject(BufferedReader(InputStreamReader(conn.inputStream)).readText())
                    val props = json.optJSONArray("propositions") ?: JSONArray()
                    var n = 0
                    for (i in 0 until props.length()) {
                        val p = props.getJSONObject(i)
                        if (p.optBoolean("silence", false)) continue
                        val corrige = p.optString("corrige")
                        if (corrige.isEmpty()) continue
                        val idx = p.optString("groupe").toIntOrNull() ?: continue
                        val liste = mutableListOf<String>()
                        p.optJSONArray("candidats")?.let { cs ->
                            for (k in 0 until cs.length()) liste.add(cs.getJSONObject(k).optString("mot"))
                        }
                        propositions[idx] = corrige
                        propositionsListe[idx] = liste.ifEmpty { listOf(corrige) }
                        n++
                    }
                    val voix = json.optJSONObject("moteur")?.optString("mode") ?: "?"
                    Log.i(TAG, "▸ Correcteur: $n propositions pour la page ($voix)")
                } else {
                    Log.w(TAG, "▸ Correcteur: réponse ${conn.responseCode}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "▸ Correcteur: ${e.javaClass.simpleName}: ${e.message}")
            }
            uiHandler.post { onDone() }
        }.start()
    }
    val groupAnchor = mutableMapOf<Int, Pair<Float, Float>>()
    val groupBlobs = mutableMapOf<String, BlobData>()
    val inferredGroupFirstIdxs = mutableSetOf<Int>()

    // ── Template ───────────────────────────────────────────────────────
    var template: Template = Template.HorizontalStaff(spacingPx = 120f)
    var cachedTemplateLines: List<Float> = emptyList()
    var cachedTemplateHeight: Int = -1

    // ── Rendu ──────────────────────────────────────────────────────────
    var bitmap: Bitmap? = null
    var bitmapCanvas: Canvas? = null
    var backgroundBitmap: Bitmap? = null  // fond (screenshot Flutter)

    // ── MDM ────────────────────────────────────────────────────────────
    var lastMdmApplied: Long = 0
    val generatedStrokes = mutableMapOf<String, List<Triple<Float, Float, Int>>>()

    // ═══════════════════════════════════════════════════════════════════
    // GROUPES
    // ═══════════════════════════════════════════════════════════════════

    fun initGroupManager(context: Context) {
        Log.i(TAG, "initGroupManager: appelé, blockDir=${blockDir?.absolutePath ?: "NULL"}")
        appContext = context.applicationContext
        // ═══ Charger les paramètres de calibration (blob, espacement) ═══
        val calX = CalibrationActivity.getSpatialDistanceX(context)
        val calY = CalibrationActivity.getSpatialDistanceY(context)
        groupManager = GroupManager({}).also {
            it.params = it.params.copy(
                spatialDistancePx = calX,
                spatialDistanceY = calY,
                transcriptionTimeoutMs = Long.MAX_VALUE
            )
            it.pointProvider = { strokeId ->
                inkStrokeIdToRegistryIndex[strokeId]
                    ?.let { strokeRegistry.getOrNull(it)?.points ?: emptyList() }
                    ?: emptyList()
            }
            val tmpDir = File(context.filesDir, "groups"); tmpDir.mkdirs()
            it.persistence = GroupPersistence(File(tmpDir, "current.groups"))
            // ═══ Archivage des strokes quand un groupe passe LOADED→STORED ═══
            // « Déjà rastérisé dans le bitmap » — le drapeau DIT que l'encre est
            // dans le bitmap, et redrawBitmapInternal() (INCR) ne redessine jamais
            // un archivé : il faut donc que ce soit VRAI au moment où il se lève.
            // ⚠️ MARÉE 12/09 — sans ce geste, l'encre meurt à la première bascule
            // qui remplace ou efface le bitmap (effigie PNG de la matrice,
            // clearPage, scrub) : « les strokes ne s'affichent pas ». On rastérise
            // d'abord, on archive ensuite — dans le même souffle, sur le fil de l'UI.
            it.onGroupEvicted = { group ->
                uiHandler.post {
                    redrawBitmapInternal()   // rastériser AVANT de lever le drapeau
                    for (sid in group.strokeIds) {
                        val ri = inkStrokeIdToRegistryIndex[sid] ?: continue
                        strokeRegistry.getOrNull(ri)?.isArchived = true
                    }
                }
                pageDirty = true  // ⛪ MARÉE 30/08 — les états changent : le save écrira.
            }
        }
    }

    /** Recharge les paramètres de calibration (blob, template) depuis SharedPreferences.
     *  Appelé dans onResume() pour propager les changements faits dans CalibrationActivity. */
    fun applyCalibrationParams(context: Context) {
        val gm = groupManager ?: return
        val calX = CalibrationActivity.getSpatialDistanceX(context)
        val calY = CalibrationActivity.getSpatialDistanceY(context)
        gm.params = gm.params.copy(spatialDistancePx = calX, spatialDistanceY = calY)
        updateTemplateSpacing(context, context.resources.displayMetrics.heightPixels)
        Log.d(TAG, "Calibration appliquée: blobRx=$calX blobRy=$calY")
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAPTURE DE STROKE (utilise par CaptureSurface)
    // ═══════════════════════════════════════════════════════════════════

    fun beginStroke(x: Float, y: Float, pressure: Float = 1.0f) {
        currentPath.reset()
        currentPath.moveTo(x, y)
        currentStrokeRecord = StrokeRecord(id = java.util.UUID.randomUUID().toString()).also { sr ->
            sr.points.add(Pair(x, y))
            sr.timestamps.add(System.currentTimeMillis())
            sr.pressures.add(pressure.coerceIn(0f, 1f))
        }
    }

    fun addStrokePoint(x: Float, y: Float, pressure: Float = 1.0f) {
        val sr = currentStrokeRecord ?: return
        currentPath.lineTo(x, y)
        sr.points.add(Pair(x, y))
        sr.timestamps.add(System.currentTimeMillis())
        sr.pressures.add(pressure.coerceIn(0f, 1f))
    }

    /** Finalise le stroke en cours, le rastérise, l'ajoute au registre
     *  et le soumet au GroupManager.
     *  @return l'index dans strokeRegistry, ou -1 si pas de stroke. */
    fun endStroke(): Int {
        val sr = currentStrokeRecord
        currentStrokeRecord = null
        if (sr == null || sr.points.isEmpty()) { currentPath.reset(); return -1 }

        // Ignorer les taps (1 seul point sans mouvement)
        if (sr.points.size < 2) { currentPath.reset(); return -1 }

        // Rasteriser dans le bitmap engine
        val canvas = bitmapCanvas
        if (canvas != null) {
            val paint = Paint().apply {
                color = Color.BLACK; strokeWidth = 3f; style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; isAntiAlias = true
            }
            canvas.drawPath(currentPath, paint)
        }
        currentPath.reset()

        // Ajouter au registre
        strokeRegistry.add(sr)
        pageDirty = true  // ⛪ MARÉE 30/08 — la page a bougé : le save écrira.
        val ri = strokeRegistry.size - 1
        val inkId = ++inkStrokeIdCounter
        inkStrokeIdToRegistryIndex[inkId] = ri

        // Soumettre au GroupManager (groupement spatial)
        onStrokeSealed(sr, inkId)

        Log.d(TAG, "Stroke #$ri termine: ${sr.points.size} points, inkId=$inkId")
        return ri
    }

    /** Convertit un StrokeRecord en InkStroke et le soumet au GroupManager.
     *  @return le groupe affecte, ou null. */
    fun onStrokeSealed(sr: StrokeRecord, inkId: Long): InkGroup? {
        val gm = groupManager ?: return null

        // ═══ Premier stroke d'une page blanche → déclarer la page au monde ═══
        // La page existe déjà sur le disque (créée par le Miroir à l'ouverture),
        // mais elle n'a pas encore de données → c'est le moment de l'exporter.
        val isFirstLive = strokeRegistry.none { !it.isDeleted && it.points.isNotEmpty() }

        val inkStroke = strokeRecordToInkStroke(sr, inkId)
        val group = gm.onStrokeSealed(inkStroke)

        if (isFirstLive) {
            exportCurrentPage()
        }
        // Creer/mettre a jour le blob du groupe
        if (group != null) {
            val blob = computeBlobPath(group)
            if (blob != null) {
                groupBlobs[group.id] = blob
                Log.d(TAG, "Blob cree pour groupe ${group.id.take(8)} — ${group.strokeIds.size} strokes")
            }
            // ═══ Absorption d'un groupe existant → forcer la ré-inférence ═══
            if (group.strokeIds.size > 1) {
                val firstIdx = group.strokeIds.firstOrNull()
                    ?.let { inkStrokeIdToRegistryIndex[it] }
                if (firstIdx != null) relecture.retirer(firstIdx)
            }
        }
        return group
    }

    /** Calcule le blob (zone d'absorption elliptique) d'un groupe. */
    fun computeBlobPath(group: InkGroup, ctx: android.content.Context? = null): BlobData? {
        val gm = groupManager ?: return null
        val rx = gm.params.spatialDistancePx
        val ry = gm.params.spatialDistanceY
        if (rx <= 0f && ry <= 0f) return null
        if (group.strokeIds.isEmpty()) return null

        val pts = mutableListOf<Pair<Float, Float>>()
        for (sid in group.strokeIds) {
            val idx = inkStrokeIdToRegistryIndex[sid] ?: continue
            val sr = strokeRegistry.getOrNull(idx) ?: continue
            for ((x, y) in sr.points) pts.add(Pair(x, y))
        }
        if (pts.size < 2) return null

        var cx = 0f; var cy = 0f
        for ((px, py) in pts) { cx += px; cy += py }
        cx /= pts.size; cy /= pts.size

        val context = ctx ?: appContext
        val rayCount = if (context != null) {
            try { CalibrationActivity.getBlobRayCount(context) } catch (_: Exception) { 16 }
        } else {
            16  // valeur par defaut (16 rayons)
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        val path = Path()
        var first = true

        for (i in 0 until rayCount) {
            val angle = 2.0 * Math.PI * i / rayCount
            val dx = Math.cos(angle).toFloat()
            val dy = Math.sin(angle).toFloat()
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
        return BlobData(path, RectF(minX, minY, maxX, maxY))
    }

    /** Reconstruit tous les blobs visuels (après chargement). */
    fun rebuildAllBlobs() {
        val gm = groupManager ?: return
        for (g in gm.allGroupsFull()) {
            computeBlobPath(g)?.let { groupBlobs[g.id] = it }
        }
        Log.i(TAG, "rebuildAllBlobs: ${groupBlobs.size} blobs reconstruits")
    }

    /** Redessine les strokes dans le bitmap interne.
     *  @param fullRedraw si true, efface tout et redessine TOUS les strokes (chargement).
     *                    si false (défaut), préserve les strokes archivés (déjà dans le bitmap),
     *                    efface seulement les supprimés, et redessine les actifs. */
    fun redrawBitmapInternal(fullRedraw: Boolean = false) {
        val canvas = bitmapCanvas ?: return
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK; strokeWidth = 3f
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = false  // EPD : pas d'anti-aliasing (coûteux, inutile sur e-ink)
        }
        val erasePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE; strokeWidth = 4f
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = false
        }

        if (fullRedraw) {
            // Chargement : effacer tout, redessiner tout (archivés inclus)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            var drawn = 0
            for (sr in strokeRegistry) {
                if (sr.isDeleted || sr.points.size < 2) continue
                val path = android.graphics.Path()
                path.moveTo(sr.points[0].first, sr.points[0].second)
                for (i in 1 until sr.points.size) path.lineTo(sr.points[i].first, sr.points[i].second)
                canvas.drawPath(path, paint)
                drawn++
            }
            Log.d(TAG, "redrawBitmap FULL: $drawn strokes redessinés")
        } else {
            // Incrémental : effacer les strokes supprimés, redessiner les actifs
            // Les strokes archivés restent dans le bitmap (non touchés)
            var erased = 0; var drawn = 0
            for (sr in strokeRegistry) {
                if (sr.isDeleted && sr.points.size >= 2) {
                    val path = android.graphics.Path()
                    path.moveTo(sr.points[0].first, sr.points[0].second)
                    for (i in 1 until sr.points.size) path.lineTo(sr.points[i].first, sr.points[i].second)
                    canvas.drawPath(path, erasePaint)
                    erased++
                }
            }
            for (sr in strokeRegistry) {
                if (sr.isDeleted || sr.isArchived || sr.points.size < 2) continue
                val path = android.graphics.Path()
                path.moveTo(sr.points[0].first, sr.points[0].second)
                for (i in 1 until sr.points.size) path.lineTo(sr.points[i].first, sr.points[i].second)
                canvas.drawPath(path, paint)
                drawn++
            }
            if (drawn > 0 || erased > 0) Log.d(TAG, "redrawBitmap INCR: $drawn dessinés, $erased effacés — ${strokeRegistry.size} total")
        }
    }

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
    // BLOCS
    // ═══════════════════════════════════════════════════════════════════

    fun ensureBlockDir(context: Context, appName: String, ts: Long): File {
        val blocksDir = File(context.filesDir, "blocks"); blocksDir.mkdirs()
        val dir = File(blocksDir, "${appName}_$ts"); dir.mkdirs()
        blockDir = dir
        Log.i(TAG, "Bloc: ${dir.name}")
        return dir
    }

    fun openBlockDir(context: Context, blockId: String): File {
        val blocksDir = File(context.filesDir, "blocks"); blocksDir.mkdirs()
        val dir = File(blocksDir, blockId)
        if (!dir.exists()) dir.mkdirs()
        blockDir = dir
        currentPageIndex = 0
        Log.i(TAG, "Bloc ouvert: $blockId (pages=${countPages()})")
        return dir
    }

    fun closeBlock() {
        savePageFull()  // sauvegarde complète, pas la version minimale
        groupManager?.clearAll()
        groupBlobs.clear()
        strokeRegistry.clear()
        inkStrokeIdToRegistryIndex.clear()
        relecture.oublier()
        groupAnchor.clear()
        blockDir = null
    }

    /** Liste les blocs disponibles dans files/blocks/.
     *  @return liste de BlockInfo (id, nom, nombre de pages, dernière modification). */
    fun listBlocks(context: Context): List<BlockInfo> {
        val blocksDir = File(context.filesDir, "blocks")
        if (!blocksDir.exists()) return emptyList()
        return blocksDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { dir ->
                val pages = dir.listFiles()?.count { f -> f.isDirectory && f.name.startsWith("page_") } ?: 0
                BlockInfo(
                    id = dir.name,
                    pages = pages,
                    lastModified = dir.lastModified()
                )
            }
            ?.sortedByDescending { it.lastModified }
            ?: emptyList()
    }

    /** Interroge le Cœur pour obtenir la configuration Miroir
     *  (liste de toutes les bibliothèques avec leur étagère Miroir).
     *  @param coeurUrl URL du Cœur (ex: \"http://127.0.0.1:8008\")
     *  @return liste de LibraryMiroirInfo, ou liste vide si injoignable. */
    fun fetchParnasseConfig(coeurUrl: String): List<LibraryMiroirInfo> {
        try {
            val url = URL("$coeurUrl/api/miroir/config")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()
            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()
            val json = JSONObject(body)
            val arr = json.optJSONArray("libraries") ?: return emptyList()
            val result = mutableListOf<LibraryMiroirInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(LibraryMiroirInfo(
                    libraryId = obj.getString("library_id"),
                    libraryName = obj.getString("library_name"),
                    shelfId = obj.getString("shelf_id"),
                    shelfTitle = obj.getString("shelf_title")
                ))
            }
            return result
        } catch (e: Exception) {
            Log.w(TAG, "fetchParnasseConfig: ${e.message}")
            return emptyList()
        }
    }

    /** Interroge le Cœur pour obtenir la liste des blocs Parnasse
     *  dans l'étagère Miroir Standalone.
     *  @param coeurUrl URL du Cœur (ex: \"http://127.0.0.1:8008\")
     *  @param libraryId optionnel — UUID de la bibliothèque cible
     *  @return liste de ParnasseBlocInfo, ou liste vide si injoignable. */
    fun fetchParnasseBlocs(coeurUrl: String, libraryId: String? = null): List<ParnasseBlocInfo> {
        try {
            val urlStr = if (libraryId != null) {
                "$coeurUrl/api/miroir/blocs?library_id=$libraryId"
            } else {
                "$coeurUrl/api/miroir/blocs"
            }
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return emptyList()
            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            conn.disconnect()
            val json = JSONObject(body)
            val arr = json.optJSONArray("blocs") ?: return emptyList()
            val result = mutableListOf<ParnasseBlocInfo>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(ParnasseBlocInfo(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    nbNotes = obj.optInt("nb_notes", 0),
                    mirrorName = obj.optString("mirror_name", "")
                ))
            }
            return result
        } catch (e: Exception) {
            Log.w(TAG, "fetchParnasseBlocs: ${e.message}")
            return emptyList()
        }
    }

    /** Interroge le Cœur : « qu'y a-t-il à la page N de ce bloc ? » (mode PARNASSE).
     *  Parnasse répond le nombre de pages (total_notes) et l'identité de la note
     *  (note_id) — ou null si la position est vide. Le Miroir ne calcule plus rien.
     *  ⚠️ HTTP sur thread secondaire (NetworkOnMainThreadException sinon), synchronisé
     *  par CountDownLatch — même pattern que resolveMirrorBlockName. */
    fun queryPageParnasse(pageN: Int, navDelta: Int = 0, onDone: () -> Unit = {}): Boolean {
        val uuid = parnasseBlockUuid ?: return false
        val seq = ++requestSeq  // rang de cette requête — la garde la jette si périmée
        // L'identité d'abord : le Cœur résout par note_id (et calcule le voisin
        // pour le geste de navigation). La position n'est qu'une réponse.
        val hasId = !parnasseNoteId.isNullOrEmpty()
        val urlStr = when {
            hasId && navDelta != 0 ->
                "$coeurUrl/api/miroir/page?block_id=$uuid&note_id=$parnasseNoteId" +
                    "&nav=$navDelta"
            hasId -> "$coeurUrl/api/miroir/page?block_id=$uuid&note_id=$parnasseNoteId"
            else -> "$coeurUrl/api/miroir/page?block_id=$uuid&page_n=$pageN"
        }
        // 🧭 MARÉE 30/08 — trace de la requête (la course d'identité : deux
        // requêtes en vol au démarrage — voir laquelle porte l'identité).
        Log.i(TAG, "▸ req[$seq] hasId=$hasId nid=$parnasseNoteId → $urlStr")
        // ═══ ASYNCHRONE : la réponse revient sur le UI thread — le thread
        // d'interface n'est jamais gelé (ANR). onDone: la suite du chargement. ═══
        Thread {
            var okF = false
            var totalF = 0
            var noteIdF: String? = null
            var posF = pageN
            var pageNumF = -1
            var captureF: String? = null
            var updatedF = 0L
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
                    val json = JSONObject(body)
                    totalF = json.optInt("total_notes", 0)
                    noteIdF = json.optString("note_id").ifEmpty { null }
                    posF = json.optInt("page_n", pageN)  // position de lecture
                    pageNumF = json.optInt("page_number", -1)  // ⚠️ le numéro de la note
                    captureF = json.optString("capture").ifEmpty { null }
                    updatedF = json.optLong("updated_date", 0)
                    okF = true
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "queryPageParnasse: ${e.javaClass.simpleName}: ${e.message}")
            }
            val ok = okF
            val total = totalF
            val noteId = noteIdF
            val pos = posF
            val pageNum = pageNumF
            val capture = captureF
            val updated = updatedF
            uiHandler.post {
                if (seq != requestSeq) {
                    Log.w(TAG, "queryPageParnasse: réponse périmée rang=$seq (dernier=$requestSeq) jetée — état et rendu non touchés")
                    return@post
                }
                if (ok) {
                    parnasseTotal = total
                    parnasseNoteId = noteId
                    parnassePageNumber = pageNum
                    parnasseCapture = capture
                    parnasseUpdated = updated
                    // ⚠️ La position de lecture est dictée par le Cœur — jamais calculée.
                    currentPageIndex = pos
                    Log.i(TAG, "queryPageParnasse: page=$pos num=$pageNum total=$parnasseTotal note_id=$parnasseNoteId" +
                        (if (hasId && navDelta != 0) " nav=$navDelta" else "") +
                        (if (capture != null) " capture=${capture.take(30)}" else ""))
                }
                onDone()
            }
        }.start()
        return true
    }

    /** Hook de rendu (29/08/2026) — l'Activity l'allume pour invalider la vue
     *  après un rendu asynchrone (la LECTURE : le PNG de la matrice arrive). */
    var onPageRendered: (() -> Unit)? = null

    /** ═══ LA LECTURE (29/08/2026) — VOIE PNG ═══
     *  La fenêtre n'a pas la matière LOCALE (jamais écrite dans ce tiroir) ;
     *  la MATRICE la porte (la capture du canal, le PNG de la page).
     *  Le viewport affiche ce que la matrice rend : téléchargement de
     *  /api/files/<capture>?v=<updated> — le PNG devient le fond de la vue.
     *  Le premier trait transformera la page en page d'édition (naît locale). */
    private fun loadLectureBitmap() {
        val cap = parnasseCapture ?: return
        val upd = parnasseUpdated
        // ⚠️ MARÉE 30/08 — L'ÉCHO PÉRIMÉ : la LECTURE est asynchrone ; si une
        // autre navigation a eu lieu pendant le téléchargement, le PNG
        // revient poser le bitmap de l'ANCIENNE page par-dessus l'encre de
        // la nouvelle (« les strokes disparaissaient après la rafale » —
        // vu 30/08 : page 468 → 465, l'écho de 468 effaçait le poème). Même
        // garde que les réponses : le rang de la requête qui a lancé la LECTURE.
        val seqAtLaunch = requestSeq
        Thread {
            try {
                val urlStr = "$coeurUrl/api/files/$cap" + (if (upd > 0) "?v=$upd" else "")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                conn.disconnect()
                uiHandler.post {
                    if (seqAtLaunch != requestSeq) {
                        Log.w(TAG, "🧭 LECTURE périmée (rang $seqAtLaunch, dernier $requestSeq) — bitmap non posé")
                        return@post
                    }
                    if (bmp != null) {
                        // ⚠️ IMMUTABLE (29/08/2026) : decodeStream rend un bitmap
                        // immuable — eraseColor/drawColor lèvent
                        // « cannot erase immutable bitmaps » (crash clearPage).
                        // La LECTURE pose une copie MUTABLE — la page vit encore.
                        bitmap = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        // ═══ LE PINCEAU SUIT LE BITMAP (MARÉE 12/09) ═══
                        // bitmapCanvas restait posé sur l'ANCIEN bitmap : dès qu'une
                        // effigie se posait, tous les redraws partaient dans l'orphelin
                        // et l'écran (qui affiche le NOUVEAU bitmap) ne montrait plus
                        // jamais l'encre — ni celle qu'on chargeait, ni celle qu'on
                        // écrivait. Seules les étiquettes, dessinées en direct sur la
                        // toile de la vue, restaient visibles. On repose le pinceau.
                        bitmapCanvas = Canvas(bitmap!!)
                        redrawBitmapInternal()
                        Log.i(TAG, "🧭 MATRICE: effigie affichée (${cap.take(30)}… v=$upd)")
                    } else {
                        Log.w(TAG, "🧭 LECTURE: décodage null pour $cap")
                        clearPage()
                        redrawBitmapInternal()
                    }
                    onPageRendered?.invoke()
                }
            } catch (e: Exception) {
                Log.w(TAG, "🧭 LECTURE: ${e.javaClass.simpleName}: ${e.message}")
                uiHandler.post {
                    if (seqAtLaunch != requestSeq) return@post
                    clearPage()
                    redrawBitmapInternal()
                    onPageRendered?.invoke()
                }
            }
        }.start()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SERRE-LIVRES
    // ═══════════════════════════════════════════════════════════════════

    private val serreLivresFile = "serre-livres.json"

    fun loadSerreLivres(context: Context): SerreLivresData {
        try {
            val f = File(context.filesDir, serreLivresFile)
            if (!f.exists()) return SerreLivresData()
            return SerreLivresData() // TODO: JSON parsing with Gson or manual
            // Pour l'instant, retourne un objet vide — les serre-livres
            // seront persistés quand le parseur JSON sera ajouté.
        } catch (e: Exception) {
            Log.w(TAG, "loadSerreLivres: ${e.message}")
            return SerreLivresData()
        }
    }

    fun saveSerreLivres(context: Context, data: SerreLivresData) {
        try {
            val f = File(context.filesDir, serreLivresFile)
            // TODO: sérialiser en JSON
            f.writeText("{}") // placeholder
        } catch (e: Exception) {
            Log.w(TAG, "saveSerreLivres: ${e.message}")
        }
    }

    /** Change de bloc actif. Sauvegarde la page courante, ferme le bloc actuel,
     *  ouvre le nouveau, initialise le GroupManager, et charge la dernière page. */
    fun switchBlock(context: Context, blockId: String): Boolean {
        savePageFull()
        closeBlock()
        openBlockDir(context, blockId)
        initGroupManager(context)
        val total = countPages()
        if (total > 0) {
            currentPageIndex = total - 1
            return loadPageFull()
        }
        return true  // bloc vide, aucune page à charger
    }

    /** Nombre de dossiers page_N/ locaux — le sens ne change pas, quel que soit le mode.
     *  C'est la vérité du cache local (les fonctions de manipulation de dossiers s'y fient). */
    fun countPages(): Int =
        blockDir?.listFiles()?.count { it.isDirectory && it.name.startsWith("page_") } ?: 0

    /** Total de navigation : Parnasse dicte en mode PARNASSE, sinon le cache local. */
    fun navigationTotal(): Int =
        if (navMode == NavMode.PARNASSE) parnasseTotal else countPages()

    /** ⚓ MARÉE 11/09 — La marque haute du bloc : le plus grand id jamais écrit
     *  dans ce tiroir. Persistée par savePageFull dans .ci_max — l'alignement de
     *  l'identité se dérive du BLOC, jamais de la seule page chargée. */
    private fun readBlocCiWatermark(bd: File): Long {
        return try {
            val f = File(bd, ".ci_max")
            if (!f.exists()) 0L else (f.readText().trim().toLongOrNull() ?: 0L)
        } catch (_: Exception) { 0L }
    }

    /** ⚓ MARÉE 11/09 — Balayage borné : le max des captureIndices des groups.json
     *  du bloc (petits fichiers, jamais les V★). Rattrape les marques absentes
     *  (tiroirs antérieurs à .ci_max) sans lire une seule encre. */
    private fun scanBlocGroupsMaxCi(bd: File): Long {
        var maxCi = 0L
        try {
            val pages = bd.listFiles()?.filter { it.isDirectory && it.name.startsWith("page_") } ?: return 0L
            for (p in pages) {
                val g = File(p, "groups.json")
                if (!g.exists() || g.length() > 200_000) continue
                val arr = try { org.json.JSONObject(g.readText()).optJSONArray("groups") } catch (_: Exception) { null } ?: continue
                for (i in 0 until arr.length()) {
                    val ciArr = arr.getJSONObject(i).optJSONArray("captureIndices") ?: continue
                    for (j in 0 until ciArr.length()) {
                        val inkId = ciArr.getLong(j) + 1
                        if (inkId > maxCi) maxCi = inkId
                    }
                }
            }
        } catch (_: Exception) { }
        return maxCi
    }

    /** Lit le note_id Parnasse gravé dans le groups.json d'une page — les DEUX
     *  liens sont lus (json puis cap). ⚓ UNE SEULE MAIN (14/09) : la lecture vit
     *  dans IdentitePage, partagée avec l'IME — plus deux copies. */
    fun readPageNoteId(pageIndex: Int): String? {
        val bd = blockDir ?: return null
        return IdentitePage.lire(File(bd, "page_$pageIndex"))
    }

    /** ⚓ LA CONTRE-GRAVURE (marée 14/09) — « aucune capture sans identité ni place ».
     *  Quand une note n'a plus de maison dans le tiroir (lien mort, absent, effacé),
     *  la COPIE SD — le pont, écrite à chaque save — dit où elle habite. On regrave
     *  alors les DEUX liens internes (la garde des maisons habitées protège la matière).
     *  La boucle est fermée : la contrepartie visible (le dossier) se répare seule
     *  à chaque fois que le Miroir cherche où lire/écrire — zéro réseau. */
    fun healMaisonDepuisSD(noteId: String): Int {
        val bd = blockDir ?: return -1
        val sdRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "parnasse/miroir/${bd.name}")
        if (!sdRoot.exists()) return -1
        val pages = bd.listFiles()?.filter { it.isDirectory && it.name.startsWith("page_") } ?: return -1
        for (pageDir in pages) {
            val idx = pageDir.name.removePrefix("page_").toIntOrNull() ?: continue
            val sdCap = File(File(sdRoot, "page_$idx"), ".note_id")
            val dit = try { if (sdCap.exists()) sdCap.readText().trim() else null } catch (_: Exception) { null }
            if (!dit.isNullOrEmpty() && dit == noteId) {
                Log.i(TAG, "🧭 Contre-gravure: la copie SD dit que ${noteId.take(8)} habite page_$idx — les deux liens regravés")
                baptiserPage(idx, noteId)
                return idx
            }
        }
        return -1
    }

    /** Écrit l'adresse d'une note dans une page nommée (le geste du rituel, outillé). */
    fun alignerMaison(pageIndex: Int, noteId: String) {
        if (pageIndex < 0) return
        if (blockDir == null) return
        if (readPageNoteId(pageIndex) == noteId) return
        baptiserPage(pageIndex, noteId)
    }

    /** 🧭 SENTINELLE — lit la forme des résidences (les deux liens + la matière).
     *  N'expose que les faiblesses — ne corrige JAMAIS (le doute au Tas).
     *  Les trois respirations : bascule (savePageFull), fermeture d'app (onDestroy),
     *  sonnerie (findPageByNoteId = -1). Zéro réseau, zéro boucle, ~30 lignes. */
    fun sentinelAudit(reason: String) {
        val bd = blockDir ?: return
        val pages = bd.listFiles()?.filter { it.isDirectory && it.name.startsWith("page_") } ?: return
        var maisons = 0; var fantomes = 0; var divergences = 0
        for (dir in pages) {
            val idx = dir.name.removePrefix("page_").toIntOrNull() ?: continue
            maisons++
            val gObj = try {
                org.json.JSONObject(File(dir, "groups.json").takeIf { it.exists() }?.readText() ?: "{}")
            } catch (_: Exception) { org.json.JSONObject() }
            val lienJson = gObj.optString("note_id", null).takeUnless { it.isNullOrEmpty() }
            val lienFile = File(dir, ".note_id").takeIf { it.exists() }?.readText()?.trim()?.takeUnless { it.isEmpty() }
            val vstar = File(dir, "page.vstar").takeIf { it.exists() && it.length() > 0 }
            if (vstar != null && lienJson == null && lienFile == null) fantomes++   // matière sans nom
            if (lienJson != null && lienFile != null && lienJson != lienFile) divergences++ // les deux liens qui mentent
        }
        if (fantomes + divergences > 0)
            Log.w(TAG, "🧭 SENTINEL[$reason]: maisons=$maisons MATIÈRE-sans-maison=$fantomes liens-divergents=$divergences — le doute passe au Tas, jamais la poubelle")
        else if (maisons > 0)
            Log.i(TAG, "🧭 SENTINEL[$reason]: résidences saines ($maisons maisons)")
    }

    /** Retrouve le dossier local qui porte ce note_id — par l'identité, pas la position.
     *  Une note peut se déplacer (changer de page_number) sans perdre sa capture.
     *  ⚠️ Scanne les DOSSIERS RÉELS (page_4, page_5 — le tiroir peut n'avoir que les
     *  pages visitées, jamais la séquence 0..count-1 : les pages absentes manqueraient). */
    fun findPageByNoteId(noteId: String): Int {
        val bd = blockDir ?: return -1
        val pages = bd.listFiles()?.filter { it.isDirectory && it.name.startsWith("page_") }
            ?: return -1
        for (dir in pages) {
            val idx = dir.name.removePrefix("page_").toIntOrNull() ?: continue
            if (readPageNoteId(idx) == noteId) return idx
        }
        // 🧭 SENTINEL — la sonnerie de la brume : la note n'a pas de maison.
        // La MATRICE va parler à sa place. Le doute est noté, jamais corrigé.
        Log.w(TAG, "🧭 SENTINEL: note $noteId sans maison — MATRICE en héritage (les deux liens lus : groups.json + .note_id)")
        return -1
    }

    /** Où est la matière de la page courante ? Le sens est unique : « où lire/écrire ? »
     *  LOCAL    → currentPageIndex (le Miroir numérote ses dossiers).
     *  PARNASSE → retrouvé par note_id (l'identité) — jamais par la position,
     *             jamais par le NUMÉRO du titre (parnassePageNumber : les
     *             renumérotations du tiroir en ont fait un traître). */
    private fun pageDirIndex(): Int {
        if (navMode == NavMode.PARNASSE) {
            // ═══ L'IDENTITÉ d'abord : la dictée a nommé la note — chercher SA
            // matière. Le numéro (parnassePageNumber) n'est qu'un titre : les
            // trous et déchirures du tiroir (page_28..37 absentes, renumérotation)
            // l'ont rendu trompeur — le voisin s'affichait avec les labels d'avant.
            // L'identité ne bouge jamais : chaque dossier porte son note_id gravé.
            val nid = parnasseNoteId
            if (!nid.isNullOrEmpty()) {
                var idx = findPageByNoteId(nid)
                // ⚓ LA CONTRE-GRAVURE : si le tiroir a perdu la maison, la copie SD
                // (le pont) dit où la note habite — on regrave les deux liens, puis
                // on relit. La lecture ne part JAMAIS sur une page sans maison.
                if (idx < 0) idx = healMaisonDepuisSD(nid)
                if (idx >= 0) return idx
                // Note inconnue du tiroir (jamais visitée/écrite) → page vierge :
                // jamais le voisin, jamais le limbe. La matière naîtra à l'écriture
                // (savePageFull crée la maison à la position dictée).
                return -1
            }
            // ⚠️ Aucune note désignée par le Cœur : aucune page à charger — surtout
            // pas page_0 (poison hérité), ni le limbe page_-1.
            return -1
        }
        return currentPageIndex
    }

    /** ⛪ Baptise une page : grave le note_id Parnasse dans son groups.json.
     *  Les pages importées du standalone n'ont jamais été baptisées (pas de note_id).
     *  Le baptême fait de l'identité le fil de navigation, au lieu du numéro de page. */
    fun baptiserPage(pageIndex: Int, noteId: String) {
        if (pageIndex < 0) return  // pas de page désignée — pas de baptême à détourer
        val bd = blockDir ?: return
        val pageDir = File(bd, "page_$pageIndex")
        if (!pageDir.exists()) return  // page vierge — baptisée à sa création
        try {
            val deja = IdentitePage.lire(pageDir)
            // ⚠️ MARÉE 30/08 — la course d'identité : deux dictées en rafale
            // (bascule en rafale sur la même position) font changer la maison
            // de nom — la matière du poème précédent se lie alors à la mauvaise
            // note (coquillage : la note du matin affichant le poème du midi).
            // Une maison HABITÉE (un nom + de l'encre) reste au nom de sa
            // matière — jamais renommée par une visite.
            if (deja != null && deja.isNotEmpty() && deja != noteId) {
                val matiere = File(pageDir, "page.vstar")
                if (matiere.exists() && matiere.length() > 0) {
                    Log.w(TAG, "⛪ Page $pageIndex déjà habitée ($deja) — ne pas renommer en $noteId (matière présente)")
                    return
                }
            }
            // ═══ En PARNASSE, le Cœur est la vérité : le baptême répare les
            // notes polluées par l'ancienne résolution par position. ═══
            if (deja != null && deja.isNotEmpty() && deja != noteId && navMode != NavMode.PARNASSE) {
                Log.w(TAG, "⛪ Page $pageIndex déjà baptisée avec $deja — ne pas écraser par $noteId")
                return
            }
            // ⚓ UNE SEULE MAIN (14/09) : les DEUX liens se gravent ensemble.
            IdentitePage.graver(pageDir, noteId)
            Log.i(TAG, "⛪ Page $pageIndex baptisée: note_id=$noteId")
        } catch (e: Exception) {
            Log.w(TAG, "baptiserPage: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PAGES
    // ═══════════════════════════════════════════════════════════════════

    /** Insère une nouvelle page APRÈS la page courante (décale vers la droite).
     *  Si on est sur la dernière page, ajoute simplement à la fin. */
    fun newPage() {
        Log.i(TAG, "newPage: avant save, page=$currentPageIndex, blockDir=${blockDir?.absolutePath ?: "NULL"}")
        savePageFull()
        val bd = blockDir ?: return
        val total = countPages()
        if (currentPageIndex >= total - 1 || total == 0) {
            // Dernière page ou bloc vide → ajouter à la fin, pas de décalage
            clearPage()
            currentPageIndex = total
        } else {
            // Insérer après currentPageIndex → décaler les pages suivantes
            for (i in total - 1 downTo currentPageIndex + 1) {
                File(bd, "page_$i").renameTo(File(bd, "page_${i + 1}"))
            }
            clearPage()
            currentPageIndex = currentPageIndex + 1
        }
    }

    /** Insère une nouvelle page au DÉBUT du bloc (décale tout vers la droite). */
    fun newPageAtBeginning() {
        savePageFull()
        val bd = blockDir ?: return
        val total = countPages()
        for (i in total - 1 downTo 0) {
            File(bd, "page_$i").renameTo(File(bd, "page_${i + 1}"))
        }
        clearPage()
        currentPageIndex = 0
    }

    /** Ajoute une nouvelle page à la FIN du bloc (pas de décalage). */
    fun newPageAtEnd() {
        savePageFull()
        val total = countPages()
        clearPage()
        currentPageIndex = total
    }

    /** Crée une nouvelle page à la fin du bloc et la baptise immédiatement
     *  avec le note_id Parnasse. Retourne l'index de la page créée.
     *  Utilisé par CaptureActivity quand une note manuscrite est nouvelle
     *  (pas encore de page baptisée) : on ouvre une page vierge à la fin,
     *  pas la première page. */
    fun createPageAtEnd(noteId: String): Int {
        val bd = blockDir ?: return 0
        val newPage = countPages()
        val pageDir = File(bd, "page_$newPage")
        pageDir.mkdirs()
        try {
            val root = org.json.JSONObject()
            root.put("note_id", noteId)
            File(pageDir, "groups.json").writeText(root.toString())
            Log.i(TAG, "⛪ Nouvelle page $newPage créée et baptisée: note_id=$noteId")
        } catch (e: Exception) {
            Log.w(TAG, "createPageAtEnd: ${e.message}")
        }
        return newPage
    }

    /** Insère une nouvelle page à la position pageN (décale les suivantes vers la droite)
     *  et la baptise avec noteId. Si pageN >= countPages, append à la fin.
     *  Cinématique d'insertion au milieu : Parnasse indique la position, le Miroir reflète et décale. */
    fun insertPageAt(pageN: Int, noteId: String): Int {
        val bd = blockDir ?: return 0
        val total = countPages()
        val target = if (pageN >= total) total else pageN
        for (i in total - 1 downTo target) {
            File(bd, "page_$i").renameTo(File(bd, "page_${i + 1}"))
        }
        val pageDir = File(bd, "page_$target")
        pageDir.mkdirs()
        try {
            val root = org.json.JSONObject()
            root.put("note_id", noteId)
            File(pageDir, "groups.json").writeText(root.toString())
            Log.i(TAG, "⛪ Page $target insérée et baptisée: note_id=$noteId")
        } catch (e: Exception) {
            Log.w(TAG, "insertPageAt: ${e.message}")
        }
        return target
    }

    fun clearPage() {
        groupManager?.clearAll()
        groupBlobs.clear()
        strokeRegistry.clear()
        inkStrokeIdToRegistryIndex.clear()
        relecture.oublier()
        groupAnchor.clear()
        // Effacer le bitmap sans le détruire (reste utilisable pour redrawBitmapOnly)
        bitmap?.eraseColor(android.graphics.Color.WHITE)
        pageLoaded = false
    }

    fun goToPage(index: Int) {
        val total = countPages()
        if (total == 0 || index < 0 || index >= total) return
        savePage()
        currentPageIndex = index
        loadPage()
    }

    /** Navigation avec sauvegarde/chargement complets (standalone).
     *  Permissive : accepte tout index ≥ 0 et tout index négatif (carnet affillié).
     *  Le sens est unique : « va à la page N ». Le maître change selon le mode :
     *    LOCAL    → le Miroir lit ses dossiers page_N/ (page blanche si absent).
     *    PARNASSE → le Miroir interroge le Cœur (page vierge si note_id null). */
    fun goToPageFull(index: Int, navDelta: Int = 0, onSettled: (() -> Unit)? = null) {
        if (index < Int.MIN_VALUE) return  // seule garde : sécurité absurde
        savePageFull()
        // ═══ FERMETURE PROPRE — le cache est vidé SYNCHRONE, avant la requête ═══
        // Tout repaint intermédiaire (canal Onyx, fontaine, invalidates) ne peut plus
        // dessiner l'encre/labels de l'ancienne page : la toile est blanche et nue.
        clearPage()
        currentPageIndex = index
        // ═══ PARNASSE maître — RYTHME ASYNCHRONE (jamais de gel du UI thread) ═══
        if (navMode == NavMode.PARNASSE) {
            queryPageParnasse(index, navDelta) {
                if (parnasseNoteId == null) {
                    // Parnasse dit « vide » → page vierge, quelle que soit la cache locale.
                    clearPage()
                    redrawBitmapInternal()
                } else {
                    val loaded = loadPageFull()
                    if (!loaded) {
                        // ═══ LA LECTURE (29/08/2026) : la fenêtre n'a pas la matière
                        // LOCALE — la matrice la porte (la capture du canal). Le
                        // viewport affiche ce que la matrice rend — LA VOIE PNG.
                        if (!parnasseCapture.isNullOrEmpty()) loadLectureBitmap()
                        else clearPage()
                    }
                    redrawBitmapInternal()
                }
                onSettled?.invoke()
            }
            return
        }
        val loaded = loadPageFull()
        if (!loaded) {
            // Pas de dossier local → page blanche
            clearPage()
        }
        redrawBitmapInternal()  // synchroniser après chargement
        onSettled?.invoke()
    }

    // ═══════════════════════════════════════════════════════════════════
    // TEMPLATE
    // ═══════════════════════════════════════════════════════════════════

    fun updateTemplateSpacing(context: Context, canvasHeight: Int) {
        if (canvasHeight <= 0) return
        val spacing = CalibrationActivity.getTemplateSpacing(context)
        val sw = CalibrationActivity.getTemplateStrokeWidth(context)
        template = Template.HorizontalStaff(spacingPx = spacing)
        Template.GUIDE_PAINT.strokeWidth = sw
        val t = template
        if (t is Template.HorizontalStaff) {
            cachedTemplateLines = t.linePositions(canvasHeight)
        }
        cachedTemplateHeight = canvasHeight
    }

    fun snapToLine(y: Float): Float {
        if (cachedTemplateLines.isEmpty()) return y
        var upper = cachedTemplateLines.first(); var lower = cachedTemplateLines.last()
        for (line in cachedTemplateLines) {
            if (line <= y && line > upper) upper = line
            if (line >= y && line < lower) lower = line
        }
        if (upper == lower) return upper
        return if (y <= upper + (lower - upper) * 0.2f) upper else lower
    }

    // ═══════════════════════════════════════════════════════════════════
    // SAUVEGARDE / CHARGEMENT V★
    // ═══════════════════════════════════════════════════════════════════

    fun savePage() {
        val bd = blockDir ?: return
        val hasLive = strokeRegistry.any { !it.isDeleted && it.points.isNotEmpty() }
        val dir = File(bd, "page_$currentPageIndex")
        if (!hasLive) { dir.deleteRecursively(); return }
        dir.mkdirs()
        bitmap?.let {
            FileOutputStream(File(dir, "bitmap.png")).use { out -> it.compress(Bitmap.CompressFormat.PNG, 90, out) }
        }
        // V★ save — minimal pour l'instant
        val vstarFile = File(dir, "page.vstar"); vstarFile.delete()
        val doc = VStarDocumentV2(vstarFile); doc.open()
        val liveIndices = strokeRegistry.indices.filter { !strokeRegistry[it].isDeleted && it < strokeRegistry.size }.toList()
        for (ri in liveIndices) {
            val ci = doc.beginStroke()
            doc.endStroke(ci)
        }
        doc.flush(); doc.close()
        Log.i(TAG, "Page $currentPageIndex sauvegardée: ${liveIndices.size} strokes")
    }

    fun loadPage(): Boolean {
        val bd = blockDir ?: return false
        val dir = File(bd, "page_$currentPageIndex")
        if (!dir.exists()) return false
        groupManager?.clearAll()
        groupBlobs.clear(); strokeRegistry.clear(); inkStrokeIdToRegistryIndex.clear()
        relecture.oublier(); groupAnchor.clear()
        val vstarFile = File(dir, "page.vstar")
        if (!vstarFile.exists()) return false
        // V★ load — minimal
        val doc = VStarDocumentV2(vstarFile); doc.open()
        // Pour l'instant, on ne charge que la structure
        doc.close()
        Log.i(TAG, "Page $currentPageIndex chargée")
        return true
    }

    // ═══════════════════════════════════════════════════════════════════
    // SAUVEGARDE / CHARGEMENT COMPLET (standalone)
    // ═══════════════════════════════════════════════════════════════════

    /** Sauvegarde complete : V★ + bitmap + groupes + MDM. */
    fun savePageFull() {
        val bd = blockDir ?: return
        val idx = pageDirIndex()
        val dir: File
        if (idx < 0) {
            // ═══ ANTI-LIMBE (26/08/2026) — jamais page_-1 ═══
            // La page peut n'avoir aucun dossier local (note née du Parnasse,
            // numéro dicté par le Cœur) : pageDirIndex() renvoie -1 et la
            // sauvegarde partait dans page_-1 — le limbe — les strokes
            // « n'étaient pas conservés ». Si le Cœur a dicté un numéro,
            // on crée SA maison : les traits atterrissent à leur page.
            if (parnassePageNumber >= 0) {
                dir = File(bd, "page_${parnassePageNumber}").apply { mkdirs() }
                Log.i(TAG, "savePageFull: page_$idx inexistante → maison créée page_${parnassePageNumber}")
            } else {
                // ⚓ MARÉE 30/08 — la dictée est en retard (le Cœur traîne — le
                // sync absorbé) : la matière RESTE dans le registre, et la
                // fenêtre redemande la position (nav=0). Le prochain save
                // écrira à la bonne maison — jamais de refus absolu.
                Log.w(TAG, "savePageFull: numéro non dicté — redemande la position (nav=0), matière gardée en mémoire")
                if (navMode == NavMode.PARNASSE && parnasseNoteId != null) {
                    queryPageParnasse(currentPageIndex, 0)
                }
                return
            }
        } else {
            dir = File(bd, "page_$idx")
            dir.mkdirs()
        }
        val vstarFile = File(dir, "page.vstar")
        // ⚓ MARÉE 30/08 — SAUVE CONDITIONNEL : la page n'a pas bougé (ni trait,
        // ni archive, ni effacement) → le disque parle déjà — ne pas réécrire
        // le vstar à chaque battement (390 Ko pour un battement d'horloge).
        if (!pageDirty && vstarFile.exists()) return
        // ═══ Inclure TOUS les strokes non-supprimés (vivants + archivés) ═══
        // Les strokes archivés sont déjà dans le bitmap mais doivent persister
        // dans le .vstar pour le rechargement futur.
        val allStrokes = strokeRegistry.count { !it.isDeleted && it.points.isNotEmpty() }

        // ── V★ : reecriture propre avec tous les strokes (archivés inclus) ──
        // 🛡️ ÉCRITURE ATOMIQUE (19/09/2026) — la leçon de la tuerie du nettoyeur :
        //   `delete()` puis écriture laissait un fichier COURT quand l'app mourait
        //   en route (578 Ko écrits sur ~1,24 Mo → 211 strokes perdus, aucune copie
        //   nulle part). Le plein ne doit jamais céder la place avant que le neuf
        //   soit complet. On écrit donc dans `page.vstar.neuf`, et la page ne
        //   change de main que par un RENOMMAGE — atomique sur le même volume.
        //   Une interruption ne peut plus détruire : au pire le neuf reste à côté.
        if (allStrokes > 0) {
            val neufFile = File(dir, "page.vstar.neuf")
            if (neufFile.exists()) neufFile.delete()
            val dataRegion = VStarDataRegion(neufFile)
            dataRegion.open()
            val allIndices = strokeRegistry.indices
                .filter { !strokeRegistry[it].isDeleted && strokeRegistry[it].points.isNotEmpty() }
                .toList()
            for (ri in allIndices) {
                val sr = strokeRegistry[ri]
                val inkId = inkStrokeIdToRegistryIndex.entries.firstOrNull { it.value == ri }?.key
                val ci = inkId?.let { (it - 1).toShort() } ?: continue
                val tokens = strokeRecordToTokensV2(sr, ci)
                for (t in tokens) dataRegion.append(t)
            }
            dataRegion.close()
            // Le neuf est écrit en entier : la page peut céder la place.
            // La précédente reste comme TÉMOIN (`page.vstar.bak`) jusqu'à la
            // prochaine écriture — jamais plus d'une génération de retard.
            var remplacee = false
            if (neufFile.length() > 0) {
                val bakFile = File(dir, "page.vstar.bak")
                try {
                    if (vstarFile.exists()) {
                        if (bakFile.exists()) bakFile.delete()
                        remplacee = vstarFile.renameTo(bakFile)
                    } else {
                        remplacee = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "savePageFull: témoin .bak impossible: ${e.message}")
                }
                if (neufFile.renameTo(vstarFile)) {
                    Log.i(TAG, "savePageFull page=$currentPageIndex vstar atomique: ${vstarFile.length()}B" +
                            " (témoin ${if (remplacee) "gardé" else "absent"})")
                } else {
                    Log.w(TAG, "savePageFull page=$currentPageIndex: renommage du neuf REFUSÉ — la page précédente est intacte")
                }
            }
            // ⚓ MARÉE 11/09 — LA MARQUE HAUTE DU BLOC : elle survit aux sessions.
            // L'alignement par page seul ramenait le compteur sous la plage brûlée
            // du tiroir ; la marque ne redescend jamais.
            try {
                val f = File(bd, ".ci_max")
                val ecrit = inkStrokeIdToRegistryIndex.keys.maxOrNull() ?: 0L
                val ancienne = if (f.exists()) (f.readText().trim().toLongOrNull() ?: 0L) else 0L
                if (ecrit > ancienne) f.writeText(ecrit.toString())
            } catch (_: Exception) { }
            Log.i(TAG, "savePageFull page=$currentPageIndex vstar=${vstarFile.length()}B strokes=$allStrokes")
        } else if (vstarFile.exists() && !pageLoaded) {
            // ⚠️ GARDIEN DE L'ŒUVRE (28/08) — le registre est vide parce que la
            // page courante n'a PAS encore été chargée (process neuf, ouverture
            // avant la requête). Le vstar existant est l'écriture des sessions
            // précédentes — le détruire effacerait l'encre (« strokes non
            // enregistrés » — vu 28/08 : poème 61 Ko réduit à 1792 B).
            Log.i(TAG, "savePageFull page=$currentPageIndex — registre vide avant chargement, vstar intact")
        } else {
            if (vstarFile.exists()) { vstarFile.delete() }
            Log.i(TAG, "savePageFull page=$currentPageIndex — page vide")
        }

        // ── Bitmap PNG ──
        // ═══ L'EXPORT EST UNE DÉRIVÉE DU REGISTRE (MARÉE 12/09) : le PNG écrit
        // doit porter TOUTE l'encre — pas ce que le cache a bien voulu garder
        // (l'INCR saute les archivés ; un bitmap recyclé entre-temps est blanc).
        redrawBitmapInternal(fullRedraw = true)  // synchroniser avant sauvegarde
        bitmap?.let {
            FileOutputStream(File(dir, "bitmap.png")).use { out ->
                it.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }

        // ── Groupes & labels ──
        // ═══ ÉVICTION avant sauvegarde : force LOADED→STORED ═══
        // Les groupes inactifs passent en STORED pour que groups.json
        // reflète l'état réel (pas tous LOADED). Les blobs, labels et
        // bitmap restent intacts — l'éviction ne touche qu'au cache RAM.
        groupManager?.evictInactive()
        saveGroupsJson(dir)

        // ── MDM ──
        savePageMdm(dir)

        // ═══ BAPTÊME en PARNASSE : grave note_id dans groups.json local. ═══
        // Le lien capture↔note devient l'identité (note_id), pas la position.
        // findPageByNoteId retrouve la matière par identité, même si la note
        // se déplace (renumérotation Parnasse).
        if (navMode == NavMode.PARNASSE && parnasseNoteId != null) {
            // ⛪ MARÉE 30/08 — la maison vient de naître à la position dictée :
            // pageDirIndex() vaut encore -1 (l'identité ne s'y est pas encore
            // gravée), le baptême tombait dans le limbe et la page restait
            // sans nom — findPageByNoteId ne la retrouvait jamais.
            val maison = if (idx < 0) parnassePageNumber else idx
            baptiserPage(maison, parnasseNoteId!!)
        }

        // ── Miroir sdcard — copie accessible au Scanner/Cœur ──
        mirrorToSdcard(dir, bd.name)
        // 🧭 SENTINEL — la bascule : la forme vient d'être couchée, l'audit le dit.
        sentinelAudit("bascule")
        pageDirty = false  // ⚓ MARÉE 30/08 — le disque parle comme la mémoire
    }

    /** Exporte la page courante vers la SD card — appelé à chaque mot reconnu.
     *  Les fichiers sont déjà sur disque (.vstar, groups.json, page.mdm).
     *  On ne fait que les déclarer au monde extérieur. */
    fun exportCurrentPage() {
        val bd = blockDir ?: return
        // ⛪ MARÉE 30/08 — la page exportée est celle de l'IDENTITÉ (la maison :
        // position dictée), jamais la position affichée qui n'est qu'un titre.
        val idx = pageDirIndex()
        val dir = if (idx >= 0) File(bd, "page_$idx") else File(bd, "page_$parnassePageNumber")
        if (!dir.exists()) return
        Log.i(TAG, "exportCurrentPage: $dir → SD card")
        mirrorToSdcard(dir, bd.name)
    }

    /** Copie miroir de la page sauvegardée vers le stockage externe
     *  pour que le Scanner et le Cœur puissent la lire sans sandboxing. */
    internal fun mirrorToSdcard(pageDir: File, blockName: String) {
        // ⛪ MARÉE 30/08 — le reflet est le jumeau de la maison : il porte SA
        // adresse (le nom du dossier), jamais une position parallèle. Côté
        // Cœur, l'identité se lit à l'adresse de la maison — et la maison,
        // elle, est baptisée par savePageFull.
        val pageN = pageDir.name.removePrefix("page_").toIntOrNull() ?: return
        try {
            val mirrorDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "parnasse/miroir/$blockName/page_$pageN")
            mirrorDir.mkdirs()
            // Copier page.mdm (format brut avec ancres)
            val mdmSrc = File(pageDir, "page.mdm")
            if (mdmSrc.exists()) mdmSrc.copyTo(File(mirrorDir, "page.mdm"), overwrite = true)
            // Générer page.txt (texte épuré, sans balises @mot{…})
            if (mdmSrc.exists()) {
                val raw = mdmSrc.readText()
                val clean = stripMdmTags(raw)
                File(mirrorDir, "page.txt").writeText(clean)
            }
            // Copier bitmap.png (rendu visuel)
            val bmpSrc = File(pageDir, "bitmap.png")
            if (bmpSrc.exists()) bmpSrc.copyTo(File(mirrorDir, "bitmap.png"), overwrite = true)
            // ── Témoin de boîte aux lettres ──
            // Horodatage UTC de la dernière écriture Miroir.
            // Parnasse compare avec note.metadata["miroir_releve"] pour détecter les mises à jour.
            File(mirrorDir, ".miroir_temoin").writeText(Instant.now().toString())
            // ── Identité durable : note_id Parnasse (lien note ↔ capture ↔ transcription) ──
            // PARNASSE → parnasseNoteId (la source de vérité du viewport).
            // LOCAL    → groups.json (note_id gravé au baptême).
            // Le Cœur joint par identité (note_id), jamais par position — le titre p.N
            // change à la renumérotation, l'ID non.
            val noteId = if (navMode == NavMode.PARNASSE) {
                parnasseNoteId
            } else {
                val groupsFile = File(pageDir, "groups.json")
                if (groupsFile.exists()) {
                    try {
                        org.json.JSONObject(groupsFile.readText()).optString("note_id", null)
                    } catch (_: Exception) { null }
                } else null
            }
            if (!noteId.isNullOrEmpty()) File(mirrorDir, ".note_id").writeText(noteId)
        } catch (e: Exception) {
            Log.w(TAG, "mirrorToSdcard échec: ${e.message}")
        }
    }

    /** Épure le texte MDM : retire les métadonnées {…} et les @.
     *
     *  ⚠️ NE PAS supprimer les lettres isolées (ex: regex \\b\\p{L}\\b).
     *  L'apostrophe étant une frontière de mot pour \\b, le "l" de "l'eau"
     *  serait supprimé → MDM tronqué → loadFromMdm() ne trouve plus le label
     *  → génération de strokes synthétiques en double sur la 1ère interligne.
     *  Voir SyntheticStrokeGenerator.kt pour l'analyse du conflit. */
    private fun stripMdmTags(mdm: String): String {
        return mdm.replace(Regex("""\{[^}]*\}"""), "")  // retire {…}
                  .replace("@", "")                      // retire @
                  .trim()
    }

    /** Chargement complet : V★ + bitmap + groupes + MDM. */
    fun loadPageFull(): Boolean {
        val bd = blockDir ?: return false
        try {
            // ═══ ANTI-LIMBE (26/08/2026) — jamais lire page_-1 ═══
            // pageDirIndex() < 0 = aucune page désignée par le Cœur : la
            // lecture ne doit rien charger (surtout pas le limbe écrit par
            // l'ancienne garde incomplète) — la page reste vierge, le
            // réglage viendra au prochain battement de focus.
            val pdi = pageDirIndex()
            if (pdi < 0) return false
            val dir = File(bd, "page_$pdi")
            if (!dir.exists()) return false

            // Nettoyer l'etat avant chargement
            groupManager?.clearAll()
            groupBlobs.clear()
            strokeRegistry.clear()
            inkStrokeIdToRegistryIndex.clear()
            relecture.oublier()
            groupAnchor.clear()

            // ── Bitmap : reconstruit depuis les strokes (pas depuis PNG) ──
            // Le PNG peut contenir des artéfacts (traînées de drag, etc.).
            // On reconstruit le bitmap proprement depuis les strokes du .vstar.
            // Le bitmap doit être initialisé avant (onSizeChanged dans la View).

            // ── V★ → strokes (format V2, 16 bytes/token, scaleFactor=8) ──
            // 🛡️ LE TÉMOIN PARLE (19/09/2026) — suite de l'écriture atomique : si
            // l'app est morte entre les deux renommages, la page n'a pas atterri.
            // Le neuf COMPLET attend alors à côté (`page.vstar.neuf`), ou le
            // témoin de la génération précédente (`page.vstar.bak`, la même loi
            // que le Sculpteur). Une interruption ne doit jamais rendre une page
            // vide : on reprend ce qui est là, et on le dit.
            val vstarFile = File(dir, "page.vstar").let { f ->
                if (f.exists() && f.length() > 0) f
                else {
                    val neuf = File(dir, "page.vstar.neuf")
                    val bak = File(dir, "page.vstar.bak")
                    when {
                        neuf.exists() && neuf.length() > 0 -> {
                            Log.w(TAG, "loadPageFull page_$pdi: page.vstar absente — le NEUF complet est repris")
                            neuf.renameTo(f); f
                        }
                        bak.exists() && bak.length() > 0 -> {
                            Log.w(TAG, "loadPageFull page_$pdi: page.vstar absente — le TÉMOIN (.bak) est repris")
                            bak.renameTo(f); f
                        }
                        else -> f
                    }
                }
            }
            // ═══ LA SENTINELLE VEILLE — le sculpteur façonne la forme ancienne ═══
            // « Quand une note vit mal, façonner la forme, jamais le sens. »
            // Un .vstar v1.1 (header JSON + 14 B/token) est mal lu par le décodeur v2 :
            // les strokes dégénèrent (points identiques) et les groupes rechargés meurent.
            // Le sculpteur migre la forme (v1.1 → v2), le sens reste intact ; l'original
            // reste en .bak (la trace). Une forme inconnue reste en trace, jamais touchée.
            if (VStarSculptor.needsSculpting(vstarFile)) {
                Log.i(TAG, "Sculpteur: forme v1.1 détectée dans ${vstarFile.name} — façonnage (sens préservé, .bak gardé)")
                VStarSculptor.sculpter(vstarFile)
            }
            val ciToRi = mutableMapOf<Short, Int>()
            if (vstarFile.exists() && vstarFile.length() > 0) {
                val region = VStarDataRegion(vstarFile)
                val tokens = region.readAll()
                val scaleFactor = 8f  // doit correspondre à strokeRecordToTokensV2
                if (tokens.isNotEmpty()) {
                    var currentSR: StrokeRecord? = null
                    var currentCI: Short = -1
                    var rx = 0f; var ry = 0f  // position reconstruite
                    for (t in tokens) {
                        val isPenDown = (t.flags.toInt() and VStarTokenV2.FLAG_PEN_DOWN.toInt()) != 0
                        val isPenUp   = (t.flags.toInt() and VStarTokenV2.FLAG_PEN_UP.toInt()) != 0
                        if (VStarTokenV2.isGroupMeta(t.flags)) {
                            // ═══ Ancre de groupe : coordonnées ABSOLUES — réinitialise la
                            // position reconstruite, jamais un point du trait courant.
                            // (Sans quoi l'ancre deviendrait un delta parasite : les bounds
                            // et les points des strokes suivants déraillaient.)
                            rx = t.dx / scaleFactor; ry = t.dy / scaleFactor
                            continue
                        }
                        if (isPenDown) {
                            // Début de stroke : dx/dy = position absolue × scaleFactor
                            // FLAG_PEN_UP sur un PEN_DOWN n'est PAS une fin (juste le premier point)
                            currentSR = StrokeRecord(id = java.util.UUID.randomUUID().toString())
                            rx = t.dx / scaleFactor; ry = t.dy / scaleFactor
                            currentSR.points.add(Pair(rx, ry))
                            currentSR.timestamps.add(0L)
                            currentSR.pressures.add(t.p / 255f)
                            currentCI = t.captureIndex
                        } else if (currentSR != null) {
                            // Move ou PenUp : dx/dy = delta × scaleFactor
                            rx += t.dx / scaleFactor; ry += t.dy / scaleFactor
                            currentSR.points.add(Pair(rx, ry))
                            currentSR.timestamps.add(0L)
                            currentSR.pressures.add(t.p / 255f)
                            if (isPenUp) {
                                // Fin de stroke (PEN_UP sans PEN_DOWN)
                                val ri = strokeRegistry.size
                                strokeRegistry.add(currentSR!!)
                                ciToRi[currentCI] = ri
                                val inkId = (currentCI + 1).toLong()
                                inkStrokeIdToRegistryIndex[inkId] = ri
                                currentSR = null
                            }
                        }
                    }
                    Log.i(TAG, "loadPageFull: ${strokeRegistry.size} strokes depuis V★ (${tokens.size} tokens)")
                }
            }

            // ═══ ALIGNEMENT DE L'IDENTITÉ ═══
            // Un stroke ne change jamais d'identité : les strokes rechargés portent
            // les ids PÉRENNES (ci+1) — le compteur de session reprend AU-DELÀ, il ne
            // repart jamais de zéro (sinon les ids de session divergent des ids du
            // disque : les groupes évincés .groups pointent dans le vide et
            // l'absorption des groupes rechargés meurt — les « frères » fantômes).
            var maxInkId = 0L
            for (id in inkStrokeIdToRegistryIndex.keys) if (id > maxInkId) maxInkId = id
            // ⚓ MARÉE 11/09 — L'ALIGNEMENT EST PAR BLOC, PAS PAR PAGE.
            // Les captureIndices sont pérennes PAR BLOC : charger une page « basse »
            // (ci jusqu'à 88) ne doit jamais ramener le compteur sous une plage déjà
            // brûlée par une autre page du même tiroir (ci jusqu'à 699) — sinon les
            // traits neufs naissent sur des ids déjà pris, les groupes évincés
            // pointent dans le vide et l'absorption meurt.
            // La marque est triple : la page chargée + la marque persistée du bloc
            // (.ci_max, écrite à chaque save) + le balayage borné des groups.json.
            val marqueBloc = readBlocCiWatermark(bd)
            val balayage = scanBlocGroupsMaxCi(bd)
            val maxBloc = maxOf(maxInkId, marqueBloc, balayage)
            if (maxBloc >= inkStrokeIdCounter) inkStrokeIdCounter = maxBloc
            Log.d(TAG, "Alignement identité (bloc) : inkStrokeIdCounter=$inkStrokeIdCounter (page=$maxInkId, marque=$marqueBloc, balayage=$balayage)")

            // ── Groupes & labels ──
            loadGroupsJson(dir, ciToRi)

            // ── MDM ──
            loadPageMdm(dir)

            // Reconstruire les blobs visuels
            rebuildAllBlobs()

            // ═══ Reconstruire le bitmap depuis les strokes (fullRedraw) ═══
            // On ne charge plus bitmap.png (peut contenir des artéfacts).
            // Les strokes viennent d'être chargés depuis .vstar → on les rasterise.
            redrawBitmapInternal(fullRedraw = true)

            // Diagnostic : bounding box des strokes
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
            var totalPts = 0
            for (sr in strokeRegistry) {
                for ((x, y) in sr.points) {
                    if (x < minX) minX = x; if (y < minY) minY = y
                    if (x > maxX) maxX = x; if (y > maxY) maxY = y
                }
                totalPts += sr.points.size
            }
            // Premier stroke : premiers et derniers points
            val firstSR = strokeRegistry.firstOrNull()
            val firstPts = firstSR?.points?.take(3)?.joinToString { "(${it.first.toInt()},${it.second.toInt()})" } ?: "—"
            val lastPts = firstSR?.points?.takeLast(3)?.joinToString { "(${it.first.toInt()},${it.second.toInt()})" } ?: "—"
            Log.i(TAG, "loadPageFull geom: bbox=(${minX.toInt()},${minY.toInt()})-(${maxX.toInt()},${maxY.toInt()}) pts=$totalPts first=$firstPts last=$lastPts")

            Log.i(TAG, "loadPageFull page=$currentPageIndex: ${strokeRegistry.size} strokes, ${groupLabels.size} labels")
            pageLoaded = true
            pageDirty = false  // ⚓ MARÉE 30/08 — la mémoire parle comme le disque
            // 🎙️ LA VOIX DU CORRIGÉ (19/09/2026) — la page est chargée, ses groupes
            // sont connus : on demande les propositions au pont. Elles ne touchent
            // rien — elles attendent d'être AFFICHÉES, puis ratifiées au geste.
            demanderPropositions()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "loadPageFull: ${e.message}", e)
            return false
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS PRIVES
    // ═══════════════════════════════════════════════════════════════════

    private fun strokeRecordToTokensV2(sr: StrokeRecord, ci: Short, scaleFactor: Float = 8f): List<VStarTokenV2> {
        if (sr.points.isEmpty()) return emptyList()
        val tokens = mutableListOf<VStarTokenV2>()
        val first = sr.points.first()
        tokens.add(VStarTokenV2.penDown(
            x = first.first, y = first.second, scaleFactor = scaleFactor,
            p = if (sr.pressures.isNotEmpty()) sr.pressures.first().toInt() else 128,
            az = 255, i = 255, ci = ci
        ))
        var rx = first.first; var ry = first.second
        var lastTs = if (sr.timestamps.isNotEmpty()) sr.timestamps.first() else 0L
        for (j in 1 until sr.points.size) {
            val pt = sr.points[j]
            val dx = ((pt.first - rx) * scaleFactor).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            val dy = ((pt.second - ry) * scaleFactor).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            rx += dx / scaleFactor; ry += dy / scaleFactor
            val ts = if (j < sr.timestamps.size) sr.timestamps[j] else lastTs
            val dt = ((ts - lastTs).toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            lastTs = ts
            val p = if (j < sr.pressures.size) sr.pressures[j].toInt() else 128
            val isLast = j == sr.points.size - 1
            tokens.add(if (isLast) {
                VStarTokenV2.penUp(dx, dy, dt, p, 255, 255, ci)
            } else {
                VStarTokenV2.move(dx, dy, dt, p, 255, 255, ci)
            })
        }
        return tokens
    }

    private fun saveGroupsJson(dir: File) {
        try {
            val gm = groupManager ?: return
            // ⚓ MARÉE 14/09 — LA FORME SUIT LA MATIÈRE : un groupe ne s'écrit que si
            // au moins un de ses strokes VIT dans la page chargée. Sans ce filtre,
            // la maison d'une page sans encre recevait les groupes d'une session
            // d'avant (28 groupes orphelins vus sur page_13, bloc vide — des
            // fantômes dans une maison neuve, épreuve du 14/09).
            val vivants = strokeRegistry.indices
                .filter { !strokeRegistry[it].isDeleted && strokeRegistry[it].points.isNotEmpty() }
                .toHashSet()
            val allGroups = gm.allGroupsFull().filter { g ->
                g.strokeIds.isNotEmpty() && g.strokeIds.any { sid -> inkStrokeIdToRegistryIndex[sid] in vivants }
            }
            if (allGroups.isEmpty()) return
            val arr = org.json.JSONArray()
            for (g in allGroups) {
                val obj = org.json.JSONObject()
                obj.put("id", g.id)
                val ciArr = org.json.JSONArray()
                for (sid in g.strokeIds) {
                    val ci = (sid - 1).coerceIn(0, 65535)
                    ciArr.put(ci)
                }
                if (ciArr.length() == 0) continue
                obj.put("captureIndices", ciArr)
                obj.put("state", g.state.toString())
                val firstSid = g.strokeIds.firstOrNull()
                val firstRI = firstSid?.let { inkStrokeIdToRegistryIndex[it] }
                if (firstRI != null) {
                    groupLabels[firstRI]?.let { obj.put("label", it) }
                    groupAnchor[firstRI]?.let { a ->
                        obj.put("anchorX", a.first.toDouble())
                        obj.put("anchorY", a.second.toDouble())
                    }
                }
                arr.put(obj)
            }
            val groupsFile = File(dir, "groups.json")
            // ═══ Conserver le note_id du baptême (sinon écrasé au 1er save) ═══
            // ⚠️ MARÉE 12/09 — LA LIE S'ÉCRIT AUSSI : l'identité connue de la page
            // (parnasseNoteId) prime sur l'héritage. Sans ce geste, un groups.json
            // né sans note_id le restait à jamais et la page devenait « sans
            // maison » (l'effigie de la MATRICE à la place de la V★).
            val existingNoteId = try {
                if (groupsFile.exists()) org.json.JSONObject(groupsFile.readText()).optString("note_id", null) else null
            } catch (_: Exception) { null }
            val lie = parnasseNoteId?.takeUnless { it.isEmpty() } ?: existingNoteId
            java.io.FileWriter(groupsFile).use { w ->
                val root = org.json.JSONObject()
                root.put("groups", arr)
                if (!lie.isNullOrEmpty()) root.put("note_id", lie)
                w.write(root.toString(2))
            }
            Log.i(TAG, "groups.json: ${allGroups.size} groupes sauvegardes")
        } catch (e: Exception) {
            Log.w(TAG, "saveGroupsJson: ${e.message}")
        }
    }

    private fun loadGroupsJson(dir: File, ciToRi: Map<Short, Int>): Int {
        val file = File(dir, "groups.json")
        val legacyFile = File(dir, "page.groups.json")
        val useFile = when {
            file.exists() -> file
            legacyFile.exists() -> legacyFile
            else -> return 0
        }
        if (useFile.length() > 100_000) {
            Log.w(TAG, "groups.json trop volumineux (${useFile.length()}B) — ignore")
            useFile.delete()
            return 0
        }
        try {
            val raw = useFile.readText().trim()
            val arr = if (raw.startsWith("{")) {
                org.json.JSONObject(raw).optJSONArray("groups") ?: org.json.JSONArray()
            } else {
                org.json.JSONArray(raw)
            }
            var count = 0
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ciArr = obj.optJSONArray("captureIndices")
                val riArr = obj.optJSONArray("registryIndices")
                val sidArr = obj.optJSONArray("strokeIds")
                val inkGroup = InkGroup.create()
                if (ciArr != null) {
                    for (j in 0 until ciArr.length()) {
                        val ci = ciArr.getInt(j).toShort()
                        val ri = ciToRi[ci] ?: continue
                        val inkId = (ci + 1).toLong()
                        if (inkStrokeIdToRegistryIndex.containsKey(inkId)) {
                            inkGroup.strokeIds.add(inkId)
                        }
                    }
                } else if (riArr != null) {
                    val riToCi = mutableMapOf<Int, Short>()
                    for ((c, r) in ciToRi) { riToCi[r] = c }
                    for (j in 0 until riArr.length()) {
                        val savedRI = riArr.getInt(j)
                        val ci = riToCi[savedRI] ?: continue
                        val inkId = (ci + 1).toLong()
                        if (inkStrokeIdToRegistryIndex.containsKey(inkId)) {
                            inkGroup.strokeIds.add(inkId)
                        }
                    }
                } else if (sidArr != null) {
                    for (j in 0 until sidArr.length()) {
                        val sid = sidArr.getLong(j)
                        if (inkStrokeIdToRegistryIndex.containsKey(sid)) {
                            inkGroup.strokeIds.add(sid)
                        }
                    }
                }
                if (inkGroup.strokeIds.isEmpty()) continue
                groupManager?.registerLoadedGroup(inkGroup)
                val firstSid = inkGroup.strokeIds.firstOrNull()
                if (firstSid != null) groupManager?.reactivateGroup(firstSid)
                val label = obj.optString("label", "").takeIf { it.isNotEmpty() }
                val ax = obj.optDouble("anchorX", Double.NaN)
                val ay = obj.optDouble("anchorY", Double.NaN)
                if (label != null || !ax.isNaN()) {
                    val firstRI = firstSid?.let { inkStrokeIdToRegistryIndex[it] }
                    if (firstRI != null) {
                        if (label != null) groupLabels[firstRI] = label
                        if (!ax.isNaN()) groupAnchor[firstRI] = Pair(ax.toFloat(), ay.toFloat())
                    }
                }
                count++
            }
            Log.i(TAG, "groups.json charge: $count groupes")
            return count
        } catch (e: Exception) {
            Log.w(TAG, "loadGroupsJson: ${e.message}")
        }
        return 0
    }

    private fun savePageMdm(dir: File) {
        try {
            data class LineAnchor(val label: String, val lineIdx: Int, val x: Float,
                                  val strokeCount: Int = 0, val pointCount: Int = 0)
            val items = mutableListOf<LineAnchor>()
            val gm = groupManager
            for ((firstIdx, label) in groupLabels) {
                val anchor = groupAnchor[firstIdx] ?: continue
                val cleanLabel = cleanLabelForMdm(label)
                if (cleanLabel.isEmpty()) continue
                val lineIdx = if (cachedTemplateLines.isNotEmpty()) {
                    var best = 0; var bestD = Float.MAX_VALUE
                    for ((idx, ly) in cachedTemplateLines.withIndex()) {
                        val d = Math.abs(anchor.second - ly)
                        if (d < bestD) { bestD = d; best = idx }
                    }
                    best
                } else 0
                // Trouver le groupe correspondant pour les compteurs
                var sc = 0; var pc = 0
                if (gm != null) {
                    val group = gm.allGroupsFull().find { g ->
                        val firstSid = g.strokeIds.firstOrNull() ?: return@find false
                        inkStrokeIdToRegistryIndex[firstSid] == firstIdx
                    }
                    if (group != null) {
                        sc = group.strokeIds.size
                        pc = group.strokeIds.sumOf { sid ->
                            val ri = inkStrokeIdToRegistryIndex[sid] ?: return@sumOf 0
                            strokeRegistry.getOrNull(ri)?.points?.size ?: 0
                        }
                    }
                }
                items.add(LineAnchor(cleanLabel, lineIdx, anchor.first, sc, pc))
            }
            if (items.isEmpty()) return
            items.sortWith(compareBy<LineAnchor> { it.lineIdx }.thenBy { it.x })
            val sb = StringBuilder()
            val totalLines = cachedTemplateLines.size
            if (totalLines <= 0) return
            val lineToWords = mutableMapOf<Int, MutableList<LineAnchor>>()
            for (item in items) {
                lineToWords.getOrPut(item.lineIdx) { mutableListOf() }.add(item)
            }
            for (lineIdx in 0 until totalLines) {
                val words = lineToWords[lineIdx]
                if (words != null) {
                    sb.append(words.joinToString(" ") { item ->
                        val base = "@${item.label}"
                        if (item.strokeCount > 0) {
                            "$base{${item.strokeCount}s/${item.pointCount}p}"
                        } else base
                    })
                }
                if (lineIdx < totalLines - 1) sb.append("\n")
            }
            val mdm = sb.toString().trimEnd('\n')
            if (mdm.isNotEmpty()) {
                File(dir, "page.mdm").writeText(mdm)
                Log.i(TAG, "MDM sauvegarde: ${items.size} ancres -> ${mdm.length}B")
            }
        } catch (e: Exception) {
            Log.w(TAG, "MDM save: ${e.message}")
        }
    }

    private fun loadPageMdm(dir: File) {
        try {
            val mdmFile = File(dir, "page.mdm")
            if (!mdmFile.exists()) return
            val src = mdmFile.readText()
            val count = loadFromMdm(src)
            Log.i(TAG, "MDM charge: ${src.length}B — $count ancres appliquées")
        } catch (e: Exception) {
            Log.w(TAG, "MDM load: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MDM → STROKES SYNTHÉTIQUES (Génération + Positionnement)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Charge un MDM (Geppetto ou fichier) en strokes synthétiques.
     * Pour chaque @mot :
     *   - Si le groupe existe déjà → NE PAS le recalculer (doctrine 13/09 : l'encre
     *     est là, l'étiquette reste sur son encre ; avant, l'Y était écrasé par
     *     firstLine + lineIndex*spacing → labels sautés au rechargement)
     *   - Sinon → génère des strokes synthétiques, crée le groupe
     *
     * Règles de positionnement :
     *   - Première interligne (cachedTemplateLines[0])
     *   - Distance blob entre groupes (spatialDistanceX)
     *   - Retour à la ligne automatique si le mot dépasse la largeur dispo
     *
     * @param mdmSrc  Texte MDM à parser et appliquer
     * @return Nombre d'ancres traitées (déjà en place — non recalculées — ou créées)
     */
    fun loadFromMdm(mdmSrc: String): Int {
        if (mdmSrc.isBlank()) return 0

        val mdmAnchors = try {
            MdmParser.parse(mdmSrc)
        } catch (e: Exception) {
            Log.w(TAG, "MDM parse: ${e.message}")
            return 0
        }
        if (mdmAnchors.isEmpty()) return 0

        val gm = groupManager ?: return 0
        val ctx = appContext ?: return 0
        val canvasW = bitmap?.width ?: ctx.resources.displayMetrics.widthPixels
        val spacing = CalibrationActivity.getTemplateSpacing(ctx)
        val calX = CalibrationActivity.getSpatialDistanceX(ctx)
        val firstLine = cachedTemplateLines.firstOrNull() ?: (spacing * 2f)

        val generator = SyntheticStrokeGenerator(
            lineHeight = spacing,
            blobSpacingX = calX,
            marginX = 60f
        )

        var kept = 0        // groupes déjà en place — NON recalculés (doctrine 13/09)
        var generated = 0
        var lineIndex = 0
        var cursorX = generator.marginX  // position X courante sur la ligne
        val rightMargin = 40f
        val maxX = canvasW - rightMargin

        for (mdmA in mdmAnchors) {
            val targetLabel = mdmA.label

            // ── Groupe existant ? → NE RIEN recalculer (doctrine 13/09) ──
            // L'encre est là, l'ancre réelle est là : l'étiquette reste sur son encre.
            // (Avant : newY = firstLine + lineIndex*spacing écrasait l'Y → labels sautés
            // d'une à plusieurs lignes au rechargement, et les mots répétés (« les »,
            // « page »…) tiraient tous le PREMIER groupe de leur nom vers la dernière
            // occurrence. « Ne pas recalculer ce qui n'a pas changé. »)
            val existingFirstIdx = groupLabels.entries
                .find { it.value.equals(targetLabel, ignoreCase = true) }?.key
            if (existingFirstIdx != null) {
                kept++
                continue
            }

            // ── Nouveau mot → générer strokes synthétiques ──
            // ⚠️ PIÈGE (26/08/2026) : si la page porte DÉJÀ de l'encre réelle,
            // un label manquant est un MISMATCH — labels MDM pollués par
            // l'ancien cleanLabelForMdm (ex. « l'eau » → « eau », regex
            // \b\p{L}\b supprimant les lettres isolées) — PAS un mot nouveau.
            // La sinusoïde ne doit JAMAIS recouvrir les vrais strokes :
            // génération interdite quand la page est déjà encrée. Elle reste
            // réservée aux pages texte sans encre (imports MDM textes).
            if (strokeRegistry.isNotEmpty()) {
                Log.w(TAG, "MDM→strokes: label «$targetLabel» non trouvé — page déjà encrée, génération INTERDITE (mismatch, pas un mot nouveau)")
                continue
            }
            // Vérifier le cache generatedStrokes d'abord
            val cachedStrokes = generatedStrokes[targetLabel.lowercase()]
            val records: List<StrokeRecord>

            if (cachedStrokes != null) {
                // Strokes pré-générés (du modèle Alex Graves ou cache)
                records = listOf(buildStrokeRecord(targetLabel, cachedStrokes))
            } else {
                // Génération procédurale
                records = generator.generate(targetLabel, 0f, 0f)
            }

            if (records.isEmpty()) continue

            // ── Calculer la largeur du mot → retour à la ligne si nécessaire ──
            val wordWidth = generator.estimateWidth(targetLabel)
            if (cursorX + wordWidth > maxX && cursorX > generator.marginX) {
                lineIndex++
                cursorX = generator.marginX
            }

            val anchorY = firstLine + lineIndex * spacing
            val anchorX = cursorX

            // ── Injecter les strokes dans le registre ──
            val newIndices = mutableListOf<Int>()
            val newInkIds = mutableListOf<Long>()
            for (record in records) {
                val shiftedRecord = shiftRecord(record, anchorX, anchorY)
                strokeRegistry.add(shiftedRecord)
                val ri = strokeRegistry.size - 1
                val inkId = ++inkStrokeIdCounter
                inkStrokeIdToRegistryIndex[inkId] = ri
                newIndices.add(ri)
                newInkIds.add(inkId)
            }

            if (newIndices.isEmpty()) continue

            // ── Créer le groupe ──
            val firstIdx = newIndices.first()
            groupLabels[firstIdx] = targetLabel
            groupAnchor[firstIdx] = Pair(anchorX, anchorY)

            val group = InkGroup.create()
            group.strokeIds.addAll(newInkIds)  // inkIds (pas ri+1)
            gm.registerLoadedGroup(group)
            computeBlobPath(group, ctx)?.let { groupBlobs[group.id] = it }

            // Avancer le curseur
            cursorX += wordWidth + calX
            generated++
        }

        val total = kept + generated
        if (total > 0) {
            Log.i(TAG, "MDM→strokes: $kept déjà en place (non recalculés), $generated générés (${total}/${mdmAnchors.size} ancres)")
        }
        // Rien n'a bougé s'il n'y a que des « déjà en place » : pas de redraw.
        if (generated > 0) redrawBitmapInternal(fullRedraw = true)
        return total
    }

    /** Décale tous les points d'un StrokeRecord de (dx, dy). */
    private fun shiftRecord(record: StrokeRecord, dx: Float, dy: Float): StrokeRecord {
        return StrokeRecord(
            id = record.id,
            points = record.points.map { Pair(it.first + dx, it.second + dy) }.toMutableList(),
            timestamps = record.timestamps.toMutableList(),
            pressures = record.pressures.toMutableList(),
            source = record.source
        )
    }

    /** Construit un StrokeRecord depuis des points (x, y, pen). */
    private fun buildStrokeRecord(label: String, strokes: List<Triple<Float, Float, Int>>): StrokeRecord {
        val points = mutableListOf<Pair<Float, Float>>()
        val timestamps = mutableListOf<Long>()
        val pressures = mutableListOf<Float>()
        var t = 0L
        for ((x, y, pen) in strokes) {
            points.add(Pair(x, y))
            timestamps.add(t)
            pressures.add(if (pen > 0) 0.7f else 0f)
            t += 10L
        }
        return StrokeRecord(
            id = java.util.UUID.randomUUID().toString(),
            points = points,
            timestamps = timestamps,
            pressures = pressures,
            source = "mdm"
        )
    }

    private fun cleanLabelForMdm(raw: String): String {
        var cleaned = raw.replace(Regex("\\*G\\d+-\\d+S\\s*"), "")
        cleaned = cleaned.replace(Regex("[^\\p{L}\\p{N} '\\-]"), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        // ⚠️ Ne PAS supprimer les lettres isolées : \b\p{L}\b tue le "l" de "l'eau"
        // car l'apostrophe est une frontière de mot pour \b
        return cleaned
    }
}

data class BlobData(val path: Path, val bounds: RectF)

/** Information sur un bloc pour le menu de sélection. */
data class BlockInfo(
    val id: String,
    val pages: Int,
    val lastModified: Long
)

/** Information sur un bloc Parnasse (récupéré du Cœur via /api/miroir/blocs). */
data class ParnasseBlocInfo(
    val id: String,
    val title: String,
    val nbNotes: Int,
    val mirrorName: String = ""
)

/** Information sur une bibliothèque Parnasse avec son étagère Miroir. */
data class LibraryMiroirInfo(
    val libraryId: String,
    val libraryName: String,
    val shelfId: String,
    val shelfTitle: String
)

/** Un serre-livres — groupement nommé de blocs (Miroir ou Parnasse). */
data class SerreLivres(
    val nom: String,
    val blocs: MutableList<String>  // IDs des blocs (Miroir = nom de dossier, Parnasse = UUID)
)

/** Racine du fichier serre-livres.json dans files/. */
data class SerreLivresData(
    val miroir: MutableList<SerreLivres> = mutableListOf(),
    val parnasse: MutableList<SerreLivres> = mutableListOf()
)
