package com.parnasse.miroir

import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * ⛪ L'IDENTITÉ D'UNE PAGE — une seule main pour les deux liens (14/09/2026).
 *
 * « Aucune capture sans identité ni place » (le Capitaine). Une page porte son
 * adresse (note_id) gravée à DEUX endroits, et les deux doivent parler d'une
 * même voix :
 *   • `groups.json` → champ `note_id` — la vérité du RENDU (le Moteur,
 *     `findPageByNoteId` : quelle encre pour quelle note).
 *   • le fichier `.note_id` — le pont de la MOISSON (le Semeur du Cœur lit la
 *     copie SD pour joindre la page à sa note, et regrave la lie).
 *
 * Le Moteur (MiroirEngine) et l'IME (MiroirIME) avaient chacun leur copie —
 * et l'IME n'en gravait qu'UN : d'où les divergences silencieuses du 12/09
 * (liens internes sur des maisons mortes, la moisson qui re-résout par
 * titre/position). Une seule main désormais : ce fichier.
 */
object IdentitePage {
    private const val TAG = "Miroir/Identite"

    /** Lit la maison d'une page : `groups.json` d'abord (la vérité du Moteur),
     *  puis le fichier `.note_id` (le pont) s'il se tait. */
    fun lire(pageDir: File): String? {
        try {
            val gf = File(pageDir, "groups.json")
            if (gf.exists()) {
                val v = JSONObject(gf.readText()).optString("note_id", null)
                if (!v.isNullOrEmpty()) return v
            }
        } catch (_: Exception) { }
        return try {
            val f = File(pageDir, ".note_id")
            if (f.exists()) f.readText().trim().takeUnless { it.isEmpty() } else null
        } catch (_: Exception) { null }
    }

    /** Grave l'adresse dans LES DEUX liens — ensemble, jamais l'un sans l'autre.
     *  (L'IME gravait `groups.json` seul : la moisson ne l'apprenait pas.) */
    fun graver(pageDir: File, noteId: String) {
        try {
            val groupsFile = File(pageDir, "groups.json")
            val root = if (groupsFile.exists()) JSONObject(groupsFile.readText()) else JSONObject()
            root.put("note_id", noteId)
            groupsFile.writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "graver: groups.json — ${e.message}")
        }
        try {
            File(pageDir, ".note_id").writeText(noteId)
        } catch (e: Exception) {
            Log.w(TAG, "graver: .note_id — ${e.message}")
        }
    }
}
