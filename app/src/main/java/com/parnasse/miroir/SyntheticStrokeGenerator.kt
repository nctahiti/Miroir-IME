package com.parnasse.miroir

import android.graphics.PointF
import kotlin.math.*

/**
 * SyntheticStrokeGenerator — Générateur de strokes cursifs procéduraux.
 * ====================================================================
 * Produit des InkStroke synthétiques pour un mot donné, calibrés
 * sur l'interligne du template. Approche simple (sinusoïde par lettre)
 * en attendant le modèle Alex Graves.
 *
 * Usage :
 *   val gen = SyntheticStrokeGenerator(lineHeight, blobSpacingX)
 *   val strokes = gen.generate("Bonjour", anchorX, anchorY)
 *   // strokes = liste de StrokeRecord prêts à être injectés dans le registre
 *
 * ⚠️ CONFLIT POTENTIEL AVEC LE NETTOYAGE DES LABELS
 * ─────────────────────────────────────────────────
 * La génération de strokes est déclenchée par loadFromMdm() lorsque
 * le label MDM n'est pas trouvé dans groupLabels (MiroirEngine.kt:1007).
 * Si cleanLabelForMdm() altère le label (ex: supprime le "l" de "l'eau"
 * via la regex \\b\\p{L}\\b), le MDM sauvegardé contient un label tronqué
 * qui ne matche plus le label original dans groups.json → strokes en double.
 *
 * RÈGLE : cleanLabelForMdm() NE doit JAMAIS supprimer de caractères
 * alphabétiques, même isolés. L'apostrophe est une frontière de mot
 * pour \\b — toute regex utilisant \\b doit en tenir compte.
 *
 * Propriétés :
 *   - Une sinusoïde par lettre (boucle cursive)
 *   - Pas de lever de plume dans un mot (un seul trait continu)
 *   - Hauteur calibrée sur lineHeight
 *   - Espacement entre lettres : lineHeight * 0.5
 *   - Espacement entre mots : blobSpacingX * 2
 */
class SyntheticStrokeGenerator(
    private val lineHeight: Float,      // hauteur d'interligne (pixels)
    private val blobSpacingX: Float,    // distance horizontale entre groupes
    val marginX: Float = 60f            // marge gauche (publique pour loadFromMdm)
) {
    companion object {
        private const val LETTER_WIDTH_RATIO = 0.55f   // largeur lettre / lineHeight
        private const val LETTER_GAP_RATIO = 0.15f     // espace entre lettres / lineHeight
        private const val WORD_GAP_RATIO = 2.5f        // espace entre mots / letterWidth
        private const val STROKE_POINTS_PER_LETTER = 16 // points de résolution par lettre
        private const val PRESSURE_NOMINAL = 0.7f       // pression simulée
        private const val TILT_NOMINAL = 0.4f           // inclinaison simulée (radians)
        private const val ORIENTATION_NOMINAL = (-PI / 4).toFloat() // orientation stylet
        private const val TOOL_STYLUS = 2               // MotionEvent.TOOL_TYPE_STYLUS
        private const val ACTION_DOWN = 0               // MotionEvent.ACTION_DOWN
        private const val ACTION_MOVE = 2               // MotionEvent.ACTION_MOVE
        private const val ACTION_UP = 1                 // MotionEvent.ACTION_UP
    }

    /**
     * Largeur estimée d'un mot (pour le calcul de retour à la ligne).
     */
    fun estimateWidth(word: String): Float {
        if (word.isEmpty()) return 0f
        val letterW = lineHeight * LETTER_WIDTH_RATIO
        val gap = lineHeight * LETTER_GAP_RATIO
        return word.length * (letterW + gap) - gap
    }

    /**
     * Génère une liste de StrokeRecord pour un mot.
     * Chaque StrokeRecord = un trait continu (DOWN→MOVE...→UP).
     *
     * @param word     Le mot à tracer (ex: "Bonjour")
     * @param anchorX  Position X de l'ancre (début du mot)
     * @param anchorY  Position Y de l'ancre (ligne de base)
     * @return Liste de StrokeRecord (généralement 1 par mot)
     */
    fun generate(word: String, anchorX: Float, anchorY: Float): List<StrokeRecord> {
        if (word.isEmpty()) return emptyList()

        val records = mutableListOf<StrokeRecord>()
        val letterW = lineHeight * LETTER_WIDTH_RATIO
        val letterH = lineHeight * 0.45f  // hauteur de la boucle (au-dessus de la ligne)
        val gap = lineHeight * LETTER_GAP_RATIO
        val pointsPerLetter = STROKE_POINTS_PER_LETTER
        val totalPoints = word.length * pointsPerLetter

        // Un seul trait cursif pour tout le mot
        val points = mutableListOf<Pair<Float, Float>>()
        val timestamps = mutableListOf<Long>()
        val pressures = mutableListOf<Float>()

        var t = 0L
        val tStep = 12L  // ~12ms entre points → ~200ms par lettre

        for ((ci, ch) in word.withIndex()) {
            val baseX = anchorX + ci * (letterW + gap)

            for (pi in 0 until pointsPerLetter) {
                val frac = pi.toFloat() / (pointsPerLetter - 1)  // 0.0 → 1.0

                // Position X : avance linéaire dans la lettre
                val x = baseX + frac * letterW

                // Position Y : sinusoïde pour la boucle cursive
                // La boucle monte (au-dessus de la ligne) puis descend
                val yOffset = sin(frac * PI.toFloat() * 2f) * letterH
                // Léger décalage descendant pour l'effet "cursif penché"
                val slant = frac * letterH * 0.15f
                val y = anchorY - yOffset + slant

                points.add(Pair(x, y))
                timestamps.add(t)
                pressures.add(PRESSURE_NOMINAL + (sin(frac * PI.toFloat()) * 0.15f).toFloat())
                t += tStep
            }
        }

        if (points.isNotEmpty()) {
            val record = StrokeRecord(
                id = java.util.UUID.randomUUID().toString(),
                points = points,
                timestamps = timestamps,
                pressures = pressures,
                source = "synthetic"
            )
            records.add(record)
        }

        return records
    }

    /**
     * Convertit un StrokeRecord en InkStroke (pour compatibilité avec GroupManager).
     */
    fun toInkStroke(record: StrokeRecord, strokeId: Long, sessionId: Long = 0): InkStroke {
        val inkPoints = record.points.mapIndexed { i, (x, y) ->
            InkPoint(
                x = x,
                y = y,
                pressure = record.pressures.getOrElse(i) { PRESSURE_NOMINAL },
                tilt = TILT_NOMINAL,
                orientation = ORIENTATION_NOMINAL,
                distance = 0f,
                timestamp = record.timestamps.getOrElse(i) { i * 12L },
                action = when (i) {
                    0 -> ACTION_DOWN
                    record.points.size - 1 -> ACTION_UP
                    else -> ACTION_MOVE
                },
                toolType = TOOL_STYLUS
            )
        }
        return InkStroke(
            id = strokeId,
            sessionId = sessionId,
            points = inkPoints.toMutableList(),
            isSealed = true
        ).also { it.endNano = record.timestamps.lastOrNull() ?: 0L }
    }
}
