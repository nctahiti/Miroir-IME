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

    // ── Strokes ────────────────────────────────────────────────────────
    val strokeRegistry = mutableListOf<StrokeRecord>()
    val inkStrokeIdToRegistryIndex = mutableMapOf<Long, Int>()

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
}

data class BlobData(val path: Path, val bounds: RectF)
