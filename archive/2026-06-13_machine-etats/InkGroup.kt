package dev.parnasse.inkservice

import android.graphics.RectF

/**
 * InkGroup — unité de groupement logique de strokes.
 *
 * Un groupe représente un mot, une ligne, ou une phrase — selon le niveau
 * de granularité choisi par l'algorithme de groupement (blob d'absorption).
 *
 * Cycle de vie :
 *   ACTIVE   → capture en cours, strokes ajoutables
 *   PENDING  → timeout écoulé, transcription programmée ou en cours
 *   CLOSED   → figé, transcription finale poussée au Cœur
 *   EXPORTED → déplacé hors étagère Miroir, Cœur devient maître
 *
 * Règles :
 *   - Un seul groupe ACTIVE à la fois
 *   - Un groupe CLOSED peut être réactivé (survol long d'un mot)
 *   - La transcription est re-déclenchée à chaque modification
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
    var state: GroupState = GroupState.ACTIVE,
    val strokeIds: MutableList<Long> = mutableListOf(),
    var transcription: String? = null,
    var confidence: Float = 0f,
    val bounds: RectF = RectF(),
    val createdAt: Long = System.currentTimeMillis(),
    var modifiedAt: Long = System.currentTimeMillis(),
    val groupLevel: GroupLevel = GroupLevel.WORD
) {
    /** Nombre de strokes dans le groupe */
    val strokeCount: Int get() = strokeIds.size

    /** true si le groupe est encore modifiable */
    val isModifiable: Boolean
        get() = state == GroupState.ACTIVE || state == GroupState.PENDING

    /** true si la transcription est disponible */
    val hasTranscription: Boolean
        get() = !transcription.isNullOrBlank()

    companion object {
        /** Crée un nouveau groupe ACTIVE avec un UUID aléatoire */
        fun create(groupLevel: GroupLevel = GroupLevel.WORD): InkGroup =
            InkGroup(
                id = java.util.UUID.randomUUID().toString().take(8),
                groupLevel = groupLevel
            )
    }
}

/**
 * État d'un groupe de strokes.
 */
enum class GroupState {
    ACTIVE,
    PENDING,
    CLOSED,
    EXPORTED
}

/**
 * Niveau de granularité du groupement.
 */
enum class GroupLevel {
    WORD,
    LINE,
    PARAGRAPH
}
