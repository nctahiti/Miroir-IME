package com.parnasse.miroir

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Template — Partition du Miroir, exprimée en deltas.
 *
 * Chaque élément du template se positionne par relation directionnelle
 * à un autre élément + un écart (dx, dy) — comme les points d'un stroke V★.
 *
 * Pas de coordonnées absolues. Pas de layout_weight. Juste des deltas.
 */
sealed class Template {

    /** Peinture pour les lignes de guide */
    companion object {
        val GUIDE_PAINT = Paint().apply {
            color = Color.BLACK  // noir pur — mode DU ne rend que le noir
            strokeWidth = 2.0f   // légèrement plus épais pour compenser l'absence d'anti-aliasing
            style = Paint.Style.STROKE
            isAntiAlias = false  // mode DU : pas de gris
        }
    }

    /**
     * Portée horizontale — lignes de guide tous les [spacingPx] pixels.
     *
     * Delta : chaque ligne = ligne précédente + spacingPx.
     * La première ligne est ancrée au bord supérieur du canvas (y = 0 + spacingPx).
     *
     * @param spacingPx écart vertical entre deux lignes (ex: 500)
     */
    class HorizontalStaff(val spacingPx: Float) : Template() {
        /**
         * Génère la liste des positions Y (deltas cumulés depuis le haut).
         * Chaque position = position précédente + spacingPx.
         */
        fun linePositions(canvasHeight: Int): List<Float> {
            val positions = mutableListOf<Float>()
            var y = spacingPx  // première ligne après le delta initial
            while (y < canvasHeight) {
                positions.add(y)
                y += spacingPx  // delta
            }
            return positions
        }

        /**
         * Dessine la portée sur le canvas.
         */
        fun draw(canvas: Canvas, width: Int, height: Int) {
            val positions = linePositions(height)
            for (y in positions) {
                canvas.drawLine(0f, y, width.toFloat(), y, GUIDE_PAINT)
            }
        }
    }

    /**
     * Région rectangulaire — élément de base d'un template.
     *
     * @param width largeur (positive = pixels, 0 = fill, -1 = wrap)
     * @param height hauteur (positive = pixels, 0 = fill, -1 = wrap)
     */
    data class Region(
        val id: String,
        val width: Int,   // pixels, 0=fill, -1=wrap
        val height: Int,  // pixels, 0=fill, -1=wrap
        val anchorId: String? = null,  // null = racine
        val direction: Direction? = null,  // null = racine
        val deltaX: Float = 0f,
        val deltaY: Float = 0f
    )

    enum class Direction {
        BELOW,    // en dessous de l'ancre (dy positif)
        ABOVE,    // au-dessus de l'ancre (dy négatif)
        RIGHT_OF, // à droite de l'ancre (dx positif)
        LEFT_OF   // à gauche de l'ancre (dx négatif)
    }
}
