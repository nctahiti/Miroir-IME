package com.parnasse.miroir

import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * BlobAbsorber — algorithme de groupement paramétré.
 *
 * Décide si un nouveau stroke doit être absorbé par un groupe existant
 * ou déclencher la création d'un nouveau groupe.
 *
 * Trois critères, tous doivent être satisfaits pour l'absorption :
 *   1. Distance spatiale ≤ seuil (pixels)
 *   2. Chevauchement horizontal ≥ seuil (%)
 *   3. Distance temporelle ≤ seuil (ms)
 *
 * Chaque critère est pondérable selon le niveau de groupement :
 *   WORD  — seuils serrés (écriture latine standard)
 *   LINE  — seuils relâchés horizontalement
 *   PARAGRAPH — seuils larges, priorité à la continuité
 *
 * Utilisé par GroupManager.onStrokeSealed() pour décider si le stroke
 * va dans le groupe LOADED ou crée un nouveau groupe.
 */
class BlobAbsorber(private var params: BlobParams = BlobParams()) {

    /**
     * Met à jour les paramètres (appelé depuis la calibration).
     */
    fun updateParams(newParams: BlobParams) {
        params = newParams
    }

    /**
     * Vérifie si un stroke peut être absorbé par un groupe existant.
     *
     * @param group       Groupe candidat
     * @param strokeBounds Boîte englobante du nouveau stroke
     * @param strokeTime  Timestamp de début du stroke (epoch ms)
     * @return            true si le stroke doit être absorbé
     */
    fun shouldAbsorb(
        group: InkGroup,
        strokeBounds: RectF,
        strokeTime: Long
    ): Boolean {
        // Groupe vide → toujours absorber
        if (group.bounds.isEmpty) return true

        // Groupe non modifiable → refuser
        if (!group.isModifiable) return false

        // Critère 1 : distance spatiale
        val dist = spatialDistance(group.bounds, strokeBounds)
        val spatialOk = dist <= spatialThreshold()

        // Critère 2 : chevauchement horizontal
        val overlap = horizontalOverlap(group.bounds, strokeBounds)
        val overlapOk = overlap >= overlapThreshold()

        // Critère 3 : distance temporelle
        val temporalGap = strokeTime - group.modifiedAt
        val temporalOk = temporalGap <= params.temporalDistanceMs

        val absorb = spatialOk && overlapOk && temporalOk
        Log.d("BlobAbsorber", "shouldAbsorb: dist=${dist.toInt()}px($spatialOk) ov=$overlap%($overlapOk) gap=${temporalGap}ms/${params.temporalDistanceMs}ms($temporalOk) → ${if (absorb) "ABSORB" else "NOUVEAU"} (${group.strokeCount}s)")
        return absorb
    }

    /**
     * Calcule la distance spatiale entre deux boîtes englobantes.
     * Retourne 0 si les boîtes se chevauchent.
     */
    private fun spatialDistance(a: RectF, b: RectF): Float {
        val dx = if (a.right < b.left) b.left - a.right
                 else if (b.right < a.left) a.left - b.right
                 else 0f

        val dy = if (a.bottom < b.top) b.top - a.bottom
                 else if (b.bottom < a.top) a.top - b.bottom
                 else 0f

        return max(dx, dy)  // distance de Manhattan max (Chebyshev)
    }

    /**
     * Calcule le % de chevauchement horizontal entre deux boîtes.
     * Retourne 0..100.
     */
    private fun horizontalOverlap(a: RectF, b: RectF): Int {
        val overlapLeft  = max(a.left, b.left)
        val overlapRight = min(a.right, b.right)
        val overlapWidth = overlapRight - overlapLeft

        if (overlapWidth <= 0) return 0

        val newWidth = b.width()
        if (newWidth <= 0) return 0

        return (overlapWidth / newWidth * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * Seuil spatial adapté au niveau de groupement.
     */
    private fun spatialThreshold(): Float = when (params.groupLevel) {
        GroupLevel.WORD      -> params.spatialDistancePx
        GroupLevel.LINE      -> params.spatialDistancePx * 1.5f
        GroupLevel.PARAGRAPH -> params.spatialDistancePx * 3.0f
    }

    /**
     * Seuil de chevauchement adapté au niveau de groupement.
     */
    private fun overlapThreshold(): Int = when (params.groupLevel) {
        GroupLevel.WORD      -> 0  // mots : distance spatiale uniquement (pas de chevauchement)
        GroupLevel.LINE      -> max(params.minOverlapPercent - 20, 0)
        GroupLevel.PARAGRAPH -> 0  // pas de contrainte de chevauchement
    }

    companion object {
        /** Calcule la boîte englobante d'un stroke */
        fun computeBounds(stroke: InkStroke): RectF {
            if (stroke.points.isEmpty()) return RectF()

            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
            for (p in stroke.points) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
            return RectF(minX, minY, maxX, maxY)
        }
    }
}
