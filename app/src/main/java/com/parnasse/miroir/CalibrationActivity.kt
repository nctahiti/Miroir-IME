package com.parnasse.miroir

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.*

/**
 * CalibrationActivity — réglages essentiels du Miroir IME.
 *
 * Curseurs :
 *   - Distance spatiale X (↔) : blob horizontal
 *   - Distance spatiale Y (↕) : blob vertical
 *
 * Persisté dans SharedPreferences, lu par MiroirIME et CaptureView.
 */
class CalibrationActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "miroir_calibration"
        private const val KEY_SPATIAL_DISTANCE_X = "spatial_distance_x_px"
        private const val KEY_SPATIAL_DISTANCE_Y = "spatial_distance_y_px"

        const val DEFAULT_SPATIAL_DISTANCE_X = 40f
        const val DEFAULT_SPATIAL_DISTANCE_Y = 70f

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getSpatialDistanceX(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)

        fun getSpatialDistanceY(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)

        // ── Stubs pour compatibilité CaptureView (valeur par défaut) ──
        fun getAutoInferDelay(ctx: Context): Long = 1500L
        fun getTemporalDistance(ctx: Context): Long = 800L
        fun getLongHoverDelay(ctx: Context): Long = 1000L
        fun getBlobColor(ctx: Context): Int = 0xFFC0C0C0.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs(this)
        val currentX = p.getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)
        val currentY = p.getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }

        // Titre
        root.addView(TextView(this).apply {
            text = "⚙ Blob du Miroir"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })

        // Distance spatiale X
        val (xLabel, xSeek) = addSlider(root, "Horizontal (↔)", 5, 500, currentX.toInt(), "px")
        xSeek.setOnSeekBarChangeListener(simpleListener { v ->
            xLabel.text = "Horizontal (↔) : ${v + 5} px"
            prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_X, (v + 5).toFloat()).apply()
        })

        // Distance spatiale Y
        val (yLabel, ySeek) = addSlider(root, "Vertical (↕)", 5, 500, currentY.toInt(), "px")
        ySeek.setOnSeekBarChangeListener(simpleListener { v ->
            yLabel.text = "Vertical (↕) : ${v + 5} px"
            prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_Y, (v + 5).toFloat()).apply()
        })

        setContentView(root)
    }

    /** Crée un slider avec son label. Retourne (label, seekBar). */
    private fun addSlider(
        parent: LinearLayout, name: String,
        min: Int, max: Int, current: Int, unit: String
    ): Pair<TextView, SeekBar> {
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
            setPadding(0, 0, 0, dp(16))
        }
        parent.addView(seek)
        return Pair(label, seek)
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
