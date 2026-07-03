package com.parnasse.miroir

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * VStarDecoder — Décodeur V★ v1.1.
 *
 * Lit un flux de tokens V★ et émet des événements de stroke/group/label.
 * Travaille en PIXELS (÷8) — les valeurs Short sont divisées par 8
 * pour retrouver les pixels natifs (précision 1/8 px).
 *
 * v1.1 : captureIndex pérenne (2 bytes, 0-65535).
 *        Le mapping captureIndex → strokeRegistry n'est plus séquentiel.
 *
 * Format supporté : v1.0 (13 bytes, sr+pr), v1.1 (14 bytes, captureIndex).
 */
class VStarDecoder(private val file: File) {

    companion object {
        private const val TAG = "Miroir/VStarDecoder"
        private const val HEADER_MARKER = "\n---\n"
    }

    data class DecodeResult(
        val strokes: List<StrokeRecord>,
        val labels: Map<Int, String>,
        val anchors: Map<Int, Pair<Float, Float>>,
        val groups: List<List<Int>>,  // chaque groupe = liste d'indices dans strokes
        val captureIndexToRegistry: Map<Int, Int>  // captureIndex → index dans strokes
    )

    /** Décode le fichier .vstar et retourne le résultat complet. */
    fun decode(): DecodeResult? {
        try {
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val content = bytes.toString(Charsets.UTF_8)
            val markerIdx = content.indexOf(HEADER_MARKER)
            if (markerIdx < 0) { Log.e(TAG, "Marqueur introuvable"); return null }

            // Détecter la version
            val headerStr = content.substring(0, markerIdx)
            val isV11 = headerStr.contains("\"version\":\"1.1\"")
            val tokenSize = if (isV11) 14 else 13
            Log.i(TAG, "DEC version=${if (isV11) "1.1" else "1.0"}, tokenSize=$tokenSize")

            val binaryStart = markerIdx + HEADER_MARKER.length
            val binaryBytes = bytes.copyOfRange(binaryStart, bytes.size)
            val dis = DataInputStream(java.io.ByteArrayInputStream(binaryBytes))

            val strokes = mutableListOf<StrokeRecord>()
            val labels = mutableMapOf<Int, String>()
            val anchors = mutableMapOf<Int, Pair<Float, Float>>()
            val groups = mutableListOf<List<Int>>()
            val captureIndexToRegistry = mutableMapOf<Int, Int>()
            var currentGroup = mutableListOf<Int>()

            var currentX = 0f
            var currentY = 0f
            var currentTime = 0L
            var currentStroke: StrokeRecord? = null
            val buffer = ByteArray(tokenSize)

            while (true) {
                if (dis.available() < tokenSize) break
                dis.readFully(buffer)
                val bits = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.BIG_ENDIAN)
                val dx = bits.getShort(0)
                val dy = bits.getShort(2)
                val dt = bits.getShort(4)
                val p = bits.get(6).toInt() and 0xFF
                // az = bits[7], i = bits[8] — ignorés
                val ps = bits.get(9).toInt() and 0xFF
                // h = bits[10]

                // captureIndex : 2 bytes en v1.1 (bits 12-13), 1 byte en v1.0 (bit 11)
                val captureIndex: Int = if (isV11) {
                    bits.getShort(12).toInt() and 0xFFFF
                } else {
                    bits.get(11).toInt() and 0xFF
                }

                when (ps) {
                    VStarToken.PS_PENDOWN -> {
                        if (currentStroke == null) {
                            currentX = dx.toFloat() / 8f
                            currentY = dy.toFloat() / 8f
                        } else {
                            currentX += dx.toFloat() / 8f
                            currentY += dy.toFloat() / 8f
                        }
                        currentTime += dt.toLong()
                        if (currentStroke == null) {
                            // Nouveau stroke
                            currentStroke = StrokeRecord(id = java.util.UUID.randomUUID().toString())
                            currentStroke!!.points.add(Pair(currentX, currentY))
                            currentStroke!!.timestamps.add(currentTime)
                            currentStroke!!.pressures.add(p / 255f)
                        } else {
                            // Point intermédiaire du stroke en cours
                            currentStroke!!.points.add(Pair(currentX, currentY))
                            currentStroke!!.timestamps.add(currentTime)
                            currentStroke!!.pressures.add(p / 255f)
                        }
                    }
                    VStarToken.PS_PENUP -> {
                        currentX += dx.toFloat() / 8f
                        currentY += dy.toFloat() / 8f
                        currentTime += dt.toLong()
                        if (currentStroke != null) {
                            currentStroke!!.points.add(Pair(currentX, currentY))
                            currentStroke!!.timestamps.add(currentTime)
                            currentStroke!!.pressures.add(p / 255f)
                            val registryIdx = strokes.size
                            strokes.add(currentStroke!!)
                            currentGroup.add(registryIdx)
                            // ═══ Mapping captureIndex → registryIndex ═══
                            captureIndexToRegistry[captureIndex] = registryIdx
                            currentStroke = null
                        }
                        currentX = 0f; currentY = 0f; currentTime = 0L
                    }
                    VStarToken.PS_GROUP_SEP -> {
                        if (currentGroup.isNotEmpty()) {
                            groups.add(currentGroup.toList())
                        }
                        currentGroup = mutableListOf()
                    }
                    VStarToken.PS_GROUP_ANCRE -> {
                        val anchorX = dx.toFloat() / 8f
                        val anchorY = dy.toFloat() / 8f
                        // L'ancre est pour le prochain groupe
                        val firstIdx = strokes.size
                        anchors[firstIdx] = Pair(anchorX, anchorY)
                        currentX = anchorX
                        currentY = anchorY
                        currentTime = 0L
                    }
                    VStarToken.PS_END -> {
                        if (currentGroup.isNotEmpty()) {
                            groups.add(currentGroup.toList())
                        }
                        break
                    }
                }
            }
            dis.close()
            // Log de trace
            for ((gi, group) in groups.withIndex()) {
                for (si in group.indices) {
                    val idx = group[si]
                    if (idx < strokes.size && strokes[idx].points.isNotEmpty()) {
                        val (px, py) = strokes[idx].points[0]
                        val (lx, ly) = strokes[idx].points.last()
                        Log.i(TAG, "  DEC_group=$gi stroke=$si: first=(${px.toInt()},${py.toInt()}) last=(${lx.toInt()},${ly.toInt()}) pts=${strokes[idx].points.size}")
                    }
                }
            }
            Log.i(TAG, "Décodé: ${strokes.size} strokes, ${groups.size} groupes, ${captureIndexToRegistry.size} captureIndex mappés depuis ${file.name}")
            return DecodeResult(strokes, labels, anchors, groups, captureIndexToRegistry)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur décodage: ${e.message}")
            return null
        }
    }
}
