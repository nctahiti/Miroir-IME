package com.parnasse.miroir

import android.graphics.Color
import java.util.UUID

/**
 * StrokeRecord — Représentation mémoire d'un stroke pour l'édition.
 *
 * Stocke les points en coordonnées absolues (décodées des deltas V*)
 * pour permettre le rendu, la sélection et la manipulation.
 */
data class StrokeRecord(
    val id: String = UUID.randomUUID().toString(),
    val points: MutableList<Pair<Float, Float>> = mutableListOf(),
    val timestamps: MutableList<Long> = mutableListOf(),
    val pressures: MutableList<Float> = mutableListOf(),
    val color: Int = Color.BLACK,
    val width: Float = 3f,
    var isDeleted: Boolean = false
) {
    /** Nombre de points encore actifs */
    val activePoints: Int get() = points.size

    /** Boîte englobante du stroke */
    fun bounds(): RectF {
        if (points.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        var x0 = Float.MAX_VALUE; var x1 = Float.MIN_VALUE
        var y0 = Float.MAX_VALUE; var y1 = Float.MIN_VALUE
        for ((x, y) in points) {
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return RectF(x0, y0, x1, y1)
    }

    /** Teste si un point (px, py) est dans le rayon de sélection */
    fun hitTest(px: Float, py: Float, radius: Float = 20f): Boolean {
        for ((x, y) in points) {
            val dist = Math.sqrt(((x - px) * (x - px) + (y - py) * (y - py)).toDouble())
            if (dist < radius) return true
        }
        return false
    }

    /** Supprime les N derniers points (chronologie inversée) */
    fun trimFromEnd(n: Int = 1) {
        val remove = n.coerceIn(1, points.size)
        repeat(remove) {
            if (points.isNotEmpty()) {
                points.removeAt(points.lastIndex)
                timestamps.removeAt(timestamps.lastIndex)
                pressures.removeAt(pressures.lastIndex)
            }
        }
    }

    /** Décale tous les points d'un vecteur (dx, dy) — pour le drag */
    fun translate(dx: Float, dy: Float) {
        for (i in points.indices) {
            points[i] = Pair(points[i].first + dx, points[i].second + dy)
        }
    }
}

/** Rectangle flottant pour les bounds */
data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Modes de capture de Miroir-v2.
 */
enum class CaptureMode(val label: String) {
    CAPTURE("capture"),
    EDIT("édition"),
    EDIT_TEMPORAL("effacement"),
    INSERT("insertion"),
    REVIEW("relecture")
}
