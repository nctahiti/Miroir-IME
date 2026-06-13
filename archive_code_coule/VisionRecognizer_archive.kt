package com.parnasse.miroir

import android.content.Context
import android.graphics.*
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.*

/**
 * Reconnaissance mot-à-mot par vision (OCR image).
 *
 * Rendu vectoriel lisse :
 *   1. Décimation → ~40 points par stroke
 *   2. Spline Catmull-Rom → courbe continue traversant les points
 *   3. Supersampling ×2 → lissage final
 *
 * Paramètres ajustables dans le companion object.
 */
class VisionRecognizer(private val context: Context) : WordRecognizer {

    companion object {
        private const val TAG = "Miroir/Vision"

        var strokeWidthRatio = 0.14f   // trait bien visible (14% hauteur)
        var targetHeight = 180f
        var padding = 60f
        var supersampling = 2
        var maxDim = 2048
        var pointsPerStroke = 20      // assez espacés pour éviter chevauchement
        var splineDensity = 5
    }

    override var isLoaded: Boolean = false
        private set
    private var textRecognizer: com.google.mlkit.vision.text.TextRecognizer? = null

    fun load(): Boolean {
        return try {
            textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            isLoaded = true
            Log.i(TAG, "✅ prêt | ratio=${"%.0f".format(strokeWidthRatio*100)}% h=${targetHeight.toInt()} spline=${splineDensity}x")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Échec chargement: ${e.message}", e); false
        }
    }

    override fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String {
        if (!isLoaded || textRecognizer == null) return ""
        if (strokes.isEmpty() || group.isEmpty()) return ""

        return try {
            val bitmap = renderStrokesToBitmap(strokes, group) ?: return ""
            
            // DEBUG: sauvegarder le bitmap pour inspection visuelle
            saveDebugBitmap(bitmap)
            
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val task = textRecognizer!!.process(inputImage)
            val visionText = com.google.android.gms.tasks.Tasks.await(task)
            val result = visionText.text.lines().firstOrNull { it.isNotBlank() } ?: ""
            Log.i(TAG, "RECO | ratio=${"%.0f".format(strokeWidthRatio*100)}% | bmp=${bitmap.width}x${bitmap.height} | → '$result' | blocks=${visionText.textBlocks.size}")
            bitmap.recycle(); result
        } catch (e: Exception) {
            Log.e(TAG, "Erreur reco: ${e.message}", e); ""
        }
    }

    private var debugCount = 0
    private fun saveDebugBitmap(bitmap: Bitmap) {
        try {
            val dir = java.io.File(context.filesDir, "debug_ocr")
            dir.mkdirs()
            val file = java.io.File(dir, "reco_${debugCount++}_${bitmap.width}x${bitmap.height}.png")
            java.io.FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Debug bitmap: ${file.absolutePath}")
        } catch (_: Exception) {}
    }

    // ═══════════════════════════════════════════════════════════════
    // RENDU VECTORIEL LISSE
    // ═══════════════════════════════════════════════════════════════

    private fun renderStrokesToBitmap(strokes: List<StrokeRecord>, group: List<Int>): Bitmap? {
        if (strokes.isEmpty() || group.isEmpty()) return null

        // 1. Extraire les points du groupe
        val allStrokes = mutableListOf<List<Pt>>()
        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.isDeleted || s.points.size < 2) continue
            // Décimer
            val step = (s.points.size / pointsPerStroke).coerceIn(1, 10)
            val pts = mutableListOf<Pt>()
            for (i in s.points.indices step step) {
                pts.add(Pt(s.points[i].first, s.points[i].second))
            }
            if (pts.size > 1 && (s.points.size - 1) % step != 0) {
                pts.add(Pt(s.points.last().first, s.points.last().second))
            }
            allStrokes.add(pts)
        }
        if (allStrokes.isEmpty()) return null

        // 2. Appliquer Catmull-Rom → points lissés
        val smoothStrokes = allStrokes.map { pts -> catmullRomSmooth(pts) }

        // 3. Boîte englobante
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        for (pts in smoothStrokes) {
            for (p in pts) {
                if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
            }
        }
        if (minX == Float.MAX_VALUE) return null

        val wordWidth = maxX - minX
        val wordHeight = maxY - minY
        if (wordHeight < 1f) return null

        // 4. Rendu supersamplé
        val ss = supersampling.toFloat()
        val scale = targetHeight * ss / wordHeight
        val strokeW = wordHeight * strokeWidthRatio * ss

        val w = (wordWidth * scale + 2 * padding * ss).toInt().coerceIn(16, maxDim * supersampling)
        val h = (wordHeight * scale + 2 * padding * ss).toInt().coerceIn(16, maxDim * supersampling)

        val bigBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bigBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-minX + padding * ss / scale, -minY + padding * ss / scale)

        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            this.strokeWidth = strokeW
            strokeCap = Paint.Cap.ROUND   // OK car points lissés = segments longs
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
        }

        for (pts in smoothStrokes) {
            if (pts.size < 2) continue
            val path = Path()
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].x, pts[i].y)
            }
            canvas.drawPath(path, paint)
        }

        val result = Bitmap.createScaledBitmap(bigBitmap, w / supersampling, h / supersampling, true)
        bigBitmap.recycle()
        return result
    }

    // ═══════════════════════════════════════════════════════════════
    // SPLINE CATMULL-ROM
    // ═══════════════════════════════════════════════════════════════

    data class Pt(val x: Float, val y: Float)

    /**
     * Lisse une séquence de points par spline Catmull-Rom.
     * Pour chaque segment [Pi, Pi+1], génère [splineDensity] points interpolés.
     */
    private fun catmullRomSmooth(pts: List<Pt>): List<Pt> {
        val n = pts.size
        if (n < 3) return pts  // pas assez de points pour interpoler

        val result = mutableListOf<Pt>()
        result.add(pts[0])  // premier point

        for (i in 0 until n - 1) {
            val p0 = pts[(i - 1).coerceAtLeast(0)]
            val p1 = pts[i]
            val p2 = pts[(i + 1).coerceAtMost(n - 1)]
            val p3 = pts[(i + 2).coerceAtMost(n - 1)]

            for (j in 1..splineDensity) {
                val t = j.toFloat() / (splineDensity + 1)
                val x = catmullRom1D(t, p0.x, p1.x, p2.x, p3.x)
                val y = catmullRom1D(t, p0.y, p1.y, p2.y, p3.y)
                result.add(Pt(x, y))
            }
            result.add(p2)  // point d'arrivée du segment
        }

        return result
    }

    /** Catmull-Rom 1D */
    private fun catmullRom1D(t: Float, p0: Float, p1: Float, p2: Float, p3: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return ((-p0 + 3 * p1 - 3 * p2 + p3) * t3 / 6f
              + (p0 - 2f * p1 + p2) * t2 / 2f
              + (-2f * p0 - 3f * p1 + 6f * p2 - p3) * t / 6f
              + p1)
    }

    override fun close() {
        textRecognizer?.close()
        textRecognizer = null
        isLoaded = false
    }
}
