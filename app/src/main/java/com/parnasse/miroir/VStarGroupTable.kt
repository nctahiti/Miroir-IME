package com.parnasse.miroir

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Table d'allocation des groupes V★ v2.0.
 *
 * Remplace groups.json + labels.json + GroupPersistence.
 * Chaque groupe a des extents (plages de tokens) dans la DataRegion.
 */
class VStarGroupTable {

    companion object {
        private const val TAG = "Miroir/GroupTable"
    }

    data class GroupEntry(
        val id: String,
        val extents: MutableList<Extent> = mutableListOf(),
        var anchorX: Float = 0f,
        var anchorY: Float = 0f,
        var label: String? = null,
        var labelCorrected: String? = null,
        var order: Int = 0,
        var state: String = "OPEN",
        val createdAt: Long = System.currentTimeMillis()
    ) {
        val tokenCount: Int get() = extents.sumOf { it.count }
        val isEmpty: Boolean get() = extents.all { it.count == 0 }

        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("extents", JSONArray().apply {
                for (e in extents) put(JSONArray().apply { put(e.offset); put(e.count) })
            })
            put("anchor", JSONObject().apply { put("x", anchorX.toDouble()); put("y", anchorY.toDouble()) })
            if (label != null) put("label", label)
            if (labelCorrected != null) put("label_corrected", labelCorrected)
            put("order", order)
            put("state", state)
            put("created_at", createdAt)
        }

        companion object {
            fun fromJson(obj: JSONObject): GroupEntry {
                val extents = mutableListOf<Extent>()
                val arr = obj.optJSONArray("extents")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONArray(i) ?: continue
                        extents.add(Extent(e.optInt(0), e.optInt(1)))
                    }
                }
                val anchor = obj.optJSONObject("anchor")
                return GroupEntry(
                    id = obj.getString("id"),
                    extents = extents,
                    anchorX = anchor?.optDouble("x")?.toFloat() ?: 0f,
                    anchorY = anchor?.optDouble("y")?.toFloat() ?: 0f,
                    label = if (obj.has("label")) obj.getString("label") else null,
                    labelCorrected = if (obj.has("label_corrected")) obj.getString("label_corrected") else null,
                    order = obj.optInt("order", 0),
                    state = obj.optString("state", "OPEN"),
                    createdAt = obj.optLong("created_at", System.currentTimeMillis())
                )
            }
        }
    }

    data class Extent(val offset: Int, val count: Int)

    // ── État ──

    private val groups = mutableMapOf<String, GroupEntry>()
    private val freeList = mutableListOf<Int>()
    var nextCaptureIndex: Short = 0
        private set

    val groupCount: Int get() = groups.size
    val allGroups: Collection<GroupEntry> get() = groups.values

    // ── CRUD ──

    fun createGroup(anchorX: Float, anchorY: Float, order: Int = groups.size): GroupEntry {
        val id = generateGroupId()
        val entry = GroupEntry(id = id, anchorX = anchorX, anchorY = anchorY, order = order)
        groups[id] = entry
        Log.d(TAG, "Groupe cree: $id anchor=($anchorX,$anchorY) (total=${"$"}{groups.size})")
        return entry
    }

    fun getGroup(id: String): GroupEntry? = groups[id]

    fun addExtent(groupId: String, extent: Extent) {
        val g = groups[groupId] ?: return
        val last = g.extents.lastOrNull()
        if (last != null && last.offset + last.count == extent.offset) {
            g.extents[g.extents.size - 1] = Extent(last.offset, last.count + extent.count)
        } else {
            g.extents.add(extent)
        }
    }

    fun removeExtent(groupId: String, offset: Int): Boolean {
        val g = groups[groupId] ?: return false
        val iter = g.extents.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.offset == offset) { iter.remove(); freeList.add(offset); return true }
        }
        return false
    }

    fun deleteGroup(groupId: String): Boolean {
        val g = groups[groupId] ?: return false
        for (e in g.extents) freeList.add(e.offset)
        g.extents.clear(); g.state = "DELETED"
        return true
    }

    fun updateGroup(id: String, anchorX: Float? = null, anchorY: Float? = null,
                    label: String? = null, labelCorrected: String? = null,
                    state: String? = null, order: Int? = null): Boolean {
        val g = groups[id] ?: return false
        if (anchorX != null) g.anchorX = anchorX
        if (anchorY != null) g.anchorY = anchorY
        if (label != null) g.label = label
        if (labelCorrected != null) g.labelCorrected = labelCorrected
        if (state != null) g.state = state
        if (order != null) g.order = order
        return true
    }

    fun reorder(groupOrder: List<String>) {
        for ((i, id) in groupOrder.withIndex()) groups[id]?.order = i
    }

    fun orderedGroups(): List<GroupEntry> = groups.values
        .filter { it.state != "DELETED" }
        .sortedBy { it.order }

    // ── Free list ──

    fun allocateOffset(): Int {
        return if (freeList.isNotEmpty()) freeList.removeAt(freeList.size - 1)
        else nextCaptureIndex.toInt()
    }

    fun nextCaptureIndex(): Short {
        val ci = nextCaptureIndex
        nextCaptureIndex = (nextCaptureIndex + 1).toShort()
        return ci
    }

    // ── JSON ──

    fun toJson(): JSONObject = JSONObject().apply {
        put("groups", JSONArray().apply {
            for (g in groups.values) put(g.toJson())
        })
        put("free_list", JSONArray().apply {
            for (o in freeList) put(o.toInt())
        })
        put("next_capture_index", nextCaptureIndex.toInt())
    }

    fun fromJson(json: JSONObject) {
        groups.clear(); freeList.clear()
        val arr = json.optJSONArray("groups")
        if (arr != null) for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { obj ->
                val g = GroupEntry.fromJson(obj); groups[g.id] = g
            }
        }
        val fl = json.optJSONArray("free_list")
        if (fl != null) for (i in 0 until fl.length()) freeList.add(fl.optInt(i))
        nextCaptureIndex = json.optInt("next_capture_index", 0).toShort()
    }

    // ── Persistance ──

    fun save(file: File) {
        val tmp = File(file.parentFile, "${"$"}{file.name}.tmp")
        tmp.writeText(toJson().toString(2))
        tmp.renameTo(file)
    }

    fun load(file: File): Boolean {
        if (!file.exists()) return false
        return try { fromJson(JSONObject(file.readText())); true }
        catch (e: Exception) { Log.e(TAG, "Erreur chargement: ${"$"}{e.message}"); false }
    }

    // ── Interne ──

    private fun generateGroupId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..8).map { chars.random() }.joinToString("")
    }
}
