package com.parnasse.miroir

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.*

class CalibrationActivity : Activity() {

    companion object {
        private const val PREFS_NAME = "miroir_calibration"
        private const val KEY_SPATIAL_DISTANCE_X = "spatial_distance_x_px"
        private const val KEY_SPATIAL_DISTANCE_Y = "spatial_distance_y_px"
        private const val KEY_AUTO_INFER_DELAY = "auto_infer_delay_ms"
        private const val KEY_DISPLAY_DELAY = "display_delay_ms"
        private const val KEY_LONG_PRESS_DELAY = "long_press_delay_ms"
        private const val KEY_TEMPLATE_SPACING = "template_spacing_px"

        const val DEFAULT_SPATIAL_DISTANCE_X = 40f
        const val DEFAULT_SPATIAL_DISTANCE_Y = 70f
        const val DEFAULT_AUTO_INFER_DELAY = 350L
        const val DEFAULT_DISPLAY_DELAY = 700L
        const val DEFAULT_LONG_PRESS_DELAY = 500L
        const val DEFAULT_TEMPLATE_SPACING = 120f

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getSpatialDistanceX(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)
        fun getSpatialDistanceY(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)
        fun getAutoInferDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)
        fun getDisplayDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_DISPLAY_DELAY, DEFAULT_DISPLAY_DELAY)
        fun getLongPressDelay(ctx: Context): Long =
            prefs(ctx).getLong(KEY_LONG_PRESS_DELAY, DEFAULT_LONG_PRESS_DELAY)
        fun getTemplateSpacing(ctx: Context): Float =
            prefs(ctx).getFloat(KEY_TEMPLATE_SPACING, DEFAULT_TEMPLATE_SPACING)

        // Compatibilité (anciennes clés)
        fun getLongHoverDelay(ctx: Context): Long = getLongPressDelay(ctx)
        fun getSelectionDelay(ctx: Context): Long = DEFAULT_LONG_PRESS_DELAY
        fun getEditDelay(ctx: Context): Long = DEFAULT_LONG_PRESS_DELAY
        fun getRefreshInterval(ctx: Context): Long = 16L
        fun getBlobRayCount(ctx: Context): Int = 90
        fun getTemplateStrokeWidth(ctx: Context): Float = 2f
        fun getBlobColor(ctx: Context): Int = 0xFFC0C0C0.toInt()
        fun getTemporalDistance(ctx: Context): Long = 800L
    }

    private lateinit var spatialXSeek: SeekBar
    private lateinit var spatialYSeek: SeekBar
    private lateinit var inferSeek: SeekBar
    private lateinit var displaySeek: SeekBar
    private lateinit var longPressSeek: SeekBar
    private lateinit var templateSeek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val p = prefs(this)
        val curX = p.getFloat(KEY_SPATIAL_DISTANCE_X, DEFAULT_SPATIAL_DISTANCE_X)
        val curY = p.getFloat(KEY_SPATIAL_DISTANCE_Y, DEFAULT_SPATIAL_DISTANCE_Y)
        val curInfer = p.getLong(KEY_AUTO_INFER_DELAY, DEFAULT_AUTO_INFER_DELAY)
        val curDisplay = p.getLong(KEY_DISPLAY_DELAY, DEFAULT_DISPLAY_DELAY)
        val curLongPress = p.getLong(KEY_LONG_PRESS_DELAY, DEFAULT_LONG_PRESS_DELAY)
        val curSpacing = p.getFloat(KEY_TEMPLATE_SPACING, DEFAULT_TEMPLATE_SPACING)

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(40), dp(20), dp(20))
        }
        scroll.addView(root)

        root.addView(TextView(this).apply {
            text = "⚙ Paramètres du Miroir"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(16))
        })

        // ═══ 🎯 Blob ═══
        root.addView(sectionHeader("🎯 Blob d'absorption"))
        addSlider(root, "Distance X (↔)", 5, 300, curX.toInt(), "px") { seek ->
            spatialXSeek = seek; prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_X, (seek.progress + 5).toFloat()).apply()
        }
        addSlider(root, "Distance Y (↕)", 5, 300, curY.toInt(), "px") { seek ->
            spatialYSeek = seek; prefs(this).edit().putFloat(KEY_SPATIAL_DISTANCE_Y, (seek.progress + 5).toFloat()).apply()
        }

        // ═══ ⏱️ Timers ═══
        root.addView(sectionHeader("⏱️ Timers"))
        addSlider(root, "Délai inférence", 100, 3000, curInfer.toInt(), "ms") { seek ->
            inferSeek = seek; prefs(this).edit().putLong(KEY_AUTO_INFER_DELAY, (seek.progress + 100).toLong()).apply()
        }
        addSlider(root, "Délai affichage", 200, 3000, curDisplay.toInt(), "ms") { seek ->
            displaySeek = seek; prefs(this).edit().putLong(KEY_DISPLAY_DELAY, (seek.progress + 200).toLong()).apply()
        }
        addSlider(root, "Appui long", 200, 2000, curLongPress.toInt(), "ms") { seek ->
            longPressSeek = seek; prefs(this).edit().putLong(KEY_LONG_PRESS_DELAY, (seek.progress + 200).toLong()).apply()
        }

        // ═══ 📏 Template ═══
        root.addView(sectionHeader("📏 Template"))
        addSlider(root, "Interligne", 40, 300, curSpacing.toInt(), "px") { seek ->
            templateSeek = seek; prefs(this).edit().putFloat(KEY_TEMPLATE_SPACING, (seek.progress + 40).toFloat()).apply()
        }

        // ── Boutons ────────────────────────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(12), 0, dp(4))
        }
        btnRow.addView(Button(this).apply {
            text = "Défauts"
            setOnClickListener {
                spatialXSeek.progress = DEFAULT_SPATIAL_DISTANCE_X.toInt() - 5
                spatialYSeek.progress = DEFAULT_SPATIAL_DISTANCE_Y.toInt() - 5
                inferSeek.progress = DEFAULT_AUTO_INFER_DELAY.toInt() - 100
                displaySeek.progress = DEFAULT_DISPLAY_DELAY.toInt() - 200
                longPressSeek.progress = DEFAULT_LONG_PRESS_DELAY.toInt() - 200
                templateSeek.progress = DEFAULT_TEMPLATE_SPACING.toInt() - 40
                save()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "✓ OK"
            setOnClickListener { save(); finish() }
        })
        root.addView(btnRow)

        setContentView(scroll)
    }

    private fun addSlider(parent: LinearLayout, name: String, min: Int, max: Int, current: Int, unit: String,
                          onCreated: (SeekBar) -> Unit): TextView {
        val label = TextView(this).apply {
            text = "$name : $current $unit"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(8), 0, dp(2)); setTextColor(Color.DKGRAY)
        }
        parent.addView(label)
        val seek = SeekBar(this).apply { this.max = max - min; this.progress = (current - min).coerceIn(0, max - min); setPadding(0, 0, 0, dp(12)) }
        parent.addView(seek)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sk: SeekBar, v: Int, fromUser: Boolean) {
                label.text = "$name : ${v + min} $unit"
                if (fromUser) onCreated(seek)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        onCreated(seek)
        return label
    }

    private fun sectionHeader(title: String): TextView = TextView(this).apply {
        text = title; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.argb(220, 40, 40, 40))
        setPadding(0, dp(16), 0, dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun save() {
        Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
