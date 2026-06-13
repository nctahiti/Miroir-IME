package com.parnasse.miroir

import android.app.Application
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Initialisation de l'application — contourne les restrictions
 * hidden API pour que le SDK Onyx puisse enregistrer la View
 * auprès du système de routage stylo du Boox.
 *
 * Le SDK Onyx utilise la réflexion pour appeler des méthodes
 * hidden de `ViewUpdateHelper` (setScreenHandWritingPenState, etc.)
 * qui sont bloquées sur Android 9+.
 *
 * HiddenApiBypass permet de les débloquer.
 */
class MiroirApp : Application() {

    companion object {
        private const val TAG = "Miroir/App"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "=== MIROIR APP INIT ===")
        bypassHiddenApi()
    }

    private fun bypassHiddenApi() {
        try {
            // Exempter toutes les méthodes du SDK Onyx
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/onyx/ViewUpdateHelper;",
                "Landroid/onyx/epd/EpdModeManager;",
                "Landroid/view/View;",
                "Lcom/onyx/android/sdk/pen/TouchHelper;"
            )
            Log.i(TAG, "✓ Hidden API bypass activé")
        } catch (e: Exception) {
            Log.w(TAG, "⚠ Hidden API bypass échoué: ${e.message}")
        }
    }
}
