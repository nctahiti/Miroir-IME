package com.parnasse.miroir

import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * Implémentation réelle de la prise EPD : délègue à `EpdController` du SDK Onyx.
 *
 * Détient la [View] cible — ainsi l'organe ([DisplayController]) ne connaît JAMAIS
 * ni `View` ni `EpdController`. C'est la SEULE pièce qui touche le SDK Onyx.
 *
 * Délégation 1:1, triviale, vérifiée au stylet (pas en JVM). NON câblée au Pas 1 :
 * aucune rive ne l'instancie encore.
 */
class OnyxEpdPort(private val view: View) : EpdPort {

    override fun setHandwritingPenState(on: Boolean) {
        EpdController.setScreenHandWritingPenState(view, if (on) 1 else 0)
    }

    override fun enablePost(on: Boolean) {
        EpdController.enablePost(view, if (on) 1 else 0)
    }

    override fun setDefaultMode(mode: DisplayMode) {
        EpdController.setViewDefaultUpdateMode(view, mode.toOnyx())
    }

    override fun refresh(mode: DisplayMode) {
        EpdController.refreshScreen(view, mode.toOnyx())
    }

    private fun DisplayMode.toOnyx(): UpdateMode = when (this) {
        DisplayMode.DU -> UpdateMode.DU
        DisplayMode.GU -> UpdateMode.GU
        DisplayMode.REGAL -> UpdateMode.REGAL
    }
}
