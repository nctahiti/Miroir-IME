package com.parnasse.miroir

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.digitalink.*
import com.google.mlkit.common.MlKit
import java.util.concurrent.TimeUnit

/**
 * Reconnaissance mot-à-mot via Google ML Kit Digital Ink.
 *
 * Utilise le modèle scribe_latin (biLSTM 3 couches, 51 Ko)
 * via l'API Digital Ink Recognition de ML Kit.
 *
 * Usage :
 *   val recognizer = GoogleInkRecognizer(context)
 *   recognizer.load()  // asynchrone, vérifier isLoaded
 *   val text = recognizer.recognize(strokes, group)
 *   recognizer.close()
 */
class GoogleInkRecognizer(private val context: Context) : WordRecognizer {

    companion object {
        private const val TAG = "Miroir/GoogleInk"

        /**
         * Tag de langue BCP-47 pour le français.
         * ML Kit télécharge automatiquement le modèle approprié
         * (scribe_latin pour l'écriture latine).
         */
        private const val LANGUAGE_TAG = "fr"
    }

    override var isLoaded: Boolean = false
        private set

    private var recognizer: DigitalInkRecognizer? = null

    /** Initialise le recognizer. L'appel est asynchrone — surveiller isLoaded. */
    fun load() {
        try {
            val modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
                ?: run {
                    Log.e(TAG, "Langue '$LANGUAGE_TAG' non supportée par ML Kit")
                    return
                }

            val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
            val options = DigitalInkRecognizerOptions.builder(model).build()
            recognizer = DigitalInkRecognition.getClient(options)

            isLoaded = true
            Log.i(TAG, "✅ Digital Ink Recognizer prêt (langue: $LANGUAGE_TAG)")
        } catch (e: Exception) {
            Log.e(TAG, "Échec chargement: ${e.message}", e)
            isLoaded = false
        }
    }

    /**
     * Reconnaît un groupe de strokes.
     *
     * Convertit les StrokeRecord en objet Ink (format ML Kit),
     * puis appelle le recognizer Google.
     */
    override fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String {
        if (!isLoaded || recognizer == null) {
            Log.w(TAG, "recognize: recognizer non chargé")
            return ""
        }
        if (strokes.isEmpty() || group.isEmpty()) {
            Log.w(TAG, "recognize: strokes=$strokes.size group=$group.size — vide")
            return ""
        }

        return try {
            val ink = buildInk(strokes, group)
            if (ink.strokes.isEmpty()) {
                Log.w(TAG, "recognize: Ink vide après conversion")
                return ""
            }

            // Attendre le résultat (bloquant, ~50-200ms pour un mot)
            // Tasks.await gère le téléchargement du modèle au premier appel
            val task = recognizer!!.recognize(ink)
            val result = Tasks.await(task, 10, TimeUnit.SECONDS)
            val text = result?.candidates?.firstOrNull()?.text ?: ""
            Log.d(TAG, "recognize: '$text' (${result?.candidates?.size ?: 0} candidats)")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reconnaissance: ${e.message}", e)
            ""
        }
    }

    /**
     * Convertit un groupe de StrokeRecord en objet Ink ML Kit.
     *
     * Chaque StrokeRecord devient un Ink.Stroke avec :
     *   - points (x, y, timestamp_ms)
     *   - Ink.Stroke.Builder pour construire le stroke
     */
    private fun buildInk(strokes: List<StrokeRecord>, group: List<Int>): Ink {
        val inkBuilder = Ink.builder()

        for (strokeIdx in group) {
            if (strokeIdx < 0 || strokeIdx >= strokes.size) continue
            val stroke = strokes[strokeIdx]
            val n = stroke.activePoints
            if (n < 2) continue

            val strokeBuilder = Ink.Stroke.builder()
            for (i in 0 until n) {
                val (x, y) = stroke.points[i]
                // Timestamp relatif en ms depuis le début du stroke
                // ML Kit utilise le temps pour calculer la vélocité
                val t0 = stroke.timestamps.firstOrNull() ?: System.currentTimeMillis()
                val timestampMs = t0 + (i * 16L) // ~60 Hz = 16ms entre points
                strokeBuilder.addPoint(Ink.Point.create(x, y, timestampMs))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }

        return inkBuilder.build()
    }

    override fun close() {
        try {
            recognizer?.close()
        } catch (_: Exception) {}
        recognizer = null
        isLoaded = false
        Log.i(TAG, "Recognizer fermé")
    }
}
