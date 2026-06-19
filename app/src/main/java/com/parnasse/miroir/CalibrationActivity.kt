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
        private const val KEY_BLOB_COLOR = "blob_color"  // ARGB int persiste

        const val DEFAULT_AUTO_INFER_DELAY = 1500L
        const val DEFAULT_SPATIAL_DISTANCE_X = 40f
        const val DEFAULT_SPATIAL_DISTANCE_Y = 70f
        const val DEFAULT_TEMPORAL_DISTANCE = 800L
        const val DEFAULT_LONG_HOVER_DELAY = 1000L
        const val DEFAULT_ABSORB_CONTACTS = 1  // 1 = binaire, 10 = amorti
        const val DEFAULT_BLOB_COLOR = 0x503C3C3C.toInt()  // gris fonce alpha 80

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

        fun getBlobColor(ctx: Context): Int =
            prefs(ctx).getInt(KEY_BLOB_COLOR, DEFAULT_BLOB_COLOR)
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
        spatialXLabel = addSlider(root, "Distance spatiale X (↔)", 5, 500, currentSpatialX.toInt(), "px")
        spatialXSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Distance spatiale Y (verticale) — même échelle que X
        spatialYLabel = addSlider(root, "Distance spatiale Y (↕)", 5, 500, currentSpatialY.toInt(), "px")
        spatialYSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Distance temporelle
        temporalLabel = addSlider(root, "Distance temporelle groupe", 100, 3000, currentTemporal.toInt(), "ms")
        temporalSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Survol long (réactivation)
        hoverLabel = addSlider(root, "Survol long (réactivation)", 50, 3000, currentHover.toInt(), "ms")
        hoverSeek = root.getChildAt(root.childCount - 1) as SeekBar

        // Densité d'absorption (contacts requis)
        absorbLabel = addSlider(root, "Densité absorption (contacts)", 1, 500, currentAbsorb, "contacts")
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
                hoverSeek.progress = (DEFAULT_LONG_HOVER_DELAY.toInt() - 500).coerceAtLeast(0)
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

        // ── Barre d'outils playground (merge + decompose) ──────────
        val playgroundBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        // Bouton 🔗 fusion
        val mergeBtn = TextView(this).apply {
            text = "🔗"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(180, 80, 80, 80))
            gravity = Gravity.CENTER
            setOnClickListener {
                val newState = !(testView?.mergeMode ?: false)
                testView?.mergeMode = newState
                testView?.mergeSourceGroup = null
                this.text = if (newState) "🔗✓" else "🔗"
                this.setBackgroundColor(if (newState)
                    Color.argb(200, 100, 180, 255)
                else Color.argb(180, 80, 80, 80))
                if (newState) {
                    testView?.currentMode = CaptureMode.EDIT
                    testView?.currentMode = CaptureMode.EDIT_TEMPORAL
                    Toast.makeText(this@CalibrationActivity, "🔗 Tapez deux groupes pour les fusionner", Toast.LENGTH_SHORT).show()
                }
            }
        }
        playgroundBar.addView(mergeBtn)
        // Bouton MODE — affiche le mode actif (🚢 CAPTURE / 🔦 EDIT / ⏳ TEMPOREL)
        val modeIndicatorBtn = TextView(this).apply {
            text = "🚢"
            textSize = 28f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.argb(180, 60, 60, 60))
            gravity = Gravity.CENTER
        }
        playgroundBar.addView(modeIndicatorBtn)
        root.addView(playgroundBar)

        // ── Couleur du blob ──────────────────────────────────────────
        val blobColorLabel = TextView(this).apply {
            text = "Couleur du blob"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(4))
            setTextColor(Color.DKGRAY)
        }
        root.addView(blobColorLabel)

        val blobColors = listOf(
            0x503C3C3C.toInt() to "Gris",      // gris fonce (defaut)
            0x50222222.toInt() to "Noir",       // presque noir
            0x50444488.toInt() to "Bleu",       // bleu discret
            0x50664433.toInt() to "Sepia",      // brun chaud
            0x50336633.toInt() to "Vert",       // vert sombre
            0x50882222.toInt() to "Rouge"       // rouge sombre
        )
        var currentBlobColor = p.getInt(KEY_BLOB_COLOR, DEFAULT_BLOB_COLOR)

        val swatchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }

        val swatches = mutableListOf<TextView>()
        for ((color, name) in blobColors) {
            val swatch = TextView(this).apply {
                text = "  "  // petit carre
                textSize = 14f
                setTextColor(Color.TRANSPARENT)
                setBackgroundColor(color)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                // Bordure de selection
                val isSelected = color == currentBlobColor
                if (isSelected) {
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    // bordure epaisse = padding reduit + fond + stroke simule
                    this.setTextColor(Color.BLACK)
                    this.text = "✓"
                    this.gravity = Gravity.CENTER
                }
                setOnClickListener {
                    // Deselectionner tous
                    for (s in swatches) {
                        s.setPadding(dp(12), dp(12), dp(12), dp(12))
                        s.setTextColor(Color.TRANSPARENT)
                        s.text = "  "
                    }
                    // Selectionner celui-ci
                    this.setPadding(dp(10), dp(10), dp(10), dp(10))
                    this.setTextColor(Color.BLACK)
                    this.text = "✓"
                    this.gravity = Gravity.CENTER
                    // Persister
                    prefs(this@CalibrationActivity).edit().putInt(KEY_BLOB_COLOR, color).apply()
                    currentBlobColor = color
                    testView?.invalidate()  // redessiner le blob
                }
            }
            swatches.add(swatch)
            swatchRow.addView(swatch)
        }
        root.addView(swatchRow)

        // ── Zone d'essai (CaptureView réelle avec TouchHelper) ──────────
        val cv = CaptureView(this).apply {
            isBlocnoteMode = true
            onWordGroupCompleted = null  // pas de reco, juste affichage
        }
        testView = cv
        // Mettre a jour le bouton mode quand le mode change
        cv.onModeChanged = { mode ->
            modeIndicatorBtn.text = when {
                cv.currentMode == CaptureMode.EDIT_TEMPORAL -> "⏳"
                mode == CaptureMode.CAPTURE -> "🚢"
                mode == CaptureMode.EDIT -> "🔦"
                else -> "🚢"
            }
        }
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
        temporalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                temporalLabel.text = "Distance temporelle groupe : ${v + 100} ms"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putLong(KEY_TEMPORAL_DISTANCE, (v + 100).toLong()).apply()
                    updateTestView()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        hoverSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                hoverLabel.text = "Survol long (réactivation) : ${v + 500} ms"
                if (fromUser) {
                    prefs(this@CalibrationActivity).edit().putLong(KEY_LONG_HOVER_DELAY, (v + 500).toLong()).apply()
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
            .putFloat(KEY_SPATIAL_DISTANCE_Y, (spatialYSeek.progress + 5).toFloat())
            .putLong(KEY_TEMPORAL_DISTANCE, (temporalSeek.progress + 100).toLong())
            .putLong(KEY_LONG_HOVER_DELAY, (hoverSeek.progress + 50).toLong())
            .apply()
        Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
