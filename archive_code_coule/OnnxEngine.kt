package com.parnasse.miroir

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OnnxEngine — Inférence ONNX pour la reconnaissance cursive (mode Bloc-notes).
 *
 * Pipeline complet coté tablette :
 *   1. StrokeRecord (points absolus) → deltas normalisés
 *   2. Quantification pixel-space → centrage-étendue → tenseur [1, T, 7]
 *   3. ONNX Runtime → logits CTC (27 classes)
 *   4. Greedy decode → texte transcrit
 *
 * Usage :
 *   val engine = OnnxEngine(context)
 *   if (engine.load()) {
 *       val text = engine.recognize(strokes, groupIndices)
 *   }
 *   engine.close()
 */
class OnnxEngine(private val context: Context) {

    companion object {
        private const val TAG = "Miroir/Onnx"
        private const val MODEL_FILENAME = "ctc_words_encoder.onnx"

        // Alphabet CTC : 52 lettres (a-z A-Z) + blank (index 52)
        private val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val BLANK_IDX = 52

        private const val MAX_TOKENS = 2000
        private const val MIN_GAP_TOKENS = 3
        private const val KEEP_EDGES = 3

        /** Nombre de tokens de gap à insérer entre deux strokes pour que le
         *  PenUpCompressor détecte le gap inter-stroke et insère un token BOUNDARY.
         *  MIN_GAP_TOKENS = 3 → on met 4 pour être safe. */
        private const val INTER_STROKE_GAP_TOKENS = 4

        /** log(a + b) pour a, b en espace log (ou -inf). */
        fun logAdd(a: Float, b: Float): Float {
            if (a == Float.NEGATIVE_INFINITY) return b
            if (b == Float.NEGATIVE_INFINITY) return a
            val maxVal = max(a, b)
            val minVal = min(a, b)
            return maxVal + (Math.log(1.0 + Math.exp((minVal - maxVal).toDouble()))).toFloat()
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    var modelLoaded: Boolean = false
        private set

    // LM optionnel pour shallow fusion
    var charLM: CharLM? = null
    var lmWeight: Float = 0.1f
        private set

    /** Configurer le LM pour shallow fusion. weight=0 désactive. */
    fun setLmWeight(weight: Float) {
        lmWeight = max(0f, min(weight, 1f))
    }

    /** Charge le modèle depuis assets/. Appeler au démarrage. */
    fun load(): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = context.assets.open(MODEL_FILENAME).use { it.readBytes() }
            val opts = OrtSession.SessionOptions().apply {
                addCPU(true) // Arena: mémoire pool pour LSTM (evite deadlock)
            }
            ortSession = ortEnv?.createSession(modelBytes, opts)
            modelLoaded = true
            Log.i(TAG, "Modèle chargé: ${modelBytes.size / 1024} Ko")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Modèle ONNX non trouvé dans assets/: ${e.message}")
            modelLoaded = false
            false
        }
    }

    // ── Point d'entrée principal ────────────────────────────────────────
    // recognize(strokes, group) → String

    fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String {
        if (!modelLoaded || strokes.isEmpty() || group.isEmpty()) {
            Log.w(TAG, "recognize: skipped (loaded=$modelLoaded strokes=${strokes.size} group=${group.size})")
            return ""
        }

        return try {
            val points = extractPointsForGroup(strokes, group)
            if (points.isEmpty()) {
                Log.w(TAG, "recognize: points vides")
                return ""
            }

            val tensor = buildTensor(points) ?: return ""
            Log.d(TAG, "recognize: tensor ${tensor.size / 6} tokens")

            val logits = runInference(tensor)
            if (logits == null) {
                Log.w(TAG, "recognize: runInference null")
                return ""
            }
            Log.d(TAG, "recognize: logits ${logits.size} valeurs")

            val text = decode(logits)
            Log.d(TAG, "recognize: decode='$text' (${text.length} chars)")
            text
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reconnaissance: ${e.message}")
            Log.e(TAG, "StackTrace: ${e.stackTraceToString()}")
            ""
        }
    }

    // ── 1a. CATMULL-ROM SMOOTHING ──────────────────────────────────────
    // Copie du tokenizer.py : segmentation aux points de courbure,
    // puis lissage par Catmull-Rom spline sur chaque segment.
    // Conserve le nombre exact de points.

    private fun angleBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dot = ax * bx + ay * by
        val norm = Math.sqrt((ax * ax + ay * ay).toDouble()) * Math.sqrt((bx * bx + by * by).toDouble())
        if (norm < 1e-8) return 0.0f
        val cos = (dot / norm).coerceIn(-1.0, 1.0)
        return Math.toDegrees(Math.acos(cos.toDouble())).toFloat()
    }

    /** Catmull-Rom 1D: calcule la valeur au parametre t (0..1) sur le segment pts. */
    private fun catmullRom1D(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return ((-p0 + 3 * p1 - 3 * p2 + p3) * t3 / 6f
                + (p0 - 2f * p1 + p2) * t2 / 2f
                + (-2f * p0 - 3f * p1 + 6f * p2 - p3) * t / 6f
                + p1)
    }

    /** Lisse un stroke (xs, ys), retourne deux FloatArray de meme longueur. */
    private fun smoothStroke(xs: FloatArray, ys: FloatArray, angleTh: Float = 40f, curveTh: Float = 135f, minSeg: Int = 5): Pair<FloatArray, FloatArray> {
        val n = xs.size
        if (n < 3) return Pair(xs.copyOf(), ys.copyOf())

        // Segmentation aux points de courbure
        val segEnds = mutableListOf(0)
        for (i in 1 until n - 1) {
            val v1x = xs[i] - xs[i - 1]
            val v1y = ys[i] - ys[i - 1]
            val v2x = xs[i + 1] - xs[i]
            val v2y = ys[i + 1] - ys[i]
            val angle = angleBetween(v1x, v1y, v2x, v2y)
            if (angle < angleTh || (180f - angle) > curveTh || (i - segEnds.last()) >= minSeg) {
                segEnds.add(i)
            }
        }
        if (segEnds.last() != n - 1) segEnds.add(n - 1)

        val outX = mutableListOf<Float>()
        val outY = mutableListOf<Float>()

        for (si in 0 until segEnds.size - 1) {
            var a = segEnds[si]
            val b = segEnds[si + 1]
            if (si > 0) a += 1  // eviter duplication du point de jointure

            if (b - a < 2) {
                for (j in a..b) { outX.add(xs[j]); outY.add(ys[j]) }
                continue
            }

            val segN = b - a + 1
            // Catmull-Rom 1D sur chaque point du segment
            for (j in 0 until segN) {
                val t = j.toFloat() / (segN - 1).coerceAtLeast(1)
                val p0 = if (a + j - 1 in xs.indices) xs[a + j - 1] else xs[a + j]
                val p1 = xs[a + j]
                val p2 = if (a + j + 1 in xs.indices) xs[a + j + 1] else xs[a + j]
                val p3 = if (a + j + 2 in xs.indices) xs[a + j + 2] else xs[a + j]
                outX.add(catmullRom1D(t, p0, p1, p2, p3))

                val q0 = if (a + j - 1 in ys.indices) ys[a + j - 1] else ys[a + j]
                val q1 = ys[a + j]
                val q2 = if (a + j + 1 in ys.indices) ys[a + j + 1] else ys[a + j]
                val q3 = if (a + j + 2 in ys.indices) ys[a + j + 2] else ys[a + j]
                outY.add(catmullRom1D(t, q0, q1, q2, q3))
            }
        }

        // Forcer au meme nombre de points que l'original
        if (outX.size != n) {
            val tResample = FloatArray(n) { it.toFloat() / (n - 1).coerceAtLeast(1) }
            val tCur = FloatArray(outX.size) { it.toFloat() / (outX.size - 1).coerceAtLeast(1) }
            val tCurList = tCur.toList()
            val resampledX = FloatArray(n) { j -> interpolate(tResample[j], tCurList, outX) }
            val resampledY = FloatArray(n) { j -> interpolate(tResample[j], tCurList, outY) }
            return Pair(resampledX, resampledY)
        }

        return Pair(outX.toFloatArray(), outY.toFloatArray())
    }

    /** Interpolation lineaire 1D: valeur de ys au point t. */
    private fun interpolate(t: Float, ts: List<Float>, ys: List<Float>): Float {
        if (t <= ts.first()) return ys.first()
        if (t >= ts.last()) return ys.last()
        val idx = ts.indexOfFirst { it >= t }
        if (idx <= 0) return ys.first()
        val t0 = ts[idx - 1]; val t1 = ts[idx]
        val y0 = ys[idx - 1]; val y1 = ys[idx]
        val frac = (t - t0) / (t1 - t0).coerceAtLeast(1e-8f)
        return y0 + (y1 - y0) * frac
    }

    /** Lisse tous les strokes d'un groupe: applique smoothStroke a chaque stroke. */
    private fun smoothStrokeGroup(strokes: List<StrokeRecord>, group: List<Int>): List<List<Pair<Float, Float>>> {
        val smoothed = mutableListOf<List<Pair<Float, Float>>>()
        for (strokeIdx in group) {
            if (strokeIdx < 0 || strokeIdx >= strokes.size) continue
            val s = strokes[strokeIdx]
            val n = s.activePoints
            if (n < 2) continue
            val xs = FloatArray(n) { i -> s.points[i].first }
            val ys = FloatArray(n) { i -> s.points[i].second }
            val (sx, sy) = smoothStroke(xs, ys)
            val pts = (0 until sx.size).map { Pair(sx[it], sy[it]) }
            smoothed.add(pts)
        }
        return smoothed
    }

    // ── 1b. Extraction : StrokeRecord → points bruts (apres lissage) ──

    private data class RawPoint(
        val x: Float, val y: Float,
        val pressure: Float,
        val penState: Int
    )

    private fun extractPointsForGroup(
        strokes: List<StrokeRecord>,
        group: List<Int>
    ): List<RawPoint> {
        // 1. Lisser les strokes du groupe (Catmull-Rom) avant extraction
        val smoothedStrokes = smoothStrokeGroup(strokes, group)

        val points = mutableListOf<RawPoint>()
        var lastX = 0f
        var lastY = 0f
        var firstStroke = true

        for (si in smoothedStrokes.indices) {
            val strokePts = smoothedStrokes[si]
            if (strokePts.isEmpty()) continue

            // Gap inter-stroke (identique a l'ancien comportement)
            if (!firstStroke) {
                for (g in 0 until INTER_STROKE_GAP_TOKENS) {
                    points.add(RawPoint(lastX, lastY, 0f, VStarToken.PS_PENUP))
                }
            }
            firstStroke = false

            for (pi in strokePts.indices) {
                val (x, y) = strokePts[pi]
                // Pression constante 0.5 : le dataset d'entrainement a 500.0 hardcode
                val p = 0.5f
                val ps = if (pi == 0) VStarToken.PS_PENDOWN else VStarToken.PS_PENUP
                points.add(RawPoint(x, y, p, ps))
                lastX = x; lastY = y
            }
        }
        return points
    }

    // ── 2. Tenseur [1, T, 7] ───────────────────────────────────────────

    /**
     * Format des 6 dimensions (le modèle ONNX calcule les features spatiales en interne) :
     *   0: x_norm   = (x - cx) / scale
     *   1: y_norm   = (y - cy) / scale
     *   2: qdx_norm = round(dx / 0.1) * 0.1 / scale
     *   3: qdy_norm = round(dy / 0.1) * 0.1 / scale
     *   4: p_norm   = p / 1000.0  (toujours 0.5)
     *   5: pen_up   = flag PenUpCompressor (EXIT=0.2, BOUNDARY=1.0, ENTRY=0.3)
     */
    private fun buildTensor(points: List<RawPoint>): FloatArray? {
        if (points.isEmpty()) return null

        val T = points.size
        if (T > MAX_TOKENS) {
            Log.w(TAG, "Troncature: $T → $MAX_TOKENS tokens")
        }
        val maxLen = min(T, MAX_TOKENS)

        val DE = 6  // dimensions en sortie
        val flatSize = maxLen * DE
        val buffer = FloatArray(flatSize)

        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f
        val scale = max(maxX - minX, maxY - minY).coerceAtLeast(1f)

        val QSTEP = 0.1f
        var prevX = 0f; var prevY = 0f
        for (j in 0 until maxLen) {
            val off = j * DE
            val p = points[j]
            val dx = if (j == 0) 0f else p.x - prevX
            val dy = if (j == 0) 0f else p.y - prevY
            prevX = p.x; prevY = p.y

            buffer[off]     = (p.x - centerX) / scale
            buffer[off + 1] = (p.y - centerY) / scale
            buffer[off + 2] = (dx / QSTEP).roundToInt() * QSTEP / scale
            buffer[off + 3] = (dy / QSTEP).roundToInt() * QSTEP / scale
            buffer[off + 4] = 0.5f  // pression constante
            buffer[off + 5] = 0f    // pen_up initial (compressPenUp le modifiera)
        }

        return compressPenUp(buffer)
    }

    /**
     * Compression des gaps pen-up (identique à PenUpCompressor Python).
     *
     * Détecte les séquences de tokens pen-up (dx=0, dy=0) de longueur >= MIN_GAP_TOKENS.
     * Les remplace par:
     *   - keep_edges tokens EXIT (flag=0.2) avant le gap
     *   - 1 token BOUNDARY (flag=1.0, dx/dy = déplacement inter-stroke)
     *   - keep_edges tokens ENTRY (flag=0.3) après le gap
     *
     * Les gaps < MIN_GAP_TOKENS sont laissés tels quels (flag pen-up).
     *
     * 6 dimensions : [x, y, dx, dy, pressure, pen_up_flag]
     */
    private fun compressPenUp(data: FloatArray): FloatArray {
        val D = 6  // dimension des tokens
        val T = data.size / D
        if (T < 6) return data  // pas assez de tokens pour compresser

        val stride = D

        // Détection pen-up : dx=0 et dy=0
        val penUp = BooleanArray(T)
        for (j in 0 until T) {
            val off = j * stride
            penUp[j] = data[off + 2] == 0f && data[off + 3] == 0f
        }

        val compressed = mutableListOf<Float>()
        var i = 0
        while (i < T) {
            if (penUp[i]) {
                val gapStart = i
                while (i < T && penUp[i]) i++
                val gapLen = i - gapStart

                if (gapLen >= MIN_GAP_TOKENS) {
                    // --- EXIT: keep_edges tokens avant le gap ---
                    val exitStart = (gapStart - KEEP_EDGES).coerceAtLeast(0)
                    for (e in exitStart until gapStart) {
                        val off = e * stride
                        for (d in 0 until stride) {
                            compressed.add(if (d == 5) 0.2f else data[off + d])
                        }
                    }

                    // --- BOUNDARY: 1 token de déplacement inter-stroke ---
                    if (gapStart > 0 && i < T) {
                        val offEnd = (gapStart - 1) * stride
                        val offNext = i * stride
                        val xEnd = data[offEnd]
                        val yEnd = data[offEnd + 1]
                        val xNext = data[offNext]
                        val yNext = data[offNext + 1]
                        compressed.add(xNext)
                        compressed.add(yNext)
                        compressed.add(xNext - xEnd)  // dx inter-stroke
                        compressed.add(yNext - yEnd)  // dy inter-stroke
                        compressed.add(0f)           // pression nulle
                        compressed.add(1.0f)          // flag frontière
                    }

                    // --- ENTRY: keep_edges tokens après le gap ---
                    val entryEnd = (i + KEEP_EDGES).coerceAtMost(T)
                    for (e in i until entryEnd) {
                        val off = e * stride
                        for (d in 0 until stride) {
                            compressed.add(if (d == 5) 0.3f else data[off + d])
                        }
                    }
                } else {
                    // Petit gap: garder tel quel
                    for (e in gapStart until i) {
                        val off = e * stride
                        for (d in 0 until stride) {
                            compressed.add(data[off + d])
                        }
                    }
                }
            } else {
                // Token normal
                val off = i * stride
                for (d in 0 until stride) {
                    compressed.add(data[off + d])
                }
                i++
            }
        }

        val Tcompressed = compressed.size / stride
        if (Tcompressed != T) {
            Log.d(TAG, "Compression pen-up: $T -> $Tcompressed tokens")
        }
        return compressed.toFloatArray()
    }

    // ── 3. Inférence ONNX ──────────────────────────────────────────────

    private fun runInference(data: FloatArray): FloatArray? {
        return try {
            val env = ortEnv ?: return null
            val session = ortSession ?: return null

            val T = data.size / 6
            val shape = longArrayOf(1, T.toLong(), 6)
            val lengths = longArrayOf(T.toLong())

            val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
            val lengthsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(lengths), longArrayOf(1))
            val results = session.run(mapOf(
                "input" to input,
                "lengths" to lengthsTensor
            ))

            val raw = results.get("logits")
            if (raw == null) {
                Log.e(TAG, "runInference: 'logits' introuvable")
                return null
            }

            // ONNX Runtime 1.20 wrapper Optional -> OnnxValue
            val optional = raw as java.util.Optional<*>
            if (!optional.isPresent) {
                Log.e(TAG, "runInference: optional logits vide")
                return null
            }
            val output = optional.get() as OnnxTensor
            val logits = FloatArray(output.floatBuffer.remaining())
            output.floatBuffer.get(logits)

            results.close()
            input.close()
            lengthsTensor.close()
            logits
        } catch (e: Exception) {
            Log.e(TAG, "Erreur inférence: ${e.message}")
            Log.e(TAG, "StackTrace: ${e.stackTraceToString()}")
            null
        }
    }

    // ── 4. Décodage CTC: greedy (fallback) et beam search ──────────────

    private val BEAM_WIDTH = 20

    // Cache log softmax par frame pour éviter de recalculer
    private fun logSoftmax(logitsFlat: FloatArray, numClasses: Int): FloatArray {
        val T = logitsFlat.size / numClasses
        val logProbs = FloatArray(logitsFlat.size)
        for (t in 0 until T) {
            val off = t * numClasses
            var maxVal = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                if (logitsFlat[off + c] > maxVal) maxVal = logitsFlat[off + c]
            }
            var sumExp = 0.0
            for (c in 0 until numClasses) {
                logProbs[off + c] = logitsFlat[off + c] - maxVal
                sumExp += Math.exp(logProbs[off + c].toDouble())
            }
            val logSum = Math.log(sumExp)
            for (c in 0 until numClasses) {
                logProbs[off + c] = (logProbs[off + c] - logSum).toFloat()
            }
        }
        return logProbs
    }

    /**
     * Une hypothèse dans le beam search CTC.
     *
     * CTC prefix probability: chaque hypothèse maintient deux probabilités
     * (en espace log) pour éviter le double-counting:
     *   - scoreBlank:    P(prefix | seq[0..t]) où le dernier token est BLANK
     *   - scoreNonBlank: P(prefix | seq[0..t]) où le dernier token est non-BLANK
     *
     * Quand on étend avec un caractère k:
     *   - Si k == lastToken: seul le chemin via BLANK peut étendre (évite X->XX)
     *   - Sinon: les deux chemins peuvent étendre
     *
     * Référence: Graves et al. "Connectionist Temporal Classification" (2006)
     */
    private data class BeamHypothesis(
        val prefix: List<Int>,   // indices des tokens CTC
        val lastToken: Int,       // dernier token non-blank (-1 si vide)
        var scoreBlank: Float,   // log-prob que prefix finisse par BLANK
        var scoreNonBlank: Float // log-prob que prefix finisse par non-BLANK
    ) {
        fun totalScore(): Float {
            val a = scoreBlank
            val b = scoreNonBlank
            if (a == Float.NEGATIVE_INFINITY) return b
            if (b == Float.NEGATIVE_INFINITY) return a
            val maxVal = max(a, b)
            val minVal = min(a, b)
            val logSum = Math.log(1.0 + Math.exp((minVal - maxVal).toDouble()))
            return maxVal + logSum.toFloat()
        }
    }

    /** Lieu de l'hypothèse: préfixe identique + lastToken. */
    private data class BeamKey(val prefix: List<Int>, val lastToken: Int)

    /**
     * Décodage CTC beam search.
     *
     * Garde les BEAM_WIDTH meilleures hypothèses à chaque frame.
     * Après toutes les frames, choisit l'hypothèse avec le score total max.
     *
     * @param logitsFlat logits bruts du modèle (shape: [T * numClasses])
     * @return texte décodé
     */
    private fun beamSearchDecode(logitsFlat: FloatArray, beamWidth: Int = BEAM_WIDTH): String {
        val numClasses = 53
        val T = logitsFlat.size / numClasses

        // 1. Log softmax
        val logProbs = logSoftmax(logitsFlat, numClasses)

        // 2. Initialisation: préfixe vide
        var beams = mutableMapOf<BeamKey, BeamHypothesis>()
        val emptyKey = BeamKey(emptyList(), -1)
        beams[emptyKey] = BeamHypothesis(emptyList(), -1, 0f, Float.NEGATIVE_INFINITY)

        // 3. Beam search temporel
        for (t in 0 until T) {
            val off = t * numClasses
            val newBeams = mutableMapOf<BeamKey, BeamHypothesis>()

            for ((key, hyp) in beams) {
                val prefix = key.prefix
                val lastTok = key.lastToken

                // ── Extension avec BLANK ──
                val blankProb = logProbs[off + BLANK_IDX]
                // P_b(new) += P(prefix) * p(blank | x)
                val totalPrev = hyp.totalScore()
                val newBlankScore = if (totalPrev > Float.NEGATIVE_INFINITY)
                    totalPrev + blankProb else Float.NEGATIVE_INFINITY

                val blankKey = BeamKey(prefix, lastTok)  // même préfixe, même lastToken
                val existing = newBeams[blankKey]
                if (existing != null) {
                    existing.scoreBlank = logAdd(existing.scoreBlank, newBlankScore)
                } else {
                    newBeams[blankKey] = BeamHypothesis(
                        prefix, lastTok, newBlankScore, Float.NEGATIVE_INFINITY
                    )
                }

                // ── Extension avec chaque caractère non-blank ──
                for (c in 0 until numClasses) {
                    if (c == BLANK_IDX) continue

                    val charProb = logProbs[off + c]
                    var sourceScore = Float.NEGATIVE_INFINITY
                    var newPrefix: List<Int>
                    var newLastTok: Int

                    if (c == lastTok) {
                        // Caractère répété: ne peut venir QUE du chemin BLANK
                        // (sinon on collerait X X → XX)
                        // Mais on peut aussi étendre le préfixe avec c
                        // en venant du chemin BLANK (c'est la vraie extension)

                        // Chemin 1: même préfixe, extension via BLANK seulement
                        // → déjà géré par la section BLANK ci-dessus

                        // Chemin 2: préfixe + [c], extension depuis P_b(prefix)
                        if (hyp.scoreBlank > Float.NEGATIVE_INFINITY) {
                            sourceScore = hyp.scoreBlank + charProb
                            newPrefix = prefix + c
                            newLastTok = c

                            val extKey = BeamKey(newPrefix, newLastTok)
                            val extExisting = newBeams[extKey]
                            if (extExisting != null) {
                                extExisting.scoreNonBlank = logAdd(extExisting.scoreNonBlank, sourceScore)
                            } else {
                                newBeams[extKey] = BeamHypothesis(
                                    newPrefix, newLastTok,
                                    Float.NEGATIVE_INFINITY, sourceScore
                                )
                            }
                        }
                    } else {
                        // Nouveau caractère: peut venir des DEUX chemins
                        val maxSource = max(hyp.scoreBlank, hyp.scoreNonBlank)
                        if (maxSource > Float.NEGATIVE_INFINITY) {
                            sourceScore = maxSource + charProb
                            newPrefix = prefix + c
                            newLastTok = c

                            val extKey = BeamKey(newPrefix, newLastTok)
                            val extExisting = newBeams[extKey]
                            if (extExisting != null) {
                                extExisting.scoreNonBlank = logAdd(extExisting.scoreNonBlank, sourceScore)
                            } else {
                                newBeams[extKey] = BeamHypothesis(
                                    newPrefix, newLastTok,
                                    Float.NEGATIVE_INFINITY, sourceScore
                                )
                            }
                        }
                    }
                }
            }

            // 4. Pruning: garder les top beamWidth
            beams = newBeams.entries
                .sortedByDescending { it.value.totalScore() }
                .take(beamWidth)
                .associateTo(mutableMapOf()) { it.key to it.value }
        }

        // 5. Meilleure hypothèse finale
        val best = beams.maxByOrNull { it.value.totalScore() } ?: return ""
        val bestPrefix = best.value.prefix

        return bestPrefix.joinToString("") { idx ->
            if (idx in ALPHABET.indices) ALPHABET[idx].toString() else ""
        }
    }

    /** Décodage greedy (fallback si beam search trop lent). */
    private fun greedyDecode(logitsFlat: FloatArray): String {
        val numClasses = 53
        val T_out = logitsFlat.size / numClasses

        val argmax = IntArray(T_out)
        for (t in 0 until T_out) {
            val off = t * numClasses
            var best = BLANK_IDX
            var bestVal = Float.NEGATIVE_INFINITY
            for (c in 0 until numClasses) {
                val v = logitsFlat[off + c]
                if (v > bestVal) { bestVal = v; best = c }
            }
            argmax[t] = best
        }

        val collapsed = mutableListOf<Int>()
        var prev = -1
        for (idx in argmax) {
            if (idx != BLANK_IDX && idx != prev) {
                collapsed.add(idx)
            }
            prev = idx
        }

        return collapsed.joinToString("") { idx ->
            if (idx in ALPHABET.indices) ALPHABET[idx].toString() else ""
        }
    }

    /**
     * Greedy token-par-token avec shallow fusion LM.
     * À chaque frame, le choix du caractère est influencé par le LM
     * en fonction du préfixe déjà décodé.
     *
     * Vocabulaire: ALPHABET (52 lettres) + blank (idx 52)
     * Caractères autorisés pour le LM: a-z + espace (indices 0-25 et non utilisé)
     */
    private fun greedyDecodeLm(logitsFlat: FloatArray): String {
        val numClasses = 53
        val T_out = logitsFlat.size / numClasses
        val lm = charLM ?: return greedyDecode(logitsFlat)

        val sb = StringBuilder()
        var lastEmitted = -1
        var prefix = ""

        for (t in 0 until T_out) {
            val off = t * numClasses
            var bestIdx = BLANK_IDX
            var bestScore = logitsFlat[off + BLANK_IDX]

            for (c in 0 until numClasses) {
                if (c == BLANK_IDX) continue
                val char = ALPHABET[c]
                // Ne pas scorer LM si c'est une répétition (CTC collapse)
                val lmAdd = if (c != lastEmitted && lmWeight > 0f) {
                    lm.score(prefix, char) * lmWeight
                } else 0f
                val score = logitsFlat[off + c] + lmAdd
                if (score > bestScore) {
                    bestScore = score
                    bestIdx = c
                }
            }

            if (bestIdx != BLANK_IDX) {
                if (bestIdx != lastEmitted) {
                    sb.append(ALPHABET[bestIdx])
                    prefix = sb.toString()
                    lastEmitted = bestIdx
                } // même que last → CTC collapse
            } else {
                lastEmitted = -1
            }
        }

        return sb.toString()
    }

    /** Point d'entrée décode: greedy+LM (TM), fallback greedy pur. */
    private fun decode(logitsFlat: FloatArray): String {
        val startTime = System.currentTimeMillis()
        val text = try {
            if (charLM != null && lmWeight > 0f) {
                greedyDecodeLm(logitsFlat)
            } else {
                greedyDecode(logitsFlat)
            }
        } catch (e: Exception) {
            Log.w(TAG, "decode error, fallback: ${e.message}")
            greedyDecode(logitsFlat)
        }
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(TAG, "decode(${elapsed}ms): '$text' (lmWeight=${lmWeight})")
        return text
    }

    fun close() {
        try {
            ortSession?.close()
            ortEnv = null
            ortSession = null
            modelLoaded = false
        } catch (_: Exception) {}
    }
}
