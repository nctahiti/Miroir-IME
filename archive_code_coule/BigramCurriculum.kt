package com.parnasse.miroir

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bigram entry for training curriculum.
 */
data class BigramEntry(
    val pair: String,
    val tier: String,
    val repetitions: Int = 1,
    val isTraining: Boolean = false
)

/**
 * Progress state for bigram curriculum.
 */
data class BigramProgress(
    val entries: List<CompletedEntry> = emptyList()
)

/**
 * A completed bigram pair entry.
 */
data class CompletedEntry(
    val pair: String,
    val tier: String,
    val repetition: Int,
    val completedAt: String,
    val vstarFile: String
)

/**
 * Bigram trainer — manages bigram pair curriculum.
 * Singleton, persists progress to SharedPreferences.
 */
class BigramTrainer private constructor(
    private val stateFile: File,
    private val allPairs: List<BigramEntry>
) {
    companion object {
        private const val TAG = "Miroir/BigramTrainer"
        private const val PROGRESS_FILE = "bigram_progress.json"
        private const val PREFS_NAME = "miroir_bigrams"
        private const val PREFS_KEY = "progress"
        private const val CURRICULUM_FILE = "bigram_curriculum.json"

        @Volatile
        private var instance: BigramTrainer? = null

        fun getInstance(context: Context): BigramTrainer {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val dir = File(context.filesDir, "curriculum")
                        dir.mkdirs()
                        val stateFile = File(dir, PROGRESS_FILE)
                        val pairs = loadDefaultPairs()
                        instance = BigramTrainer(stateFile, pairs)
                        instance!!.loadState(context)
                        instance!!.isInitialized = true
                    }
                }
            }
            return instance!!
        }

        private fun loadDefaultPairs(): List<BigramEntry> {
            // Tier 1: common bigrammes (80 pairs)
            val tier1 = listOf(
                "ab", "ac", "ad", "af", "ag", "aj", "ak", "al", "am", "an",
                "ap", "aq", "ar", "as", "at", "av", "ax", "az", "ba", "be",
                "bi", "bl", "bo", "br", "bu", "ca", "ce", "ch", "ci", "cl",
                "co", "cr", "cu", "da", "de", "di", "do", "dr", "du", "en",
                "er", "es", "et", "eu", "ex", "fa", "fe", "fi", "fl", "fo",
                "fr", "fu", "ga", "ge", "gi", "gl", "go", "gr", "gu", "ha",
                "he", "hi", "ho", "hu", "ia", "ie", "il", "im", "in", "ir",
                "is", "it", "iv", "ix", "iz", "ja", "je", "ji", "jo", "ju"
            ).map { BigramEntry(it, "tier1", repetitions = 3) }

            // Tier 2: less common bigrammes (60 pairs)
            val tier2 = listOf(
                "ka", "ke", "ki", "kl", "ko", "kr", "ku", "la", "le", "li",
                "lo", "lu", "ly", "ma", "me", "mi", "ml", "mo", "mu", "my",
                "na", "ne", "ni", "no", "nu", "ny", "oc", "oi", "ol", "om",
                "on", "op", "or", "os", "ou", "oy", "pa", "pe", "pi", "pl",
                "po", "pr", "pu", "qu", "ra", "re", "ri", "ro", "ru", "sa",
                "sc", "se", "si", "so", "sp", "st", "su", "ta", "te", "ti"
            ).map { BigramEntry(it, "tier2", repetitions = 2) }

            return tier1 + tier2
        }
    }

    private val completedEntries: MutableMap<String, Int> = LinkedHashMap()
    private var completedCount: Int = 0
    private var currentIndex: Int = 0
    private var currentTier: String = "tier1"
    var isInitialized: Boolean = false
        private set

    fun getNext(): BigramEntry? {
        if (currentIndex >= allPairs.size) return null
        val entry = allPairs[currentIndex]
        currentTier = entry.tier
        return entry
    }

    fun currentPair(): BigramEntry? {
        if (currentIndex >= allPairs.size) return null
        return allPairs[currentIndex]
    }

    fun rewind(steps: Int = 1) {
        currentIndex = maxOf(0, currentIndex - steps)
    }

    fun getRecentVStarFiles(dir: File, count: Int = 5): List<File> {
        return dir.listFiles()
            ?.filter { it.extension == "vstar" }
            ?.sortedByDescending { it.lastModified() }
            ?.take(count) ?: emptyList()
    }

    fun completePair(pair: String, tier: String, vstarFile: String) {
        completedEntries[pair] = (completedEntries[pair] ?: 0) + 1
        completedCount++
        currentIndex++
        saveState()
    }

    fun reset() {
        completedEntries.clear()
        completedCount = 0
        currentIndex = 0
        currentTier = "tier1"
        saveState()
    }

    fun getStats(): String {
        return "$completedCount/${allPairs.size}"
    }

    fun getTotalPairs(): Int = allPairs.size

    val progress: Float get() = if (allPairs.isEmpty()) 1.0f else completedCount.toFloat() / allPairs.size

    val isComplete: Boolean get() = completedCount >= allPairs.size

    private fun loadState(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(PREFS_KEY, null) ?: return
            parseProgress(json)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur chargement état: ${e.message}")
        }
    }

    private fun parseProgress(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            completedCount = obj.optInt("completedCount", 0)
            currentIndex = obj.optInt("currentIndex", 0)
            currentTier = obj.optString("currentTier", "tier1")

            if (obj.has("completedEntries")) {
                val entries = obj.getJSONObject("completedEntries")
                val iter = entries.keys()
                while (iter.hasNext()) {
                    val key = iter.next()
                    completedEntries[key] = entries.getInt(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur parsing progress: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            val json = buildJson()
            // Également sauvegarder dans un fichier dans filesDir
            stateFile.parentFile?.mkdirs()
            stateFile.writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Erreur sauvegarde état: ${e.message}")
        }
    }

    private fun buildJson(): String {
        val sb = StringBuilder()
        sb.append("{\"completedCount\":$completedCount,")
        sb.append("\"currentIndex\":$currentIndex,")
        sb.append("\"currentTier\":\"$currentTier\",")
        sb.append("\"completedEntries\":{")
        var first = true
        for ((key, value) in completedEntries) {
            if (!first) sb.append(",")
            sb.append("\"$key\":$value")
            first = false
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun extractJsonString(json: String, start: Int): String? {
        // Simple extraction helper
        val begin = json.indexOf('"', start)
        if (begin < 0) return null
        val end = json.indexOf('"', begin + 1)
        if (end < 0) return null
        return json.substring(begin + 1, end)
    }

    private fun extractJsonInt(json: String, start: Int): Int? {
        val begin = json.indexOf(':', start)
        if (begin < 0) return null
        var end = json.indexOf(',', begin + 1)
        if (end < 0) end = json.indexOf('}', begin + 1)
        if (end < 0) return null
        return json.substring(begin + 1, end).trim().toIntOrNull()
    }
}
