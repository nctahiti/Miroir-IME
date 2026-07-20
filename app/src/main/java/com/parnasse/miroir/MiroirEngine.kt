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
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class MiroirEngine {

    companion object {
        const val TAG = "MiroirEngine"
    }

    // ── Blocs & Pages ──────────────────────────────────────────────────
    var blockDir: File? = null; private set
    var currentPageIndex = 0; private set
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
        appContext = context.applicationContext
        groupManager = GroupManager({}).also {
            it.params = it.params.copy(transcriptionTimeoutMs = Long.MAX_VALUE)
            it.pointProvider = { strokeId ->
                inkStrokeIdToRegistryIndex[strokeId]
                    ?.let { strokeRegistry.getOrNull(it)?.points ?: emptyList() }
                    ?: emptyList()
            }
            val tmpDir = File(context.filesDir, "groups"); tmpDir.mkdirs()
            it.persistence = GroupPersistence(File(tmpDir, "current.groups"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAPTURE DE STROKE (utilise par CaptureSurface)
    // ═══════════════════════════════════════════════════════════════════

    fun beginStroke(x: Float, y: Float) {
        currentPath.reset()
        currentPath.moveTo(x, y)
        currentStrokeRecord = StrokeRecord(id = java.util.UUID.randomUUID().toString()).also { sr ->
            sr.points.add(Pair(x, y))
            sr.timestamps.add(System.currentTimeMillis())
            sr.pressures.add(1.0f)
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
        val inkStroke = strokeRecordToInkStroke(sr, inkId)
        val group = gm.onStrokeSealed(inkStroke)
        // Creer/mettre a jour le blob du groupe
        if (group != null) {
            val blob = computeBlobPath(group)
            if (blob != null) {
                groupBlobs[group.id] = blob
                Log.d(TAG, "Blob cree pour groupe ${group.id.take(8)} — ${group.strokeIds.size} strokes")
            } else {
                Log.w(TAG, "Blob NULL pour groupe ${group.id.take(8)} — strokes=${group.strokeIds.size} pts verifices")
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
        savePage()
        groupManager?.clearAll()
        groupBlobs.clear()
        strokeRegistry.clear()
        inkStrokeIdToRegistryIndex.clear()
        groupLabels.clear()
        groupAnchor.clear()
        blockDir = null
    }

    fun countPages(): Int = blockDir?.listFiles()?.count {
        it.isDirectory && it.name.startsWith("page_")
    } ?: 0

    // ═══════════════════════════════════════════════════════════════════
    // PAGES
    // ═══════════════════════════════════════════════════════════════════

    fun newPage() {
        savePage()
        val bd = blockDir ?: return
        val total = countPages()
        for (i in total - 1 downTo currentPageIndex) {
            File(bd, "page_$i").renameTo(File(bd, "page_${i + 1}"))
        }
        clearPage()
        currentPageIndex = (currentPageIndex + 1).coerceAtMost(total)
    }

    fun clearPage() {
        groupManager?.clearAll()
        groupBlobs.clear()
        strokeRegistry.clear()
        inkStrokeIdToRegistryIndex.clear()
        groupLabels.clear()
        groupAnchor.clear()
        bitmap?.recycle(); bitmap = null; bitmapCanvas = null
    }

    fun goToPage(index: Int) {
        val total = countPages()
        if (total == 0 || index < 0 || index >= total) return
        savePage()
        currentPageIndex = index
        loadPage()
    }

    /** Navigation avec sauvegarde/chargement complets (standalone). */
    fun goToPageFull(index: Int) {
        val total = countPages()
        if (total == 0 || index < 0 || index >= total) return
        savePageFull()
        currentPageIndex = index
        loadPageFull()
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
        val liveStrokes = strokeRegistry.count { !it.isDeleted && it.points.isNotEmpty() }

        // ── V★ : reecriture propre avec strokes vivants seulement ──
        if (liveStrokes > 0) {
            if (vstarFile.exists()) vstarFile.delete()
            val dataRegion = VStarDataRegion(vstarFile)
            dataRegion.open()
            val liveIndices = strokeRegistry.indices
                .filter { !strokeRegistry[it].isDeleted && strokeRegistry[it].points.isNotEmpty() }
                .toList()
            for (ri in liveIndices) {
                val sr = strokeRegistry[ri]
                val inkId = inkStrokeIdToRegistryIndex.entries.firstOrNull { it.value == ri }?.key
                val ci = inkId?.let { (it - 1).toShort() } ?: continue
                val tokens = strokeRecordToTokensV2(sr, ci)
                for (t in tokens) dataRegion.append(t)
            }
            dataRegion.close()
            Log.i(TAG, "savePageFull page=$currentPageIndex vstar=${vstarFile.length()}B strokes=$liveStrokes")
        } else {
            if (vstarFile.exists()) { vstarFile.delete() }
            Log.i(TAG, "savePageFull page=$currentPageIndex — page vide")
        }

        // ── Bitmap PNG ──
        bitmap?.let {
            FileOutputStream(File(dir, "bitmap.png")).use { out ->
                it.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        }

        // ── Groupes & labels ──
        saveGroupsJson(dir)

        // ── MDM ──
        savePageMdm(dir)
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

            // ── Bitmap PNG ──
            val bmpFile = File(dir, "bitmap.png")
            if (bmpFile.exists()) {
                val loaded = android.graphics.BitmapFactory.decodeFile(bmpFile.absolutePath)
                if (loaded != null) {
                    bitmap?.recycle()
                    bitmap = loaded.copy(Bitmap.Config.ARGB_8888, true)
                    bitmapCanvas = Canvas(bitmap!!)
                }
            }

            // ── V★ → strokes ──
            val vstarFile = File(dir, "page.vstar")
            val ciToRi = mutableMapOf<Short, Int>()
            if (vstarFile.exists() && vstarFile.length() > 0) {
                val decoder = VStarDecoder(vstarFile)
                val result = decoder.decode()
                if (result != null) {
                    for ((ci, ri) in result.captureIndexToRegistry) {
                        ciToRi[ci.toShort()] = ri
                    }
                    strokeRegistry.addAll(result.strokes)
                    for ((ci, ri) in result.captureIndexToRegistry) {
                        val inkId = (ci + 1).toLong()
                        inkStrokeIdToRegistryIndex[inkId] = ri
                    }
                    Log.i(TAG, "loadPageFull: ${result.strokes.size} strokes depuis V★")
                }
            }

            // ── Groupes & labels ──
            loadGroupsJson(dir, ciToRi)

            // ── MDM ──
            loadPageMdm(dir)

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
            data class LineAnchor(val label: String, val lineIdx: Int, val x: Float)
            val items = mutableListOf<LineAnchor>()
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
                items.add(LineAnchor(cleanLabel, lineIdx, anchor.first))
            }
            if (items.isEmpty()) return
            items.sortWith(compareBy<LineAnchor> { it.lineIdx }.thenBy { it.x })
            val sb = StringBuilder()
            val totalLines = cachedTemplateLines.size
            if (totalLines <= 0) return
            val lineToWords = mutableMapOf<Int, MutableList<String>>()
            for (item in items) {
                lineToWords.getOrPut(item.lineIdx) { mutableListOf() }.add(item.label)
            }
            for (lineIdx in 0 until totalLines) {
                val words = lineToWords[lineIdx]
                if (words != null) {
                    sb.append(words.joinToString(" ") { "@$it" })
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
            Log.i(TAG, "MDM charge: ${src.length}B — ${src.take(80)}")
        } catch (e: Exception) {
            Log.w(TAG, "MDM load: ${e.message}")
        }
    }

    private fun cleanLabelForMdm(raw: String): String {
        var cleaned = raw.replace(Regex("\\*G\\d+-\\d+S\\s*"), "")
        cleaned = cleaned.replace(Regex("[^\\p{L}\\p{N} '\\-]"), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        cleaned = cleaned.replace(Regex("\\b\\p{L}\\b"), "").replace(Regex("\\s+"), " ").trim()
        return cleaned
    }
}

data class BlobData(val path: Path, val bounds: RectF)
