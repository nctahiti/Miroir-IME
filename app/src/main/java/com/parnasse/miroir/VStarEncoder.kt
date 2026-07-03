package com.parnasse.miroir

import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * VStarEncoder — Encodeur V★ v1.1 (batch).
 *
 * Encode un strokeRegistry complet en flux de tokens V★.
 * Travaille en PIXELS × 8 — les coordonnées sont multipliées par 8
 * avant arrondi pour préserver la précision sub-pixel (1/8 px).
 *
 * Format (14 octets/token) :
 *   PEN_DOWN : dx=x_abs, dy=y_abs, dt=0, p, ps=PENDOWN, captureIndex
 *   PEN_MOVE : dx=delta, dy=delta, dt=delta, p, ps=PENDOWN, captureIndex
 *   PEN_UP   : dx=delta, dy=delta, dt=delta, p, ps=PENUP, captureIndex
 *   GROUP    : token GROUP_SEP + ANCRE (absolu ×8)
 *   END      : token END
 *
 * v1.1 : captureIndex pérenne (2 bytes, 0-65535) remplace sr+pr.
 *        Chaque stroke conserve son index dans le strokeRegistry.
 */
class VStarEncoder {

    companion object {
        private const val TAG = "Miroir/VStarEncoder"
        private const val HEADER_MARKER = "\n---\n"
        const val VERSION = "1.1"
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
            destFile.delete()  // garantir un fichier vierge (pas de données résiduelles)
            val out = DataOutputStream(java.io.BufferedOutputStream(FileOutputStream(destFile), 65536))

            // Header JSON v1.1
            val header = """{"format":"miroir-vstar","version":"$VERSION","dpi":$dpi,"strokes":${strokes.size},"groups":${groups.size}}"""
            out.write((header + HEADER_MARKER).toByteArray(Charsets.UTF_8))
            Log.d(TAG, "ENC header écrit v$VERSION, ${groups.size} groupes")

            for ((groupIdx, group) in groups.withIndex()) {
                Log.d(TAG, "ENC groupe $groupIdx: ${group.size} indices, firstIdx=${group.firstOrNull()}")
                // Ancre du groupe
                val firstIdx = group.firstOrNull() ?: continue
                val anchor = anchors[firstIdx] ?: run {
                    val s = strokes.getOrNull(firstIdx)
                    if (s != null && s.points.isNotEmpty()) s.points[0] else Pair(0f, 0f)
                }
                // ═══ Position reconstruite (miroir du décodeur) ═══
                var rx = 0f
                var ry = 0f

                for (i in group.indices) {
                    val idx = group[i]  // ← captureIndex = index pérenne dans strokeRegistry
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
                            // La position reconstruite = valeur décodée (dx/8) — PAS la position réelle
                            val scaledDx = (px * 8).roundToInt().coerceIn(-32768, 32767)
                            val scaledDy = (py * 8).roundToInt().coerceIn(-32768, 32767)
                            dx = scaledDx.toShort()
                            dy = scaledDy.toShort()
                            rx = dx.toFloat() / 8f   // ← reconstruite, miroir du décodeur
                            ry = dy.toFloat() / 8f
                            dt = 0
                            ps = VStarToken.PS_PENDOWN
                        } else {
                            // Delta depuis la position RECONSTRUITE (pas la position réelle)
                            // → l'erreur d'arrondi est identique à celle du décodeur
                            val scaledDx = ((px - rx) * 8).roundToInt().coerceIn(-32768, 32767)
                            val scaledDy = ((py - ry) * 8).roundToInt().coerceIn(-32768, 32767)
                            dx = scaledDx.toShort()
                            dy = scaledDy.toShort()
                            rx += dx.toFloat() / 8f   // ← même accumulation que le décodeur
                            ry += dy.toFloat() / 8f
                            dt = if (j < s.timestamps.size) {
                                ((s.timestamps[j] - s.timestamps.getOrElse(j - 1) { s.timestamps[j] }).toInt()).coerceIn(-32768, 32767).toShort()
                            } else {
                                10
                            }
                            ps = if (j == s.points.size - 1) VStarToken.PS_PENUP else VStarToken.PS_PENDOWN
                        }

                        val p = (s.pressures.getOrElse(j) { 1.0f } * 255).toInt().coerceIn(0, 255)
                        // ═══ Token 14 bytes v1.1 ═══
                        out.writeShort(dx.toInt())       //  0-1 : dx
                        out.writeShort(dy.toInt())       //  2-3 : dy
                        out.writeShort(dt.toInt())       //  4-5 : dt
                        out.writeByte(p)                 //  6   : p
                        out.writeByte(0xFF)              //  7   : az
                        out.writeByte(0xFF)              //  8   : i
                        out.writeByte(ps)                //  9   : ps
                        out.writeByte(0)                 // 10   : h
                        out.writeByte(0)                 // 11   : padding (→ 14 bytes)
                        out.writeShort(idx.coerceIn(0, 65535)) // 12-13 : captureIndex (pérenne)
                    }
                }

                // Token GROUP_SEP (14 octets, PS=4)
                VStarToken.groupSepToken().toBytes(out)
                // Token ANCRE (14 octets, PS=5) : dx=anchorX, dy=anchorY (×8)
                out.writeShort((anchor.first * 8).roundToInt().coerceIn(-32768, 32767))  // dx
                out.writeShort((anchor.second * 8).roundToInt().coerceIn(-32768, 32767)) // dy
                out.writeShort(0)     // dt = 0
                out.writeByte(0)      // p = 0
                out.writeByte(0xFF)   // az
                out.writeByte(0xFF)   // i
                out.writeByte(VStarToken.PS_GROUP_ANCRE) // ps = 5
                out.writeByte(0)      // h
                out.writeByte(0)      // padding
                out.writeShort(0)     // captureIndex = 0 (non applicable)
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
                        Log.i(TAG, "  ENC_group=$gi stroke=$si ci=$idx: first=(${px.toInt()},${py.toInt()}) last=(${lx.toInt()},${ly.toInt()}) pts=${strokes[idx].points.size}")
                    }
                }
            }
            Log.i(TAG, "Encodé v$VERSION: ${strokes.size} strokes, ${groups.size} groupes → ${destFile.length()} B")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur encodage: ${e.message}", e)
        }
    }
}
