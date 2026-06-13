package com.parnasse.miroir

import android.content.Context
import android.util.Log
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.*

/**
 * Reconnaissance mot-à-mot via ML Kit Digital Ink.
 *
 * Pipeline :
 *   1. StrokeRecord → Ink (ML Kit format natif)
 *   2. DigitalInkRecognizer.recognize(ink) → texte
 *
 * Le modèle est téléchargé automatiquement par ML Kit.
 */
class DigitalInkWrapper(private val context: Context) : WordRecognizer {

    companion object {
        private const val TAG = "Miroir/DigiInk"
        private const val LANGUAGE_TAG = "fr"
    }

    override var isLoaded: Boolean = false
        private set

    private var recognizer: DigitalInkRecognizer? = null

    fun load() {
        try {
            val modelId = DigitalInkRecognitionModelIdentifier.fromLanguageTag(LANGUAGE_TAG)
                ?: run { Log.e(TAG, "Langue non supportée: $LANGUAGE_TAG"); return }

            val model = DigitalInkRecognitionModel.builder(modelId).build()

            // Télécharger le modèle explicitement AVANT de créer le recognizer
            val remoteModelManager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder()
                .requireWifi()  // ou .requireCharging() si on veut economiser
                .build()

            remoteModelManager.download(model, conditions)
                .addOnSuccessListener {
                    Log.i(TAG, "✅ Modèle Digital Ink téléchargé")
                    createRecognizer(model)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Échec téléchargement: ${e.message}", e)
                    // Essayer quand même — peut-être déjà en cache
                    createRecognizer(model)
                }

        } catch (e: Exception) {
            Log.e(TAG, "Échec initialisation: ${e.message}", e)
        }
    }

    private fun createRecognizer(model: DigitalInkRecognitionModel) {
        try {
            recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            isLoaded = true
            Log.i(TAG, "✅ Digital Ink Recognizer prêt")
        } catch (e: Exception) {
            Log.e(TAG, "Échec création recognizer: ${e.message}", e)
        }
    }

    override fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String {
        if (!isLoaded || recognizer == null) return ""
        if (strokes.isEmpty() || group.isEmpty()) return ""

        return try {
            val ink = buildInk(strokes, group)
            if (ink.strokes.isEmpty()) return ""

            val task = recognizer!!.recognize(ink)
            val result = com.google.android.gms.tasks.Tasks.await(task)
            val text = result.candidates.firstOrNull()?.text ?: ""
            Log.i(TAG, "RECO → '$text' (${result.candidates.size} candidats)")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reco: ${e.message}", e)
            ""
        }
    }

    private fun buildInk(strokes: List<StrokeRecord>, group: List<Int>): Ink {
        val inkBuilder = Ink.builder()
        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.points.size < 2) continue

            val strokeBuilder = Ink.Stroke.builder()
            val t0 = s.timestamps.firstOrNull() ?: System.currentTimeMillis()
            for (i in 0 until s.points.size) {
                val (x, y) = s.points[i]
                val t = s.timestamps.getOrElse(i) { t0 + i * 16L }
                strokeBuilder.addPoint(Ink.Point.create(x, y, t))
            }
            inkBuilder.addStroke(strokeBuilder.build())
        }
        return inkBuilder.build()
    }

    override fun close() {
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
        isLoaded = false
    }
}
