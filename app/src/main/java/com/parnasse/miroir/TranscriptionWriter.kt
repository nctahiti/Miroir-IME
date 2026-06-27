package com.parnasse.miroir

import android.util.Log
import java.io.File

/**
 * TranscriptionWriter — Source unique de vérité pour le texte reconnu.
 *
 * Écrit chaque mot reconnu immédiatement dans un fichier compagnon .transcription
 * (append-only, format snapY=YYY  mot). L'interface lit ce fichier pour afficher
 * le texte — plus de variable volatile accumulatedText.
 *
 * Format :
 *   # note_20260614.transcription
 *   # interligne=70
 *   ---
 *   snapY=520  Je
 *   snapY=525  vois
 *   snapY=640  les mots
 *
 * Les mots sont triés par snapY à la lecture, puis regroupés en lignes
 * visuelles (gap > IL/2 = nouvelle ligne).
 */
class TranscriptionWriter(
    private val noteDir: File,
    private val noteBaseName: String,    // ex: "note_20260614-002021"
    var lineHeight: Float = 70f
) {
    companion object {
        private const val TAG = "Miroir/TranscriptionWriter"
        private const val SEPARATOR = "---"
        private const val LINE_PREFIX = "snapY="
    }

    /** Fichier .transcription associé à la note courante */
    val file: File
        get() = File(noteDir, "$noteBaseName.transcription")

    // ── Écriture ──────────────────────────────────────────────────────

    /**
     * Écrit un mot dans le .transcription (append-only).
     * Crée le fichier avec le header si première écriture.
     *
     * @param snapY Position verticale du mot (centre Y du groupe)
     * @param text  Texte reconnu par ML Kit
     * @param orderIndex Ordre d'écriture (index du groupe, pour tri stable)
     */
    fun writeWord(snapY: Float, text: String, orderIndex: Int = 0) {
        try {
            if (!file.exists()) {
                noteDir.mkdirs()
                file.writeText(buildHeader())
            }
            val line = "$LINE_PREFIX${snapY.toInt()}  order=$orderIndex  $text\n"
            file.appendText(line)
            Log.d(TAG, "✍️ Écrit: $line.trim()")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur écriture transcription: ${e.message}")
        }
    }

    /**
     * Réécrit tout le fichier avec une liste complète de mots.
     * Utilisé après un déplacement (mode édition) ou une sauvegarde complète.
     */
    fun rewriteAll(words: List<Triple<Float, Int, String>>) {
        try {
            noteDir.mkdirs()
            val sb = StringBuilder(buildHeader())
            // Trier par snapY puis par order pour l'ordre visuel + écriture
            val sorted = words.sortedWith(compareBy({ it.first }, { it.second }))
            for ((snapY, order, text) in sorted) {
                sb.append("$LINE_PREFIX${snapY.toInt()}  order=$order  $text\n")
            }
            file.writeText(sb.toString())
            Log.i(TAG, "📝 Transcription réécrite: ${sorted.size} mots")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur réécriture transcription: ${e.message}")
        }
    }

    // ── Lecture ────────────────────────────────────────────────────────

    /**
     * Lit toutes les entrées du .transcription.
     * Déduplique par orderIndex (garde la dernière entrée → réactivation sans doublon).
     * @return Liste de Triple (snapY, orderIndex, texte)
     */
    fun readAll(): List<Triple<Float, Int, String>> {
        if (!file.exists()) return emptyList()
        return try {
            val all = file.readLines()
                .filter { it.startsWith(LINE_PREFIX) && !it.startsWith("#") }
                .mapNotNull { parseLine(it) }
            // Déduplication : garder la dernière entrée par orderIndex
            // (la réactivation d'un groupe réécrit le mot → remplace l'ancien)
            all.groupBy { it.second }        // group by orderIndex
               .mapValues { it.value.last() } // keep last entry per orderIndex
               .values.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture transcription: ${e.message}")
            emptyList()
        }
    }

    /**
     * Lit et retourne le texte complet dans l'ORDRE D'ÉCRITURE,
     * regroupé en lignes visuelles (détection de saut de ligne via snapY).
     * Le snapY sert UNIQUEMENT à détecter les changements de ligne,
     * PAS à ordonner les mots.
     */
    fun getOrderedText(): String {
        val entries = readAll()
        if (entries.isEmpty()) return ""

        // Trier par ordre d'écriture SEULEMENT (pas par snapY !)
        val sorted = entries.sortedBy { it.second }  // it.second = order

        // Regrouper en lignes visuelles : détecter les sauts de snapY
        val lines = mutableListOf<MutableList<String>>()
        var currentLine = mutableListOf<String>()
        var lastSnapY: Float? = null

        for ((snapY, _, text) in sorted) {
            if (lastSnapY != null &&
                kotlin.math.abs(snapY - lastSnapY!!) > lineHeight * 0.5f &&
                currentLine.isNotEmpty()) {
                lines.add(currentLine)
                currentLine = mutableListOf()
            }
            currentLine.add(text)
            lastSnapY = snapY
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        return lines.joinToString("\n") { it.joinToString(" ") }
    }

    /**
     * Retourne la liste des mots dans l'ordre d'écriture (pas trié par snapY).
     */
    fun getOrderedWords(): List<String> {
        return readAll()
            .sortedBy { it.second }  // order d'écriture
            .map { it.third }
    }

    /** @return true si le fichier .transcription existe */
    fun exists(): Boolean = file.exists()

    /** Supprime le fichier .transcription (pour nouvelle page) */
    fun delete() {
        try { file.delete() } catch (_: Exception) {}
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun buildHeader(): String {
        return "# $noteBaseName.transcription\n" +
               "# interligne=$lineHeight\n" +
               "$SEPARATOR\n"
    }

    private fun parseLine(line: String): Triple<Float, Int, String>? {
        // Format: "snapY=520  order=0  les mots"
        val parts = line.removePrefix(LINE_PREFIX).split("  ", limit=3)
        if (parts.size < 3) return null
        val snapY = parts[0].toFloatOrNull() ?: return null
        val orderStr = parts[1].removePrefix("order=")
        val order = orderStr.toIntOrNull() ?: 0
        val text = parts[2].trim()
        return Triple(snapY, order, text)
    }
}
