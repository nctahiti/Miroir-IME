package com.parnasse.miroir

import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * VStarEncoder — Encodeur V★ v1.0 (batch).
 *
 * Encode un strokeRegistry complet en flux de tokens V★.
 * Travaille en PIXELS × 8 — les coordonnées sont multipliées par 8
 * avant arrondi pour préserver la précision sub-pixel (1/8 px).
 *
 * Format (13 octets/token) :
 *   PEN_DOWN : dx=x_abs, dy=y_abs, dt=0, p, ps=PENDOWN
 *   PEN_MOVE : dx=delta, dy=delta, dt=delta, p, ps=PENDOWN
 *   PEN_UP   : dx=delta, dy=delta, dt=delta, p, ps=PENUP
 *   GROUP    : token GROUP_SEP + label_len + label_utf8 + anchorX + anchorY
 *   END      : token END
 */
class VStarEncoder {

    companion object {
        private const val TAG = "Miroir/VStarEncoder"
        private const val HEADER_MARKER = "\n---\n"
    }

    /**
     * Encode strokes + groupes dans un fichier .vstar.
     *
     * @param destFile fichier destination
     * @param strokes liste de StrokeRecord
     * @param groups liste de listes d'indices (dans strokes) par groupe
     * @param labels map firstIdx → texte du label
     * @param anchors map firstIdx → Pair(x,y) de l'ancre
     * @param dpi résolution DPI (pour le header informatif)
     */
    fun encode(
        destFile: File,
        strokes: List<StrokeRecord>,
        groups: List<List<Int>>,
        labels: Map<Int, String>,
        anchors: Map<Int, Pair<Float, Float>>,
        dpi: Float
    ) {
        try {
            destFile.parentFile?.mkdirs()
            val out = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(destFile), 65536))

            // Header JSON
            val header = """{"format":"miroir-vstar","version":"1.0","dpi":$dpi,"strokes":${strokes.size},"groups":${groups.size}}"""
            out.write((header + HEADER_MARKER).toByteArray(Charsets.UTF_8))

            var strokeIdx = 0
            for ((groupIdx, group) in groups.withIndex()) {
                // Ancre du groupe (pour les strokes après le premier)
                val firstIdx = group.firstOrNull() ?: continue
                val anchor = anchors[firstIdx] ?: run {
                    // Fallback : utiliser le premier point du premier stroke comme ancre
                    val s = strokes.getOrNull(firstIdx)
                    if (s != null && s.points.isNotEmpty()) s.points[0] else Pair(0f, 0f)
                }
                val label = labels[firstIdx] ?: ""

                for (i in group.indices) {
                    val idx = group[i]
                    if (idx < 0 || idx >= strokes.size) continue
                    val s = strokes.getOrNull(idx) ?: continue
                    if (s.points.isEmpty() || s.isDeleted) continue

                    for (j in s.points.indices) {
                        val (px, py) = s.points[j]
                        val dx: Short
                        val dy: Short
                        val dt: Short
                        val ps: Int

                        if (j == 0) {
                            // Premier point : TOUJOURS absolu (×8)
                            // L'ancre est utilisée uniquement par le Decoder pour le placement du groupe
                            dx = (px * 8).roundToInt().coerceIn(-32768, 32767).toShort()
                            dy = (py * 8).roundToInt().coerceIn(-32768, 32767).toShort()
                            dt = 0
                            ps = VStarToken.PS_PENDOWN
                        } else {
                            val (ppx, ppy) = s.points[j - 1]
                            dx = ((px - ppx) * 8).roundToInt().coerceIn(-32768, 32767).toShort()
                            dy = ((py - ppy) * 8).roundToInt().coerceIn(-32768, 32767).toShort()
                            dt = ((s.timestamps[j] - s.timestamps[j - 1]).toInt()).coerceIn(-32768, 32767).toShort()
                            ps = if (j == s.points.size - 1) VStarToken.PS_PENUP else VStarToken.PS_PENDOWN
                        }

                        val p = (s.pressures.getOrElse(j) { 1.0f } * 255).toInt().coerceIn(0, 255)
                        out.writeShort(dx.toInt())
                        out.writeShort(dy.toInt())
                        out.writeShort(dt.toInt())
                        out.writeByte(p)
                        out.writeByte(0xFF) // az
                        out.writeByte(0xFF) // i
                        out.writeByte(ps)
                        out.writeByte(0)    // h
                        out.writeByte(strokeIdx.coerceIn(0, 255))
                        out.writeByte(j.coerceIn(0, 255))
                    }
                    strokeIdx++
                }

                // Token GROUP_SEP (marqueur seul, 13 octets, PS=4)
                VStarToken.groupSepToken().toBytes(out)
                // Token ANCRE (13 octets, PS=5) : dx=anchorX, dy=anchorY (×8)
                out.writeShort((anchor.first * 8).roundToInt().coerceIn(-32768, 32767))  // dx
                out.writeShort((anchor.second * 8).roundToInt().coerceIn(-32768, 32767)) // dy
                out.writeShort(0)     // dt = 0
                out.writeByte(0)      // p = 0
                out.writeByte(0xFF)   // az
                out.writeByte(0xFF)   // i
                out.writeByte(VStarToken.PS_GROUP_ANCRE) // ps = 5
                out.writeByte(0)      // h
                out.writeByte(strokeIdx.coerceIn(0, 255)) // sr
                out.writeByte(0)      // pr
            }

            // END
            VStarToken.endToken().toBytes(out)
            out.flush()
            out.close()
            // Log de trace : premier point de chaque stroke
            for ((gi, group) in groups.withIndex()) {
                for (si in group.indices) {
                    val idx = group[si]
                    if (idx < strokes.size && strokes[idx].points.isNotEmpty()) {
                        val (px, py) = strokes[idx].points[0]
                        val (lx, ly) = strokes[idx].points.last()
                        Log.i(TAG, "  ENC_group=$gi stroke=$si: first=(${px.toInt()},${py.toInt()}) last=(${lx.toInt()},${ly.toInt()}) pts=${strokes[idx].points.size}")
                    }
                }
            }
            Log.i(TAG, "Encodé: ${strokes.size} strokes, ${groups.size} groupes → ${destFile.length()} B")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur encodage: ${e.message}")
        }
    }
}
