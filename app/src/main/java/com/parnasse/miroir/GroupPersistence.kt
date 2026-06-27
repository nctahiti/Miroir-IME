package com.parnasse.miroir

import android.graphics.RectF
import android.util.Log
import org.json.JSONObject
import java.io.File

class GroupPersistence(private val file: File) {

    companion object {
        private const val TAG = "GroupPersistence"
        fun groupsFile(noteDir: File, baseName: String): File =
            File(noteDir, "$baseName.groups")
    }

    fun exists(): Boolean = file.exists()

    fun readGroup(groupId: String): InkGroup? {
        val all = readAllJson() ?: return null
        val obj = all.optJSONObject(groupId) ?: return null
        return fromJson(groupId, obj)
    }

    fun readAllGroups(): List<InkGroup> {
        val all = readAllJson() ?: return emptyList()
        val groups = mutableListOf<InkGroup>()
        for (key in all.keys()) {
            val obj = all.optJSONObject(key) ?: continue
            val group = fromJson(key, obj)
            if (group != null) groups.add(group)
        }
        return groups
    }

    fun writeGroup(group: InkGroup) {
        val all = readAllJson() ?: JSONObject()
        all.put(group.id, toJson(group))
        writeAllJson(all)
    }

    fun deleteGroup(groupId: String) {
        val all = readAllJson() ?: return
        if (all.has(groupId)) {
            all.remove(groupId)
            writeAllJson(all)
        }
    }

    /** Supprime le fichier .groups (pour repartir à zéro au chargement). */
    fun deleteAll() {
        try { file.delete() } catch (_: Exception) {}
    }

    fun writeAllGroups(groups: List<InkGroup>) {
        val all = JSONObject()
        for (group in groups) {
            all.put(group.id, toJson(group))
        }
        writeAllJson(all)
    }

    private fun toJson(group: InkGroup): JSONObject {
        val obj = JSONObject()
        val sArr = org.json.JSONArray()
        for (sid in group.strokeIds) sArr.put(sid.toLong())
        obj.put("s", sArr)
        val bArr = org.json.JSONArray()
        bArr.put(group.bounds.left.toDouble())
        bArr.put(group.bounds.top.toDouble())
        bArr.put(group.bounds.right.toDouble())
        bArr.put(group.bounds.bottom.toDouble())
        obj.put("b", bArr)
        group.orderIndex?.let { obj.put("o", it) }
        obj.put("l", group.groupLevel.name)
        obj.put("c", group.createdAt)
        obj.put("m", group.modifiedAt)
        return obj
    }

    private fun fromJson(groupId: String, obj: JSONObject): InkGroup? {
        return try {
            val sArr = obj.optJSONArray("s") ?: return null
            val strokeIds = mutableListOf<Long>()
            for (i in 0 until sArr.length()) {
                strokeIds.add(sArr.optLong(i, -1))
            }
            if (strokeIds.isEmpty()) return null

            val bArr = obj.optJSONArray("b")
            val bounds = if (bArr != null && bArr.length() >= 4) {
                RectF(
                    bArr.optDouble(0).toFloat(),
                    bArr.optDouble(1).toFloat(),
                    bArr.optDouble(2).toFloat(),
                    bArr.optDouble(3).toFloat()
                )
            } else RectF()

            val orderIndex = if (obj.has("o")) obj.optInt("o") else null
            val groupLevel = try {
                GroupLevel.valueOf(obj.optString("l", "WORD"))
            } catch (_: Exception) { GroupLevel.WORD }

            val createdAt = obj.optLong("c", System.currentTimeMillis())
            val modifiedAt = obj.optLong("m", System.currentTimeMillis())

            InkGroup(
                id = groupId,
                state = GroupState.STORED,
                strokeIds = strokeIds,
                bounds = bounds,
                orderIndex = orderIndex,
                groupLevel = groupLevel,
                createdAt = createdAt,
                modifiedAt = modifiedAt
            )
        } catch (e: Exception) {
            Log.w(TAG, "Erreur parsing groupe $groupId: ${e.message}")
            null
        }
    }

    private fun readAllJson(): JSONObject? {
        if (!file.exists()) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lecture .groups: ${e.message}")
            null
        }
    }

    private fun writeAllJson(obj: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Erreur ecriture .groups: ${e.message}")
        }
    }
}
