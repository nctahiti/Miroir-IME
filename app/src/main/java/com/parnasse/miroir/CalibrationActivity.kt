package com.parnasse.miroir

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.*

/**
 * CalibrationActivity — réglage des paramètres du Miroir.
 *
 * Curseurs :
 *   - Timeout auto-inférence (ms) : délai après le dernier stroke
 *   - Distance spatiale groupe (px) : distance max entre strokes d'un même mot
 *   - Distance temporelle groupe (ms) : délai max entre strokes d'un même mot
 *
 * Persisté dans SharedPreferences, lu par CaptureView au démarrage.
 */
class CalibrationActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "miroir_calibration"
        private const val KEY_AUTO_INFER_DELAY = "auto_infer_delay_ms"
        private const val KEY_SPATIAL_DISTANCE_X = "spatial_distance_x_px"
        private const val KEY_SPATIAL_DISTANCE_Y = "spatial_distance_y_px"
        private const val KEY_TEMPORAL_DISTANCE = "temporal_distance_ms"
        private const val KEY_LONG_HOVER_DELAY = "long_hover_delay_ms"
        private const val KEY_ABSORB_CONTACTS = "absorb_contacts"

        const val DEFAULT_AUTO_INFER_DELAY = 1500L
        const val DEFAULT_SPATIAL_DISTANCE_X = 40f
        const val DEFAULT_SPATIAL_DISTANCE_Y = 70f
        const val DEFAULT_TEMPORAL_DISTANCE = 800L
        const val DEFAULT_LONG_HOVER_DELAY = 1000L
        const val DEFAULT_ABSORB_CONTACTS = 1  // 1 = binaire, 10 = amorti

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getAutoInferDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)

        fun getSpatialDistanceX(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)

        fun getSpatialDistanceY(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)

        fun getTemporalDistance(ctx: Context): Long =
            prefs(ctx).getLong(KEY_TEMPORAL_DISTANCE, DEFAULT_TEMPORAL_DISTANCE)

        fun getLongHoverDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_LONG_HOVER_DELAY, DEFAULT_LONG_HOVER_DELAY)

        fun getAbsorbContacts(ctx: Context): Int =
            prefs(ctx).getInt(KEY_ABSORB_CONTACTS, DEFAULT_ABSORB_CONTACTS)
    }

    private lateinit var delaySeek: SeekBar
    private lateinit var spatialXSeek: SeekBar
    private lateinit var spatialYSeek: SeekBar
    private lateinit var temporalSeek: SeekBar
    private lateinit var hoverSeek: SeekBar
    private lateinit var absorbSeek: SeekBar
    private lateinit var delayLabel: TextView
    private lateinit var spatialXLabel: TextView
    private lateinit var spatialYLabel: TextView
    private lateinit var temporalLabel: TextView
    private lateinit var hoverLabel: TextView
    private lateinit var absorbLabel: TextView
    private var testView: CaptureView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs(this)
        val currentDelay = p.getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)
        val currentSpatialX = p.getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)
        val currentSpatialY = p.getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)
        val currentTemporal = p.getLong(KEY_TEMPORAL_DISTANCE, DEFAULT_TEMPORAL_DISTANCE)
        val currentHover = p.getLong(KEY_LONG_HOVER_DELAY, DEFAULT_LONG_HOVER_DELAY)
        val currentAbsorb = p.getInt(KEY_ABSORB_CONTACTS, DEFAULT_ABSORB_CONTACTS)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        // Titre
        root.addView(TextView(this).apply {
            text = "⚙ Paramètres du Miroir"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })

        // Timeout auto-inférence
        delayLabel = addSlider(root, "Timeout inférence", 500, 5000, currentDelay.toInt(), "ms")
        delaySeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Distance spatiale X (horizontale) — même échelle que Y
        spatialXLabel = addSlider(root, "Distance spatiale X (↔)", 5, 200, currentSpatialX.toInt(), "px")
        spatialXSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Distance spatiale Y (verticale) — même échelle que X
        spatialYLabel = addSlider(root, "Distance spatiale Y (↕)", 5, 200, currentSpatialY.toInt(), "px")
        spatialYSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Distance temporelle
        temporalLabel = addSlider(root, "Distance temporelle groupe", 100, 3000, currentTemporal.toInt(), "ms")
        temporalSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Survol long (réactivation)
        hoverLabel = addSlider(root, "Survol long (réactivation)", 300, 3000, currentHover.toInt(), "ms")
        hoverSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Densité d'absorption (contacts requis)
        absorbLabel = addSlider(root, "Densité absorption (contacts)", 1, 50, currentAbsorb, "contacts")
        absorbSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // ── Boutons ────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(4))
        }
        btnRow.addView(Button(this).apply {
            text = "Réinitialiser"
            setOnClickListener {
                delaySeek.progress = DEFAULT_AUTO_INFER_DELAY.toInt() - 500
                spatialXSeek.progress = DEFAULT_SPATIAL_DISTANCE_X.toInt() - 5
                spatialYSeek.progress = DEFAULT_SPATIAL_DISTANCE_Y.toInt() - 5
                temporalSeek.progress = DEFAULT_TEMPORAL_DISTANCE.toInt() - 100
                hoverSeek.progress = DEFAULT_LONG_HOVER_DELAY.toInt() - 300
                absorbSeek.progress = DEFAULT_ABSORB_CONTACTS - 1
                save()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "✓ OK"
            setOnClickListener {
                save()
                finish()
            }
        })
        root.addView(btnRow)

        // ── Zone d'essai (CaptureView réelle avec TouchHelper) ──────────
        val cv = CaptureView(this).apply {
            isBlocnoteMode = true
            onWordGroupCompleted = null  // pas de reco, juste affichage
        }
        testView = cv
        root.addView(cv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // Mettre à jour le délai d'inférence dans la zone d'essai
        val updateTestView = {
            testView?.reloadAutoInferDelay()
            testView?.invalidate()
        }
        delaySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                delayLabel.text = "Timeout inférence : ${v + 500} ms"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putLong(KEY_AUTO_INFER_DELAY, (v + 500).toLong()).apply()
                    updateTestView()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        spatialXSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                spatialXLabel.text = "Distance spatiale X (↔) : ${v + 5} px"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putFloat(KEY_SPATIAL_DISTANCE_X, (v + 5).toFloat()).apply()
                    updateTestView()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        spatialYSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                spatialYLabel.text = "Distance spatiale Y (↕) : ${v + 5} px"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putFloat(KEY_SPATIAL_DISTANCE_Y, (v + 5).toFloat()).apply()
                    updateTestView()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        absorbSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                absorbLabel.text = "Densité absorption (contacts) : ${v + 1}"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putInt(KEY_ABSORB_CONTACTS, v + 1).apply()
                    updateTestView()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        testView?.initTouchHelper()
    }

    override fun onDestroy() {
        testView?.releaseTouchHelper()
        testView = null
        super.onDestroy()
    }

    private fun addSlider(parent: LinearLayout, name: String, min: Int, max: Int, current: Int, unit: String): TextView {
        val label = TextView(this).apply {
            text = "$name : $current $unit"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(2))
            setTextColor(Color.DKGRAY)
        }
        parent.addView(label)

        val seek = SeekBar(this).apply {
            this.max = max - min
            this.progress = current - min
            setPadding(0, 0, 0, dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sk: SeekBar, value: Int, fromUser: Boolean) {
                    label.text = "$name : ${value + min} $unit"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(seek)
        return label
    }

    private fun save() {
        prefs(this).edit()
            .putLong(KEY_AUTO_INFER_DELAY, (delaySeek.progress + 500).toLong())
            .putFloat(KEY_SPATIAL_DISTANCE_X, (spatialXSeek.progress + 5).toFloat())
            .putFloat(KEY_SPATIAL_DISTANCE_Y, (spatialYSeek.progress + 10).toFloat())
            .putLong(KEY_TEMPORAL_DISTANCE, (temporalSeek.progress + 100).toLong())
            .putLong(KEY_LONG_HOVER_DELAY, (hoverSeek.progress + 300).toLong())
            .apply()
        Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
