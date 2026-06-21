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
 * CalibrationActivity — réglages du Miroir IME.
 *
 * Curseurs :
 *   - Distance spatiale X (↔) : blob horizontal
 *   - Distance spatiale Y (↕) : blob vertical
 *   - Délai inférence (ms)
 *   - Survol long (ms)
 *
 * Playground : zone d'essai avec TouchHelper, modes, fusion.
 */
class CalibrationActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "miroir_calibration"
        private const val KEY_SPATIAL_DISTANCE_X = "spatial_distance_x_px"
        private const val KEY_SPATIAL_DISTANCE_Y = "spatial_distance_y_px"
        private const val KEY_AUTO_INFER_DELAY = "auto_infer_delay_ms"
        private const val KEY_LONG_HOVER_DELAY = "long_hover_delay_ms"

        const val DEFAULT_SPATIAL_DISTANCE_X = 40f
        const val DEFAULT_SPATIAL_DISTANCE_Y = 70f
        const val DEFAULT_AUTO_INFER_DELAY = 1500L
        const val DEFAULT_LONG_HOVER_DELAY = 1000L

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getSpatialDistanceX(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)

        fun getSpatialDistanceY(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)

        fun getAutoInferDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)

        fun getLongHoverDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_LONG_HOVER_DELAY, DEFAULT_LONG_HOVER_DELAY)

        // ── Stubs pour compatibilité CaptureView ──
        fun getTemporalDistance(ctx: Context): Long = 800L
        fun getBlobColor(ctx: Context): Int = 0xFFC0C0C0.toInt()
    }

    private lateinit var spatialXSeek: SeekBar
    private lateinit var spatialYSeek: SeekBar
    private lateinit var delaySeek: SeekBar
    private lateinit var hoverSeek: SeekBar
    private var testView: CaptureView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs(this)
        val currentX = p.getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)
        val currentY = p.getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)
        val currentDelay = p.getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)
        val currentHover = p.getLong(KEY_LONG_HOVER_DELAY, DEFAULT_LONG_HOVER_DELAY)

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

        // Distance spatiale X
        val xLabel = addSlider(root, "Distance spatiale X (↔)", 5, 500, currentX.toInt(), "px")
        spatialXSeek = root.getChildAt(root.childCount - 1) as SeekBar
        spatialXSeek.setOnSeekBarChangeListener(simpleListener { v ->
            xLabel.text = "Distance spatiale X (↔) : ${v + 5} px"
            prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_X, (v + 5).toFloat()).apply()
            testView?.invalidate()
        })

        // Distance spatiale Y
        val yLabel = addSlider(root, "Distance spatiale Y (↕)", 5, 500, currentY.toInt(), "px")
        spatialYSeek = root.getChildAt(root.childCount - 1) as SeekBar
        spatialYSeek.setOnSeekBarChangeListener(simpleListener { v ->
            yLabel.text = "Distance spatiale Y (↕) : ${v + 5} px"
            prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_Y, (v + 5).toFloat()).apply()
            testView?.invalidate()
        })

        // Délai inférence
        val delayLabel = addSlider(root, "Délai inférence", 500, 5000, currentDelay.toInt(), "ms")
        delaySeek = root.getChildAt(root.childCount - 1) as SeekBar
        delaySeek.setOnSeekBarChangeListener(simpleListener { v ->
            delayLabel.text = "Délai inférence : ${v + 500} ms"
            prefs(this).edit().putLong(KEY_AUTO_INFER_DELAY, (v + 500).toLong()).apply()
            testView?.reloadAutoInferDelay()
        })

        // Survol long
        val hoverLabel = addSlider(root, "Survol long (réactivation)", 500, 3000, currentHover.toInt(), "ms")
        hoverSeek = root.getChildAt(root.childCount - 1) as SeekBar
        hoverSeek.setOnSeekBarChangeListener(simpleListener { v ->
            hoverLabel.text = "Survol long (réactivation) : ${v + 500} ms"
            prefs(this).edit().putLong(KEY_LONG_HOVER_DELAY, (v + 500).toLong()).apply()
        })

        // ── Boutons ────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(4))
        }
        btnRow.addView(Button(this).apply {
            text = "Réinitialiser"
            setOnClickListener {
                spatialXSeek.progress = DEFAULT_SPATIAL_DISTANCE_X.toInt() - 5
                spatialYSeek.progress = DEFAULT_SPATIAL_DISTANCE_Y.toInt() - 5
                delaySeek.progress = DEFAULT_AUTO_INFER_DELAY.toInt() - 500
                hoverSeek.progress = (DEFAULT_LONG_HOVER_DELAY.toInt() - 500).coerceAtLeast(0)
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

        // ── Barre playground (fusion + témoin mode) ──────────────────
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
        // Témoin de mode (🚢 CAPTURE / 🔦 EDIT / ⏳ TEMPOREL)
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

        // ── Zone d'essai (CaptureView réelle avec TouchHelper) ───────
        val cv = CaptureView(this).apply {
            isBlocnoteMode = true
            onWordGroupCompleted = null
        }
        testView = cv
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
        }
        parent.addView(seek)
        return label
    }

    private fun simpleListener(onProgress: (Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                if (fromUser) onProgress(v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    private fun save() {
        prefs(this).edit()
            .putFloat(KEY_SPATIAL_DISTANCE_X, (spatialXSeek.progress + 5).toFloat())
            .putFloat(KEY_SPATIAL_DISTANCE_Y, (spatialYSeek.progress + 5).toFloat())
            .putLong(KEY_AUTO_INFER_DELAY, (delaySeek.progress + 500).toLong())
            .putLong(KEY_LONG_HOVER_DELAY, (hoverSeek.progress + 500).toLong())
            .apply()
        Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
