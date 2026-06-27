package com.parnasse.miroir

/**
 * La « prise » EPD du séquenceur : quatre gestes primitifs, SANS aucune dépendance
 * Android ni Onyx.
 *
 * - L'implémentation réelle [OnyxEpdPort] délègue à `EpdController` du SDK Onyx.
 * - Un faux (FakeEpdPort, en test) enregistre la séquence d'appels.
 *
 * C'est ce découplage qui honore la Règle d'Or : tester la régression du séquenceur
 * sans la tablette.
 */
interface EpdPort {
    /** setScreenHandWritingPenState(view, on?1:0) */
    fun setHandwritingPenState(on: Boolean)

    /** enablePost(view, on?1:0) */
    fun enablePost(on: Boolean)

    /** setViewDefaultUpdateMode(view, mode) — change le mode PAR DÉFAUT de la vue. */
    fun setDefaultMode(mode: DisplayMode)

    /** refreshScreen(view, mode) — rafraîchissement PONCTUEL (ne change pas le mode par défaut). */
    fun refresh(mode: DisplayMode)
}
