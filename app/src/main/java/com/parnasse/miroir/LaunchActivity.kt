package com.parnasse.miroir

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * LaunchActivity — point d'entrée de l'icône Miroir.
 * Ouvre l'IME directement. Si l'IME n'est pas activé, redirige vers les paramètres.
 */
class LaunchActivity : Activity() {

    companion object {
        private const val IME_ID = "com.parnasse.miroir/.MiroirIME"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.id == IME_ID }

        if (enabled) {
            // IME activé → le montrer
            imm.showInputMethodPicker()
            Toast.makeText(this, "✍️ Choisis « Miroir manuscrit »", Toast.LENGTH_SHORT).show()
        } else {
            // Pas encore activé → paramètres
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "⚙ Active « Miroir manuscrit » dans la liste", Toast.LENGTH_LONG).show()
        }

        finish()
    }
}
