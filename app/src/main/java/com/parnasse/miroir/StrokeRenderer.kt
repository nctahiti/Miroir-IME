package com.parnasse.miroir

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log

/**
 * StrokeRenderer — Rastérisation pure d'un groupe de strokes en Bitmap.
 *
 * Utilitaire sans état. Prend une liste de StrokeRecord + les indices
 * d'un groupe, et produit un Bitmap ARGB_8888 du rendu visuel.
 *
 * Usage : appelé par StrokeProcessor AVANT l'inférence ML Kit pour
 * produire un aperçu de contrôle (sauvegardé dans debug_rasters/).
 *
 * Les strokes sont rendus en noir sur fond blanc, avec une épaisseur
 * de trait de 3px (cohérent avec CaptureView.strokePaint).
 */
object StrokeRenderer {

    private const val TAG = "Miroir/StrokeRenderer"

    /** Épaisseur de trait pour la rastérisation (identique à CaptureView) */
    private const val STROKE_WIDTH = 3f

    /** Marge autour du contenu rastérisé (px) */
    private const val PADDING = 20

    /** Taille minimale du bitmap (évite les bitmaps 1×1) */
    private const val MIN_SIZE = 64

    /**
     * Rastérise un groupe de strokes en Bitmap.
     *
     * @param strokes Registre complet de strokes
     * @param group Indices des strokes à rastériser dans ce groupe
     * @param padding Marge autour du contenu (défaut 20px)
     * @param backgroundColor Fond du bitmap (défaut BLANC pour lisibilité e-ink)
     * @return Bitmap du groupe rastérisé, ou null si le groupe est vide
     */
    fun rasterizeGroup(
        strokes: List<StrokeRecord>,
        group: List<Int>,
        padding: Int = PADDING,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        if (strokes.isEmpty() || group.isEmpty()) {
            Log.w(TAG, "Groupe vide, pas de rastérisation")
            return null
        }

        // 1. Calculer la boîte englobante du groupe
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE
        var hasPoints = false

        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.points.isEmpty()) continue

            for ((x, y) in s.points) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                hasPoints = true
            }
        }

        if (!hasPoints) {
            Log.w(TAG, "Aucun point dans le groupe, pas de rastérisation")
            return null
        }

        // 2. Dimensions du bitmap (taille exacte du contenu + padding)
        val contentW = (maxX - minX).toInt().coerceAtLeast(1)
        val contentH = (maxY - minY).toInt().coerceAtLeast(1)
        val bmpW = (contentW + padding * 2).coerceAtLeast(MIN_SIZE)
        val bmpH = (contentH + padding * 2).coerceAtLeast(MIN_SIZE)

        // 3. Créer le bitmap
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fond
        canvas.drawColor(backgroundColor)

        // 4. Décalage pour placer le contenu dans le bitmap (avec padding)
        val offsetX = padding - minX
        val offsetY = padding - minY

        // 5. Dessiner chaque stroke
        val paint = Paint().apply {
            isAntiAlias = true
            isDither = true
            color = Color.BLACK
            strokeWidth = STROKE_WIDTH
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.points.size < 2) continue

            val path = Path()
            val first = s.points[0]
            path.moveTo(first.first + offsetX, first.second + offsetY)

            for (i in 1 until s.points.size) {
                val (x, y) = s.points[i]
                path.lineTo(x + offsetX, y + offsetY)
            }

            canvas.drawPath(path, paint)
        }

        Log.d(TAG, "Rastérisé groupe de ${group.size} strokes → ${bmpW}×${bmpH} px " +
                "(contenu: ${contentW}×${contentH}, offset: ${offsetX.toInt()},${offsetY.toInt()})")

        return bitmap
    }

    /**
     * Rastérise un stroke individuel (utile pour le débogage d'un stroke problématique).
     */
    fun rasterizeStroke(stroke: StrokeRecord, padding: Int = PADDING): Bitmap? {
        if (stroke.points.size < 2) return null

        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE; var maxY = Float.MIN_VALUE

        for ((x, y) in stroke.points) {
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
        }

        val contentW = (maxX - minX).toInt().coerceAtLeast(1)
        val contentH = (maxY - minY).toInt().coerceAtLeast(1)
        val bmpW = (contentW + padding * 2).coerceAtLeast(MIN_SIZE)
        val bmpH = (contentH + padding * 2).coerceAtLeast(MIN_SIZE)

        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val offsetX = padding - minX
        val offsetY = padding - minY

        val paint = Paint().apply {
            isAntiAlias = true; isDither = true
            color = Color.BLACK; strokeWidth = STROKE_WIDTH
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        path.moveTo(stroke.points[0].first + offsetX, stroke.points[0].second + offsetY)
        for (i in 1 until stroke.points.size) {
            path.lineTo(stroke.points[i].first + offsetX, stroke.points[i].second + offsetY)
        }
        canvas.drawPath(path, paint)

        return bitmap
    }

    // ═══ NORMALISATION INTERLIGNE (V4 Horizon) ═══

    /**
     * Rastérise un groupe de strokes dans une zone NORMALISÉE par l'interligne.
     *
     * Principe : au lieu de la boîte englobante exacte, on rastérise dans une
     * zone de hauteur fixe = 3 × interligne (1.5 au-dessus, 1.5 en-dessous du snapY).
     * La largeur reste définie par le contenu du mot.
     *
     * → Tous les mots apparaissent à la même échelle verticale.
     * → Un point isolé (accent) apparaît dans son contexte spatial.
     * → ML Kit voit toujours la même référence d'échelle.
     *
     * @param strokes Registre complet de strokes
     * @param group Indices des strokes à rastrériser
     * @param lineHeight Interligne moyenne (px) — ex: 70 (défaut calibration distY)
     * @param snapY Ligne de base du mot (centre Y du groupe) dans les coordonnées du canvas
     * @param padding Marge horizontale (px)
     * @return Bitmap de hauteur 3×lineHeight, largeur = contenu + padding
     */
    fun rasterizeGroupNormalized(
        strokes: List<StrokeRecord>,
        group: List<Int>,
        lineHeight: Float,
        snapY: Float,
        padding: Int = PADDING
    ): Bitmap? {
        if (strokes.isEmpty() || group.isEmpty()) {
            Log.w(TAG, "Groupe vide, pas de rastérisation normalisée")
            return null
        }

        // 1. Bornes horizontales (définies par le contenu)
        var minX = Float.MAX_VALUE; var maxX = Float.MIN_VALUE
        var hasPoints = false

        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.points.isEmpty()) continue
            for ((x, _) in s.points) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                hasPoints = true
            }
        }

        if (!hasPoints) {
            Log.w(TAG, "Aucun point dans le groupe, pas de rastérisation normalisée")
            return null
        }

        // 2. Zone verticale NORMALISÉE : 1.5×IL au-dessus, 1.5×IL en-dessous du snapY
        val zoneTop = snapY - lineHeight * 1.5f
        val zoneBottom = snapY + lineHeight * 1.5f
        val zoneHeight = (zoneBottom - zoneTop).toInt().coerceAtLeast(MIN_SIZE)

        // 3. Zone horizontale : définie par le contenu
        val contentW = (maxX - minX).toInt().coerceAtLeast(1)
        val bmpW = (contentW + padding * 2).coerceAtLeast(MIN_SIZE)
        val bmpH = zoneHeight

        // 4. Créer le bitmap
        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        // 5. Offset : le snapY du mot tombe au milieu de la zone (à 1.5×lineHeight du haut)
        val offsetX = padding - minX
        val offsetY = -zoneTop  // place le snapY à 1.5×lineHeight du haut du bitmap

        // 6. Dessiner chaque stroke
        val paint = Paint().apply {
            isAntiAlias = true; isDither = true
            color = Color.BLACK; strokeWidth = STROKE_WIDTH
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            val s = strokes[si]
            if (s.points.size < 2) continue

            val path = Path()
            val first = s.points[0]
            path.moveTo(first.first + offsetX, first.second + offsetY)

            for (i in 1 until s.points.size) {
                val (x, y) = s.points[i]
                path.lineTo(x + offsetX, y + offsetY)
            }

            canvas.drawPath(path, paint)
        }

        Log.d(TAG, "Rastérisé NORMALISÉ groupe de ${group.size} strokes → ${bmpW}×${bmpH} px " +
                "(IL=${lineHeight.toInt()}px, snapY=${snapY.toInt()}, zone=[${zoneTop.toInt()}..${zoneBottom.toInt()}])")

        return bitmap
    }

    /**
     * Calcule le snapY (centre vertical) d'un groupe de strokes.
     * Utilisé pour positionner le mot dans la zone normalisée.
     */
    fun computeSnapY(strokes: List<StrokeRecord>, group: List<Int>): Float {
        var sumY = 0f; var count = 0
        for (si in group) {
            if (si < 0 || si >= strokes.size) continue
            for ((_, y) in strokes[si].points) {
                sumY += y; count++
            }
        }
        return if (count > 0) sumY / count else 0f
    }
}
