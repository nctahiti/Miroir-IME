package com.parnasse.miroir

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Charge le curriculum unifié depuis assets.
 *
 * Format JSON attendu (assets/unified_curriculum.json) :
 * {
 *   "sections": [
 *     {"name":"Bigrammes","type":"TRAINING","trainer":"bigram","items":["ab","ac",...]},
 *     {"name":"Haïkus","type":"LIST","items":["haiku1","haiku2",...]},
 *     {"name":"Mots","type":"TRAINING","trainer":"word","items":["mot1","mot2",...]}
 *   ]
 * }
 */
data class CurriculumSection(
    val name: String,
    val type: SectionType,
    val items: List<String>,
    val trainer: String = "",
    val source: String = "",
    val subtitle: String = ""
)

enum class SectionType(val tag: String) {
    LIST("list"),
    TRAINING("training"),
    HISTORY("history")
}

object CurriculumLoader {

    private const val UNIFIED_FILE = "unified_curriculum.json"
    private const val TAG = "Miroir/Curriculum"

    /**
     * Charge le fichier unified_curriculum.json depuis assets.
     * Retourne une liste de CurriculumSection.
     */
    fun load(context: Context): List<CurriculumSection> {
        try {
            val json = context.assets.open(UNIFIED_FILE).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val sections = root.getJSONArray("sections")
            val result = mutableListOf<CurriculumSection>()

            for (i in 0 until sections.length()) {
                val section = sections.getJSONObject(i)
                val name = section.optString("name", "Section $i")
                val typeStr = section.optString("type", "LIST")
                val type = try {
                    SectionType.valueOf(typeStr)
                } catch (e: IllegalArgumentException) {
                    SectionType.LIST
                }
                val items = mutableListOf<String>()
                if (section.has("items")) {
                    val itemsArr = section.getJSONArray("items")
                    for (j in 0 until itemsArr.length()) {
                        items.add(itemsArr.getString(j))
                    }
                }
                val trainer = section.optString("trainer", "")
                val source = section.optString("source", "")
                val subtitle = section.optString("subtitle", "")

                result.add(CurriculumSection(name, type, items, trainer, source, subtitle))
            }

            Log.i(TAG, "Curriculum chargé: ${result.size} sections")
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement curriculum: ${e.message}")
            return listOf(
                CurriculumSection("Haïkus classiques", SectionType.LIST, emptyList()),
                CurriculumSection("Haïkus libres", SectionType.LIST, emptyList())
            )
        }
    }
}
