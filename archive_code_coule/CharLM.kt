package com.parnasse.miroir

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Character-level n-gram language model loaded from char_lm.json.
 *
 * JSON format:
 * {
 *   "chars": "abcdef...",
 *   "order": 5,
 *   "smoothing": 0.01,
 *   "unigram": [0.1, 0.05, ...],
 *   "2": {"ab": {"a": 0.5, "b": 0.3, ...}, ...},
 *   "3": { ... },
 *   ...
 * }
 *
 * models map: order -> context -> char -> probability
 */
class CharLM(context: Context) {

    companion object {
        private const val LM_FILE = "char_lm.json"
    }

    private val chars: String
    private val order: Int
    private val smoothing: Float
    private val unigram: FloatArray
    private val models: MutableMap<Int, MutableMap<String, MutableMap<Int, Float>>> = LinkedHashMap()

    init {
        val json = loadJson(context)
        chars = json.getString("chars")
        order = json.getInt("order")
        smoothing = json.getDouble("smoothing").toFloat()

        val unigramArr = json.getJSONArray("unigram")
        unigram = FloatArray(unigramArr.length()) { unigramArr.getDouble(it).toFloat() }

        for (n in 2..order) {
            val key = n.toString()
            if (json.has(key)) {
                val orderObj = json.getJSONObject(key)
                val orderMap = LinkedHashMap<String, MutableMap<Int, Float>>()

                val iter = orderObj.keys()
                while (iter.hasNext()) {
                    val ctx = iter.next()
                    val probsObj = orderObj.getJSONObject(ctx)
                    val probsMap = LinkedHashMap<Int, Float>()

                    val probIter = probsObj.keys()
                    while (probIter.hasNext()) {
                        val chStr = probIter.next()
                        probsMap[Integer.parseInt(chStr)] = probsObj.getDouble(chStr).toFloat()
                    }
                    orderMap[ctx] = probsMap
                }
                models[n] = orderMap
            }
        }
    }

    private fun loadJson(context: Context): JSONObject {
        val text = context.assets.open(LM_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return JSONObject(text)
    }

    /**
     * Score a character given context.
     * Returns log probability for next char after context.
     */
    fun score(context: String, next: Char): Float {
        val charIndex = chars.indexOf(next)
        if (charIndex < 0) return Float.NEGATIVE_INFINITY

        // Try from highest order down to unigram
        for (n in minOf(order, context.length) downTo 2) {
            val ctx = context.takeLast(n - 1)
            val orderMap = models[n] ?: continue
            val probs = orderMap[ctx] ?: continue
            val prob = probs[charIndex]
            if (prob != null) {
                // Linear interpolation with lower orders via recursion
                val result = prob * (1 - smoothing) + Math.exp(score(context, next).toDouble()).toFloat() * smoothing
                return Math.log(result.toDouble()).toFloat()
            }
        }

        // Unigram fallback
        if (charIndex < unigram.size) {
            val prob = unigram[charIndex]
            return Math.log(prob.toDouble()).toFloat()
        }

        return Float.NEGATIVE_INFINITY
    }

    /**
     * Apply language model score to a beam search hypothesis.
     * Used by OnnxEngine.beamSearchDecode.
     */
    fun applyLm(text: String, order: Int, beamScore: Float, lmWeight: Float): Float {
        var score = beamScore
        for (i in maxOf(1, text.length - this.order + 1) until text.length) {
            val context = text.substring(maxOf(0, i - this.order + 1), i)
            val next = text[i]
            val lmScore = score(context, next)
            score += lmScore * lmWeight
        }
        return score
    }
}
