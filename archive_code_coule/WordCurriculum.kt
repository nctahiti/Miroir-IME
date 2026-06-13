package com.parnasse.miroir

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Word entry for word-level training curriculum.
 */
data class WordEntry(
    val word: String,
    val tier: String,
    val repetitions: Int = 1,
    val note: String = ""
)

/**
 * Progress state for word curriculum.
 */
data class WordProgress(
    val words: List<WordCompletedEntry> = emptyList()
)

/**
 * A completed word entry.
 */
data class WordCompletedEntry(
    val word: String,
    val tier: String,
    val repetition: Int,
    val completedAt: String,
    val vstarFile: String
)

/**
 * Word trainer — manages word curriculum.
 * Singleton, persists progress to SharedPreferences.
 */
class WordTrainer private constructor(
    private val stateFile: File,
    private val allWords: List<WordEntry>
) {
    companion object {
        private const val TAG = "Miroir/WordTrainer"
        private const val PROGRESS_FILE = "word_progress.json"
        private const val PREFS_NAME = "miroir_words"
        private const val PREFS_KEY = "progress"
        private const val CURRICULUM_FILE = "word_curriculum.json"

        @Volatile
        private var instance: WordTrainer? = null

        fun getInstance(context: Context): WordTrainer {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        val dir = File(context.filesDir, "curriculum")
                        dir.mkdirs()
                        val stateFile = File(dir, PROGRESS_FILE)
                        val words = loadDefaultWords()
                        instance = WordTrainer(stateFile, words)
                        instance!!.loadState(context)
                        instance!!.isInitialized = true
                    }
                }
            }
            return instance!!
        }

        private fun loadDefaultWords(): List<WordEntry> {
            // Common French words for training
            val wordList = listOf(
                "le", "la", "les", "des", "pour", "dans", "avec", "sur",
                "une", "son", "ses", "pas", "nous", "vous", "ils", "elles",
                "mais", "ou", "donc", "car", "rien", "tout", "bien", "mal",
                "faire", "voir", "savoir", "pouvoir", "vouloir", "devoir",
                "parler", "aimer", "vivre", "croire", "mettre", "prendre",
                "comprendre", "entendre", "laisser", "passer", "donner",
                "trouver", "rendre", "venir", "tenir", "apprendre",
                "écrire", "lire", "dire", "aller", "arriver", "partir",
                "rester", "sortir", "entrer", "monter", "descendre",
                "temps", "homme", "femme", "monde", "jour", "nuit",
                "main", "tête", "yeux", "corps", "cœur", "vie",
                "amour", "travail", "argent", "enfants", "maison"
            ).map { WordEntry(it, "tier1", repetitions = 3) }

            // Tier 2: slightly harder words
            val advanced = listOf(
                "précisément", "particulièrement", "vraisemblablement",
                "extraordinaire", "contemporain", "responsabilité",
                "connaissance", "reconnaissance", "indépendance",
                "expérience", "silencieux", "merveilleux"
            ).map { WordEntry(it, "tier2", repetitions = 2) }

            return wordList + advanced
        }
    }

    private val completedWords: MutableMap<String, Int> = LinkedHashMap()
    private var completedCount: Int = 0
    private var currentIndex: Int = 0
    var isInitialized: Boolean = false
        private set

    fun getNext(): WordEntry? {
        if (currentIndex >= allWords.size) return null
        return allWords[currentIndex]
    }

    fun currentWord(): WordEntry? {
        if (currentIndex >= allWords.size) return null
        return allWords[currentIndex]
    }

    fun rewind(steps: Int = 1) {
        currentIndex = maxOf(0, currentIndex - steps)
    }

    fun completeWord(word: String, tier: String, vstarFile: String) {
        completedWords[word] = (completedWords[word] ?: 0) + 1
        completedCount++
        currentIndex++
        saveState()
    }

    fun reset() {
        completedWords.clear()
        completedCount = 0
        currentIndex = 0
        saveState()
    }

    fun getStats(): String {
        return "$completedCount/${allWords.size}"
    }

    fun getTotalWords(): Int = allWords.size

    val progress: Float get() = if (allWords.isEmpty()) 1.0f else completedCount.toFloat() / allWords.size

    val isComplete: Boolean get() = completedCount >= allWords.size

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

            if (obj.has("completedWords")) {
                val entries = obj.getJSONObject("completedWords")
                val iter = entries.keys()
                while (iter.hasNext()) {
                    val key = iter.next()
                    completedWords[key] = entries.getInt(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur parsing progress: ${e.message}")
        }
    }

    private fun saveState() {
        try {
            val json = buildJson()
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
        sb.append("\"completedWords\":{")
        var first = true
        for ((key, value) in completedWords) {
            if (!first) sb.append(",")
            sb.append("\"$key\":$value")
            first = false
        }
        sb.append("}}")
        return sb.toString()
    }

    private fun extractJsonString(json: String, start: Int): String? {
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
