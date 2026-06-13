package dev.parnasse.inkservice

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.util.TypedValue

/**
 * CalibrationActivity — mode de réglage du blob d'absorption.
 *
 * Structure visuelle :
 *   ┌──────────────────────────────────────┐
 *   │  Curseurs de réglage (ScrollView)    │
 *   │  ┌─ Distance spatiale  ──●── 12px   │
 *   │  ┌─ Chevauchement      ──●── 30%    │
 *   │  ┌─ Distance temporelle──●── 800ms  │
 *   │  ┌─ Timeout transcript.──●── 2000ms │
 *   ├──────────────────────────────────────┤
 *   │  Transcription live                  │
 *   │  "Hello World"                       │
 *   ├──────────────────────────────────────┤
 *   │  Zone d'essai (capture stylet)       │
 *   │  ┌──────────────────────────────┐    │
 *   │  │ ← écrire ici pour tester →  │    │
 *   │  └──────────────────────────────┘    │
 *   └──────────────────────────────────────┘
 *
 * Lancement : depuis la vue principale Miroir (bouton paramètres).
 */
class CalibrationActivity : Activity() {

    // ── Composants ────────────────────────────────────────────────────

    private lateinit var groupManager: GroupManager
    private lateinit var miroirView: MiroirView
    private lateinit var captureEngine: InkCaptureEngine
    private lateinit var transcriptionText: TextView
    private lateinit var params: BlobParams

    // Curseurs
    private lateinit var spatialSeek: SeekBar
    private lateinit var overlapSeek: SeekBar
    private lateinit var temporalSeek: SeekBar
    private lateinit var timeoutSeek: SeekBar

    // Labels des valeurs
    private lateinit var spatialLabel: TextView
    private lateinit var overlapLabel: TextView
    private lateinit var temporalLabel: TextView
    private lateinit var timeoutLabel: TextView

    // =================================================================
    // Lifecycle
    // =================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Charger les paramètres persistés
        params = BlobParams.load(BlobParams.prefs(this))

        // Créer le GroupManager avec les params chargés
        groupManager = GroupManager(
            onGroupTranscribed = { group ->
                runOnUiThread {
                    transcriptionText.text = group.transcription ?: ""
                }
            }
        ).also { it.params = params }

        // Construire l'UI
        val root = buildUI()
        setContentView(root)

        // Initialiser la capture stylet
        setupCapture()
    }

    override fun onDestroy() {
        groupManager.release()
        super.onDestroy()
    }

    // =================================================================
    // Construction UI
    // =================================================================

    private fun buildUI(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ── Titre ──
        root.addView(TextView(this).apply {
            text = "🔧 Calibration du Blob"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            setPadding(dp(16), dp(12), dp(16), dp(4))
        })

        // ── Curseurs (dans un ScrollView) ──
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.5f
            )
        }
        val cursorsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(8))
        }

        // Distance spatiale
        spatialLabel = addSlider(
            cursorsPanel,
            "Distance spatiale",
            BlobParams.MIN_SPATIAL_PX.toInt(),
            BlobParams.MAX_SPATIAL_PX.toInt(),
            params.spatialDistancePx.toInt(),
            "px"
        ) { spatialSeek = it }

        // Chevauchement
        overlapLabel = addSlider(
            cursorsPanel,
            "Chevauchement min",
            BlobParams.MIN_OVERLAP,
            BlobParams.MAX_OVERLAP,
            params.minOverlapPercent,
            "%"
        ) { overlapSeek = it }

        // Distance temporelle
        temporalLabel = addSlider(
            cursorsPanel,
            "Distance temporelle",
            BlobParams.MIN_TEMPORAL_MS.toInt(),
            BlobParams.MAX_TEMPORAL_MS.toInt(),
            params.temporalDistanceMs.toInt(),
            "ms"
        ) { temporalSeek = it }

        // Timeout transcription
        timeoutLabel = addSlider(
            cursorsPanel,
            "Timeout transcription",
            BlobParams.MIN_TIMEOUT_MS.toInt(),
            BlobParams.MAX_TIMEOUT_MS.toInt(),
            params.transcriptionTimeoutMs.toInt(),
            "ms"
        ) { timeoutSeek = it }

        scroll.addView(cursorsPanel)
        root.addView(scroll)

        // ── Niveau de groupement ──
        val levelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
        }
        levelRow.addView(TextView(this).apply {
            text = "Niveau :  "
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        })
        for (level in GroupLevel.values()) {
            levelRow.addView(Button(this).apply {
                text = level.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                isSelected = level == params.groupLevel
                setOnClickListener {
                    params = params.copy(groupLevel = level)
                    groupManager.params = params
                    // Désélectionner tous les boutons du niveau
                    for (i in 0 until levelRow.childCount) {
                        (levelRow.getChildAt(i) as? Button)?.isSelected = false
                    }
                    isSelected = true
                    BlobParams.save(BlobParams.prefs(this@CalibrationActivity), params)
                }
            })
        }
        root.addView(levelRow)

        // ── Transcription live ──
        root.addView(TextView(this).apply {
            text = "Transcription :"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(16), dp(8), dp(16), dp(2))
            setTextColor(Color.GRAY)
        })
        transcriptionText = TextView(this).apply {
            text = "Écrivez ci-dessous..."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(2), dp(16), dp(4))
            setTextColor(Color.BLACK)
            minLines = 2
        }
        root.addView(transcriptionText)

        // ── Zone d'essai ──
        miroirView = MiroirView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2f
            )
            setBackgroundColor(Color.argb(255, 248, 240, 224)) // crème
            groupManagerRef = groupManager
        }
        root.addView(miroirView)

        // ── Boutons ──
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(6), dp(16), dp(12))
            gravity = Gravity.END
        }
        btnRow.addView(Button(this).apply {
            text = "Réinitialiser"
            setOnClickListener {
                params = BlobParams()
                groupManager.params = params
                BlobParams.save(BlobParams.prefs(this@CalibrationActivity), params)
                updateSlidersFromParams()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "✓  OK"
            setOnClickListener { finish() }
        })
        root.addView(btnRow)

        return root
    }

    // =================================================================
    // Slider helper
    // =================================================================

    private fun addSlider(
        parent: LinearLayout,
        name: String,
        min: Int, max: Int, current: Int, unit: String,
        onSeekCreated: (SeekBar) -> Unit
    ): TextView {
        val label = TextView(this).apply {
            text = "$name : $current $unit"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(1))
            setTextColor(Color.DKGRAY)
        }
        parent.addView(label)

        val seek = SeekBar(this).apply {
            this.max = max - min
            this.progress = current - min
            setPadding(0, 0, 0, dp(6))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                    val realValue = value + min
                    label.text = "$name : $realValue $unit"
                    if (fromUser) onSliderChanged()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        parent.addView(seek)
        onSeekCreated(seek)
        return label
    }

    private fun onSliderChanged() {
        val newParams = BlobParams(
            spatialDistancePx      = (spatialSeek.progress + BlobParams.MIN_SPATIAL_PX.toInt()).toFloat(),
            minOverlapPercent      = overlapSeek.progress + BlobParams.MIN_OVERLAP,
            temporalDistanceMs     = (temporalSeek.progress + BlobParams.MIN_TEMPORAL_MS.toInt()).toLong(),
            transcriptionTimeoutMs = (timeoutSeek.progress + BlobParams.MIN_TIMEOUT_MS.toInt()).toLong(),
            groupLevel             = params.groupLevel,
            captureAnchor          = params.captureAnchor
        )
        params = newParams
        groupManager.params = newParams
        BlobParams.save(BlobParams.prefs(this), newParams)
    }

    private fun updateSlidersFromParams() {
        spatialSeek.progress  = params.spatialDistancePx.toInt() - BlobParams.MIN_SPATIAL_PX.toInt()
        overlapSeek.progress  = params.minOverlapPercent - BlobParams.MIN_OVERLAP
        temporalSeek.progress = params.temporalDistanceMs.toInt() - BlobParams.MIN_TEMPORAL_MS.toInt()
        timeoutSeek.progress  = params.transcriptionTimeoutMs.toInt() - BlobParams.MIN_TIMEOUT_MS.toInt()
        // Les labels sont mis à jour automatiquement via les OnSeekBarChangeListener
    }

    // =================================================================
    // Capture stylet dans la zone d'essai
    // =================================================================

    private fun setupCapture() {
        val sessionId = System.currentTimeMillis()

        captureEngine = InkCaptureEngine(
            sessionId = sessionId,
            onStrokeSealed = { stroke ->
                if (!stroke.wasCanceled) {
                    groupManager.onStrokeSealed(stroke)
                    // Forcer le redraw du blob
                    miroirView.invalidate()
                }
            },
            onPointAppended = { stroke, point ->
                miroirView.onStrokePoint(stroke, point)
            }
        )

        miroirView.setOnTouchListener { _, event ->
            captureEngine.onMotionEvent(event)
            true
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
