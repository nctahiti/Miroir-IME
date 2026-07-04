package com.parnasse.miroir

import android.util.Log
import java.io.*
import org.json.JSONObject

/**
 * VStarDocumentV2 — API publique du document V★ v2.0 (16B, vivant).
 *
 * Assemble VStarTokenV2 + VStarDataRegion + VStarGroupTable.
 * Remplace VStarWriter + VStarEncoder + VStarDecoder + GroupPersistence
 * pour le Miroir IME en mode V★ only.
 */
class VStarDocumentV2(private val file: File) {

    companion object {
        private const val TAG = "Miroir/VStarDocV2"
    }

    private var dataRegion: VStarDataRegion? = null
    private var groupTable: VStarGroupTable? = null
    private var isOpen = false

    private var activeStrokeTokens = mutableListOf<VStarTokenV2>()
    private var activeStrokeCI: Short = -1

    val tokenCount: Int get() = dataRegion?.tokenCount ?: 0
    val groupCount: Int get() = groupTable?.groupCount ?: 0

    // ──── Cycle de vie ────

    fun open() {
        if (isOpen) return
        dataRegion = VStarDataRegion(file).also { it.open() }
        groupTable = VStarGroupTable()
        val tableFile = groupTableFile()
        if (tableFile.exists()) {
            groupTable!!.load(tableFile)
            Log.i(TAG, "Ouvert: ${tokenCount} tokens, ${groupCount} groupes")
        } else {
            Log.i(TAG, "Nouveau: ${file.absolutePath}")
        }
        isOpen = true
    }

    fun close() { flush(); dataRegion?.close(); dataRegion = null; groupTable = null; isOpen = false }
    fun flush() { groupTable?.save(groupTableFile()); dataRegion?.flush() }

    // ──── Capture ────

    fun beginStroke(): Short {
        val gt = groupTable ?: throw IllegalStateException("Document non ouvert")
        val ci = gt.nextCaptureIndex()
        activeStrokeTokens.clear(); activeStrokeCI = ci
        return ci
    }

    fun writeToken(token: VStarTokenV2) {
        dataRegion?.append(token); activeStrokeTokens.add(token)
    }

    fun endStroke(ci: Short) {
        if (ci != activeStrokeCI) Log.w(TAG, "endStroke: ci=$ci != actif=$activeStrokeCI")
        activeStrokeCI = -1
    }

    fun activeStrokeOffset(): Int {
        val dr = dataRegion ?: return -1
        return dr.tokenCount - activeStrokeTokens.size
    }
    fun activeStrokeTokenCount(): Int = activeStrokeTokens.size

    // ──── Groupement ────

    fun createGroup(ci: Short, anchorX: Float, anchorY: Float, label: String? = null): String {
        val gt = groupTable ?: return ""
        val offset = activeStrokeOffset(); val count = activeStrokeTokenCount()
        if (count == 0) { Log.w(TAG, "createGroup: stroke vide"); return "" }
        val g = gt.createGroup(anchorX, anchorY)
        gt.addExtent(g.id, VStarGroupTable.Extent(offset, count))
        if (label != null) gt.updateGroup(g.id, label = label)
        Log.i(TAG, "Groupe: ${g.id} ci=$ci offset=$offset +$count")
        return g.id
    }

    /** Crée un groupe avec offset/count explicites (pour sauvegarde par lots). */
    fun createGroupWithExtent(anchorX: Float, anchorY: Float, offset: Int, count: Int, label: String? = null): String {
        val gt = groupTable ?: return ""
        if (count == 0) return ""
        val g = gt.createGroup(anchorX, anchorY)
        gt.addExtent(g.id, VStarGroupTable.Extent(offset, count))
        if (label != null) gt.updateGroup(g.id, label = label)
        Log.i(TAG, "Groupe batch: ${g.id} offset=$offset +$count")
        return g.id
    }

    fun getGroupTable(): VStarGroupTable? = groupTable

    fun absorbStroke(ci: Short, targetGroupId: String): Boolean {
        val gt = groupTable ?: return false
        val offset = activeStrokeOffset(); val count = activeStrokeTokenCount()
        if (count == 0) return false
        gt.addExtent(targetGroupId, VStarGroupTable.Extent(offset, count))
        Log.d(TAG, "Absorbé: ci=$ci → $targetGroupId +$count")
        return true
    }

    // ──── Modification ────

    fun erase(ci: Short) {
        dataRegion?.append(VStarTokenV2.eraseToken(ci))
        Log.d(TAG, "Tombstone: ci=$ci")
    }

    fun updateGroupMeta(groupId: String, label: String? = null, labelCorrected: String? = null,
                        anchorX: Float? = null, anchorY: Float? = null, state: String? = null) {
        groupTable?.updateGroup(groupId, anchorX = anchorX, anchorY = anchorY,
            label = label, labelCorrected = labelCorrected, state = state)
    }

    fun moveGroup(groupId: String, newOrder: Int) = groupTable?.updateGroup(groupId, order = newOrder)
    fun reorderGroups(groupOrder: List<String>) = groupTable?.reorder(groupOrder)

    // ──── Lecture ────

    data class LoadResult(
        val tokens: List<VStarTokenV2>,
        val groups: List<VStarGroupTable.GroupEntry>
    )

    fun load(): LoadResult {
        val tokens = dataRegion?.readAll() ?: emptyList()
        val groups = groupTable?.orderedGroups() ?: emptyList()
        return LoadResult(tokens, groups)
    }

    fun loadGroupTokens(groupId: String): List<VStarTokenV2> {
        val gt = groupTable ?: return emptyList(); val dr = dataRegion ?: return emptyList()
        val g = gt.getGroup(groupId) ?: return emptyList()
        return g.extents.flatMap { e -> (0 until e.count).mapNotNull { dr.readAt(e.offset + it) } }
    }

    // ──── Utilitaires ────

    private fun groupTableFile() = File(file.parentFile, "${file.nameWithoutExtension}.groups.json")

    fun reset() {
        dataRegion?.close(); groupTable = VStarGroupTable()
        file.delete(); groupTableFile().delete()
        dataRegion = VStarDataRegion(file).also { it.open() }
        activeStrokeTokens.clear(); activeStrokeCI = -1
    }
}
