package com.parnasse.miroir

import android.graphics.RectF

/**
 * InkGroup — unité de groupement logique de strokes.
 *
 * Un groupe représente un mot, une ligne, ou une phrase — selon le niveau
 * de granularité choisi par l'algorithme de groupement (blob d'absorption).
 *
 * Cycle de vie :
 *   STORED    → sur disque, pas en cache (déchargé après inactivité)
 *   LOADED    → en cache RAM, chargé au survol, non sélectionné
 *   SELECTED  → en cache, sélectionné par hover long (>1s), phare actif

 *
 * Règles :
 *   - Un seul groupe SELECTED à la fois
 *   - Un groupe STORED peut être chargé (survol)
 *   - Un groupe LOADED → SELECTED après 1s de hover maintenu
 *
 * @param id              UUID du groupe
 * @param state           État courant
 * @param strokeIds       IDs des strokes appartenant à ce groupe
 * @param transcription   Texte transcrit (null si pas encore transcrit)
 * @param confidence      Confiance de la transcription [0.0, 1.0]
 * @param bounds          Boîte englobante de tous les strokes du groupe
 * @param createdAt       Timestamp de création (epoch ms)
 * @param modifiedAt      Timestamp de dernière modification (epoch ms)
 * @param groupLevel      Niveau de groupement (WORD, LINE, PARAGRAPH)
 */
data class InkGroup(
    val id: String,
    var state: GroupState = GroupState.LOADED,
    val strokeIds: MutableList<Long> = mutableListOf(),
    var transcription: String? = null,
    var confidence: Float = 0f,
    val bounds: RectF = RectF(),
    val createdAt: Long = System.currentTimeMillis(),
    var modifiedAt: Long = System.currentTimeMillis(),
    val groupLevel: GroupLevel = GroupLevel.WORD,
    /** orderIndex dans la transcription (seq de groupSequenceCounter), null si pas encore inféré */
    var orderIndex: Int? = null
) {
    /** Nombre de strokes dans le groupe */
    val strokeCount: Int get() = strokeIds.size

    /** true si le groupe est encore modifiable */
    val isModifiable: Boolean
        get() = state == GroupState.LOADED || state == GroupState.SELECTED

    /** true si la transcription est disponible */
    val hasTranscription: Boolean
        get() = !transcription.isNullOrBlank()

    companion object {
        /** Crée un nouveau groupe LOADED avec un UUID aléatoire */
        fun create(groupLevel: GroupLevel = GroupLevel.WORD): InkGroup =
            InkGroup(
                id = java.util.UUID.randomUUID().toString().take(8),
                groupLevel = groupLevel
            )
    }
}

/**
 * État d'un groupe de strokes.
 *
 * STORED    — sur disque, pas en cache RAM (déchargé après 1s d'inactivité)
 * LOADED    — en cache, chargé au survol du stylet, non sélectionné
 * SELECTED  — en cache, sélectionné par hover maintenu > 1s (phare actif)
 * DELEGATED — page déléguée au Cœur (ne concerne pas les strokes directs)
 */
enum class GroupState {
    STORED,    // sur disque, pas en cache
    LOADED,    // en cache, chargé au survol, non sélectionné
    SELECTED   // en cache, sélectionné par hover maintenu (phare actif)
    // Note: DELEGATED n'est pas un état de groupe mais de session/page globale
}

/**
 * Niveau de granularité du groupement.
 */
enum class GroupLevel {
    WORD,
    LINE,
    PARAGRAPH
}
