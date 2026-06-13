package com.parnasse.miroir

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * OverlayPermissionActivity — demande la permission SYSTEM_ALERT_WINDOW
 * à l'utilisateur au premier lancement, puis démarre le service.
 */
class OverlayPermissionActivity : AppCompatActivity() {

    companion object {
        private const val OVERLAY_PERMISSION_REQUEST = 1234
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (checkAndStartService()) return

        // UI construite sans layout XML
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }

        val tv = TextView(this).apply {
            text = "Miroir a besoin de l'autorisation d'affichage par-dessus " +
                   "les autres applications pour superposer la surface de capture."
            textSize = 18f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(tv)

        val btn = Button(this).apply {
            text = "Accorder la permission"
            textSize = 20f
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST)
            }
        }
        root.addView(btn)

        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST) {
            checkAndStartService()
        }
    }

    private fun checkAndStartService(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) return false

        startForegroundService(Intent(this, MiroirService::class.java))
        finish()
        return true
    }
}
