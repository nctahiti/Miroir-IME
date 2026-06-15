package com.parnasse.miroir

/**
 * Un point d'encre brut — aucune altération, aucune interpolation.
 * Données préservées exactement telles que le hardware les a produites.
 *
 * @param x          Position horizontale en pixels (espace écran absolu)
 * @param y          Position verticale en pixels (espace écran absolu)
 * @param pressure   Pression [0.0, 1.0+] — valeur brute, non normalisée
 * @param tilt       Inclinaison du stylet en radians [0, π/2]
 * @param orientation Orientation du stylet en radians [-π, π]
 * @param distance   Distance stylet/écran en survol (0.0 = contact)
 * @param timestamp  Epoch nanoseconde — base de temps hardware, non systémique
 * @param action     Action MotionEvent brute (DOWN, MOVE, UP, HOVER, CANCEL)
 * @param toolType   Type de pointeur (STYLUS, ERASER, FINGER, MOUSE)
 */
data class InkPoint(
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tilt: Float,
    val orientation: Float,
    val distance: Float,
    val timestamp: Long,       // nanosecondes — résolution maximale
    val action: Int,           // MotionEvent.ACTION_* constant brute
    val toolType: Int          // MotionEvent.TOOL_TYPE_* constant brute
) {
    companion object {
        // Actions sémantiques — mappées sur les constantes Android
        const val ACTION_DOWN   = 0   // MotionEvent.ACTION_DOWN
        const val ACTION_MOVE   = 2   // MotionEvent.ACTION_MOVE
        const val ACTION_UP     = 1   // MotionEvent.ACTION_UP
        const val ACTION_HOVER  = 7   // MotionEvent.ACTION_HOVER_MOVE
        const val ACTION_CANCEL = 3   // MotionEvent.ACTION_CANCEL

        const val TOOL_STYLUS   = 2   // MotionEvent.TOOL_TYPE_STYLUS
        const val TOOL_ERASER   = 4   // MotionEvent.TOOL_TYPE_ERASER
        const val TOOL_FINGER   = 1   // MotionEvent.TOOL_TYPE_FINGER
    }
}
