package com.parnasse.miroir

import android.view.MotionEvent
import android.view.View

/**
 * Interface de capture stylet — découplée du hardware (Onyx TouchHelper vs Android standard).
 *
 * Permet au Miroir de fonctionner sur n'importe quel appareil Android,
 * avec une implémentation optimisée pour Onyx Boox (raw drawing hardware).
 *
 * Implémentations :
 *   - OnyxStylusPort : TouchHelper + raw drawing (Boox)
 *   - AndroidStylusPort : MotionEvent standard (fallback universel)
 */
interface StylusPort {

    /** Initialise la capture sur la vue cible. */
    fun init(view: View)

    /**
     * Callback appelé à chaque point du stylet.
     * @param x coordonnée X (pixels, dans le repère de la vue)
     * @param y coordonnée Y
     * @param pressure pression [0..1]
     * @param action MotionEvent action (ACTION_DOWN, ACTION_MOVE, ACTION_UP)
     * @param timestamp temps en millisecondes
     */
    fun onStylusPoint(
        x: Float, y: Float,
        pressure: Float,
        action: Int,
        timestamp: Long
    )

    /** Active/désactive la capture (hover detection). */
    fun setActive(active: Boolean)

    /** Libère les ressources. */
    fun release()

    /** @return true si le raw drawing hardware est actif (pas de latence logicielle). */
    fun isRawDrawingActive(): Boolean = false

    /** Interface pour les callbacks de points stylet. */
    interface Callback {
        fun onStylusDown(x: Float, y: Float, pressure: Float, timestamp: Long)
        fun onStylusMove(x: Float, y: Float, pressure: Float, timestamp: Long)
        fun onStylusUp(x: Float, y: Float, pressure: Float, timestamp: Long)
        fun onStylusHoverEnter() {}
        fun onStylusHoverExit() {}
    }
}
