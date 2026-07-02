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
 * Travaille en PIXELS NATIFS — aucune conversion d'unités.
 * L'aller-retour encodeur → décodeur est parfait.
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
                val anchor = anchors[firstIdx] ?: continue
                val label = labels[firstIdx] ?: ""

                for (i in group.indices) {
                    val idx = group[i]
                    if (idx >= strokes.size) continue
                    val s = strokes[idx]
                    if (s.points.isEmpty()) continue

                    for (j in s.points.indices) {
                        val (px, py) = s.points[j]
                        val dx: Short
                        val dy: Short
                        val dt: Short
                        val ps: Int

                        if (j == 0) {
                            // Premier point : absolu (ou delta depuis l'ancre si pas le premier stroke)
                            if (i == 0) {
                                dx = px.roundToInt().coerceIn(-32768, 32767).toShort()
                                dy = py.roundToInt().coerceIn(-32768, 32767).toShort()
                            } else {
                                dx = ((px - anchor.first).roundToInt()).coerceIn(-32768, 32767).toShort()
                                dy = ((py - anchor.second).roundToInt()).coerceIn(-32768, 32767).toShort()
                            }
                            dt = 0
                            ps = VStarToken.PS_PENDOWN
                        } else {
                            val (ppx, ppy) = s.points[j - 1]
                            dx = ((px - ppx).roundToInt()).coerceIn(-32768, 32767).toShort()
                            dy = ((py - ppy).roundToInt()).coerceIn(-32768, 32767).toShort()
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

                // Token GROUP : label + ancre, paddé à 13 octets
                val labelBytes = label.toByteArray(Charsets.UTF_8)
                val labelLen = labelBytes.size.coerceIn(0, 8) // max 8 chars
                // Token GROUP_SEP (13 octets)
                VStarToken.groupSepToken().toBytes(out)
                // Données étendues paddées à 13 octets : label_len(1) + label(0..8) + anchorX(2) + anchorY(2) + padding
                out.writeByte(labelLen)
                if (labelLen > 0) out.write(labelBytes, 0, labelLen)
                out.writeShort(anchor.first.roundToInt().coerceIn(-32768, 32767))
                out.writeShort(anchor.second.roundToInt().coerceIn(-32768, 32767))
                // Padding à 13 octets
                val pad = 13 - 5 - labelLen
                for (p in 0 until pad) out.writeByte(0)
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
