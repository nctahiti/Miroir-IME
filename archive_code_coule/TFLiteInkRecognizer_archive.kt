package com.parnasse.miroir

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Reconnaissance mot-à-mot via le modèle Google scribe_latin (biLSTM 51 Ko)
 * chargé directement avec TensorFlow Lite — sans ML Kit, sans Play Services.
 *
 * Pipeline :
 *   1. StrokeRecord → points normalisés [x_norm, y_norm, time_delta]
 *   2. Tenseur [1, T, 3] → Interpreter.run()
 *   3. Logits CTC → greedy decode → texte
 *
 * Usage :
 *   val rec = TFLiteInkRecognizer(context)
 *   rec.load()
 *   val text = rec.recognize(strokes, group)
 *   rec.close()
 */
class TFLiteInkRecognizer(private val context: Context) : WordRecognizer {

    companion object {
        private const val TAG = "Miroir/TFLite"
        private const val MODEL_ASSET = "scribe_latin.tflite"

        // Le modèle scribe_latin produit des logits CTC sur un alphabet de ~53 classes.
        // Sans le FST (lexique) Google, on décode en caractères bruts.
        private const val BLANK_IDX = 0  // standard CTC
    }

    override var isLoaded: Boolean = false
        private set

    private var interpreter: Interpreter? = null

    /** Charge le modèle TFLite depuis les assets */
    fun load(): Boolean {
        return try {
            val buf = loadModelFile(MODEL_ASSET)
            interpreter = Interpreter(buf)
            isLoaded = true
            Log.i(TAG, "✅ Modèle TFLite chargé (${buf.capacity() / 1024} Ko)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Échec chargement: ${e.message}", e)
            isLoaded = false
            false
        }
    }

    override fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String {
        if (!isLoaded || interpreter == null) {
            Log.w(TAG, "recognize: modèle non chargé")
            return ""
        }
        if (strokes.isEmpty() || group.isEmpty()) return ""

        return try {
            // 1. Extraire les points du groupe
            val points = extractPoints(strokes, group)
            if (points.size < 3) return ""

            // 2. Normaliser et construire le tenseur d'entrée [1, T, 3]
            val flatInput = buildFlatTensor(points)
            val T = flatInput.size / 3

            // Structurer en [1][T][3] pour TFLite
            val input = Array(1) { Array(T) { FloatArray(3) } }
            for (t in 0 until T) {
                val off = t * 3
                input[0][t][0] = flatInput[off]
                input[0][t][1] = flatInput[off + 1]
                input[0][t][2] = flatInput[off + 2]
            }

            // 3. Exécuter l'inférence — découvrir la forme de sortie dynamiquement
            val inputArray = arrayOf<Any>(input)
            
            // Obtenir la forme de sortie depuis l'interpréteur
            val outputTensor = interpreter?.getOutputTensor(0)
            val numClasses = outputTensor?.shape()?.getOrNull(2) ?: 80
            
            // TFLite attend des tableaux Java primitifs
            val output = Array(1) { Array(T) { FloatArray(numClasses) } }
            interpreter?.runForMultipleInputsOutputs(inputArray, mapOf(0 to output))

            // 4. Décoder les logits CTC → texte
            val logits = output[0]
            val text = greedyDecode(logits, T, numClasses)
            Log.d(TAG, "recognize: '$text'")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reco: ${e.message}", e)
            ""
        }
    }

    // ── Extraction des points ────────────────────────────────────────

    private data class Point(val x: Float, val y: Float, val t: Long)

    private fun extractPoints(strokes: List<StrokeRecord>, group: List<Int>): List<Point> {
        val pts = mutableListOf<Point>()
        val t0 = strokes.firstOrNull()?.timestamps?.firstOrNull() ?: 0L

        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            for (i in 0 until s.activePoints) {
                val (x, y) = s.points[i]
                val t = s.timestamps.getOrElse(i) { t0 + i * 16L }
                pts.add(Point(x, y, t))
            }
        }
        return pts
    }

    // ── Construction du tenseur [1, T, 3] ────────────────────────────

    private fun buildFlatTensor(points: List<Point>): FloatArray {
        val T = points.size.coerceAtMost(2000)
        val tensor = FloatArray(T * 3)

        // Centrage et échelle
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        val cx = (xs.minOrNull()!! + xs.maxOrNull()!!) / 2f
        val cy = (ys.minOrNull()!! + ys.maxOrNull()!!) / 2f
        val scale = maxOf(xs.maxOrNull()!! - xs.minOrNull()!!,
                          ys.maxOrNull()!! - ys.minOrNull()!!, 1f)

        val t0 = points.first().t
        var prevT = t0

        for (i in 0 until T) {
            val p = points[i]
            val off = i * 3
            // x_norm, y_norm centrés réduits
            tensor[off]     = (p.x - cx) / scale
            tensor[off + 1] = (p.y - cy) / scale
            // time_delta en ms, normalisé
            val dt = (p.t - prevT).coerceAtLeast(1L)
            tensor[off + 2] = dt.toFloat() / 100f  // échelle ~10ms
            prevT = p.t
        }

        return tensor
    }

    // ── Décodage CTC greedy ──────────────────────────────────────────

    private fun greedyDecode(logits: Array<FloatArray>, T: Int, numClasses: Int): String {
        val sb = StringBuilder()
        var prevIdx = BLANK_IDX

        for (t in 0 until T) {
            val frame = logits[t]
            // Trouver l'index avec la plus haute probabilité
            var bestIdx = BLANK_IDX
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                if (frame[c] > bestVal) {
                    bestVal = frame[c]
                    bestIdx = c
                }
            }

            // CTC: ignorer les répétitions et le blank
            if (bestIdx != BLANK_IDX && bestIdx != prevIdx) {
                // Mapping heuristique : les indices 1-26 → a-z, 27-52 → A-Z, au-delà → ?
                val ch = when {
                    bestIdx in 1..26 -> 'a' + (bestIdx - 1)
                    bestIdx in 27..52 -> 'A' + (bestIdx - 27)
                    else -> '?' // classe inconnue
                }
                sb.append(ch)
            }
            prevIdx = bestIdx
        }

        return sb.toString().lowercase()
    }

    // ── Chargement du fichier modèle ─────────────────────────────────

    private fun loadModelFile(path: String): MappedByteBuffer {
        val fd: AssetFileDescriptor = context.assets.openFd(path)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fd.startOffset
        val declaredLength = fd.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
        Log.i(TAG, "Modèle fermé")
    }
}
