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
import android.os.Environment
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONObject

class MiroirEngine {

    companion object {
        const val TAG = "MiroirEngine"
    }

    // ── Blocs & Pages ──────────────────────────────────────────────────
    var blockDir: File? = null; private set
    var currentPageIndex = 0
    private var appContext: android.content.Context? = null

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
    val groupLabels = mutableMapOf<Int, String>()
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
            // Les strokes sont déjà rastérisés dans le bitmap → on les marque
            // pour que redrawBitmapInternal() les ignore.
            it.onGroupEvicted = { group ->
                for (sid in group.strokeIds) {
                    val ri = inkStrokeIdToRegistryIndex[sid] ?: continue
                    strokeRegistry.getOrNull(ri)?.isArchived = true
                }
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
                if (firstIdx != null) groupLabels.remove(firstIdx)
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
        groupLabels.clear()
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
                    nbNotes = obj.optInt("nb_notes", 0)
                ))
            }
            return result
        } catch (e: Exception) {
            Log.w(TAG, "fetchParnasseBlocs: ${e.message}")
            return emptyList()
        }
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

    fun countPages(): Int = blockDir?.listFiles()?.count {
        it.isDirectory && it.name.startsWith("page_")
    } ?: 0

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

    fun clearPage() {
        groupManager?.clearAll()
        groupBlobs.clear()
        strokeRegistry.clear()
        inkStrokeIdToRegistryIndex.clear()
        groupLabels.clear()
        groupAnchor.clear()
        // Effacer le bitmap sans le détruire (reste utilisable pour redrawBitmapOnly)
        bitmap?.eraseColor(android.graphics.Color.WHITE)
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
     *  Si le dossier local n'existe pas, loadPageFull() crée une page blanche. */
    fun goToPageFull(index: Int) {
        if (index < Int.MIN_VALUE) return  // seule garde : sécurité absurde
        savePageFull()
        currentPageIndex = index
        val loaded = loadPageFull()
        if (!loaded) {
            // Pas de dossier local → page blanche
            clearPage()
        }
        redrawBitmapInternal()  // synchroniser après chargement
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
        groupLabels.clear(); groupAnchor.clear()
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
        val dir = File(bd, "page_$currentPageIndex")
        dir.mkdirs()
        val vstarFile = File(dir, "page.vstar")
        // ═══ Inclure TOUS les strokes non-supprimés (vivants + archivés) ═══
        // Les strokes archivés sont déjà dans le bitmap mais doivent persister
        // dans le .vstar pour le rechargement futur.
        val allStrokes = strokeRegistry.count { !it.isDeleted && it.points.isNotEmpty() }

        // ── V★ : reecriture propre avec tous les strokes (archivés inclus) ──
        if (allStrokes > 0) {
            if (vstarFile.exists()) vstarFile.delete()
            val dataRegion = VStarDataRegion(vstarFile)
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
            Log.i(TAG, "savePageFull page=$currentPageIndex vstar=${vstarFile.length()}B strokes=$allStrokes")
        } else {
            if (vstarFile.exists()) { vstarFile.delete() }
            Log.i(TAG, "savePageFull page=$currentPageIndex — page vide")
        }

        // ── Bitmap PNG ──
        redrawBitmapInternal()  // synchroniser avant sauvegarde
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

        // ── Miroir sdcard — copie accessible au Scanner/Cœur ──
        mirrorToSdcard(dir, bd.name, currentPageIndex)
    }

    /** Exporte la page courante vers la SD card — appelé à chaque mot reconnu.
     *  Les fichiers sont déjà sur disque (.vstar, groups.json, page.mdm).
     *  On ne fait que les déclarer au monde extérieur. */
    fun exportCurrentPage() {
        val bd = blockDir ?: return
        val dir = File(bd, "page_$currentPageIndex")
        if (!dir.exists()) return
        Log.i(TAG, "exportCurrentPage: page $currentPageIndex → SD card")
        mirrorToSdcard(dir, bd.name, currentPageIndex)
    }

    /** Copie miroir de la page sauvegardée vers le stockage externe
     *  pour que le Scanner et le Cœur puissent la lire sans sandboxing. */
    internal fun mirrorToSdcard(pageDir: File, blockName: String, pageN: Int) {
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
            val dir = File(bd, "page_$currentPageIndex")
            if (!dir.exists()) return false

            // Nettoyer l'etat avant chargement
            groupManager?.clearAll()
            groupBlobs.clear()
            strokeRegistry.clear()
            inkStrokeIdToRegistryIndex.clear()
            groupLabels.clear()
            groupAnchor.clear()

            // ── Bitmap : reconstruit depuis les strokes (pas depuis PNG) ──
            // Le PNG peut contenir des artéfacts (traînées de drag, etc.).
            // On reconstruit le bitmap proprement depuis les strokes du .vstar.
            // Le bitmap doit être initialisé avant (onSizeChanged dans la View).

            // ── V★ → strokes (format V2, 16 bytes/token, scaleFactor=8) ──
            val vstarFile = File(dir, "page.vstar")
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
            val allGroups = gm.allGroupsFull().filter { it.strokeIds.isNotEmpty() }
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
            java.io.FileWriter(File(dir, "groups.json")).use { w ->
                val root = org.json.JSONObject()
                root.put("groups", arr)
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
     *   - Si le groupe existe déjà → repositionne Y seulement
     *   - Sinon → génère des strokes synthétiques, crée le groupe
     *
     * Règles de positionnement :
     *   - Première interligne (cachedTemplateLines[0])
     *   - Distance blob entre groupes (spatialDistanceX)
     *   - Retour à la ligne automatique si le mot dépasse la largeur dispo
     *
     * @param mdmSrc  Texte MDM à parser et appliquer
     * @return Nombre d'ancres appliquées (groupes repositionnés ou créés)
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

        var applied = 0
        var generated = 0
        var lineIndex = 0
        var cursorX = generator.marginX  // position X courante sur la ligne
        val rightMargin = 40f
        val maxX = canvasW - rightMargin

        for (mdmA in mdmAnchors) {
            val targetLabel = mdmA.label

            // ── Groupe existant ? → repositionner Y seulement ──
            val existingFirstIdx = groupLabels.entries
                .find { it.value.equals(targetLabel, ignoreCase = true) }?.key
            if (existingFirstIdx != null) {
                val currentAnchor = groupAnchor[existingFirstIdx] ?: continue
                val newY = firstLine + mdmA.lineIndex * spacing
                groupAnchor[existingFirstIdx] = Pair(currentAnchor.first, newY)
                applied++
                continue
            }

            // ── Nouveau mot → générer strokes synthétiques ──
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

        val total = applied + generated
        if (total > 0) {
            Log.i(TAG, "MDM→strokes: $applied repositionnés, $generated générés (${total}/${mdmAnchors.size} ancres)")
            redrawBitmapInternal(fullRedraw = true)
        }
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
    val nbNotes: Int
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
