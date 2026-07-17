package com.parnasse.miroir

import android.view.View

/**
 * Implémentation EPD fallback pour appareils non-Boox.
 * Toutes les opérations sont des no-ops (l'écran standard n'a pas d'EPD).
 */
class AndroidEpdPort(private val view: View) : EpdPort {

    override fun setHandwritingPenState(on: Boolean) {}
    override fun enablePost(on: Boolean) {}

    override fun setDefaultMode(mode: DisplayMode) {
        // No-op : écran standard, pas de mode EPD
    }

    override fun refresh(mode: DisplayMode) {
        // Sur Android standard, on peut forcer un invalidate
        view.postInvalidate()
    }

    override fun enterScribble() {}
    override fun leaveScribble() {}
}
