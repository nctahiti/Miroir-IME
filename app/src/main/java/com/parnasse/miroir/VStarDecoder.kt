package com.parnasse.miroir

import android.util.Log
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * VStarDecoder — Décodeur V★ v1.0.
 *
 * Lit un flux de tokens V★ et émet des événements de stroke/group/label.
 * Travaille en PIXELS NATIFS — l'aller-retour encodeur → décodeur est parfait.
 *
 * Format supporté : v1.0 (pixels natifs, GROUP avec label).
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
        val groups: List<List<Int>>  // chaque groupe = liste d'indices dans strokes
    )

    /** Décode le fichier .vstar et retourne le résultat complet. */
    fun decode(): DecodeResult? {
        try {
            if (!file.exists()) return null
            val bytes = file.readBytes()
            val content = bytes.toString(Charsets.UTF_8)
            val markerIdx = content.indexOf(HEADER_MARKER)
            if (markerIdx < 0) { Log.e(TAG, "Marqueur introuvable"); return null }

            val binaryStart = markerIdx + HEADER_MARKER.length
            val binaryBytes = bytes.copyOfRange(binaryStart, bytes.size)
            val dis = DataInputStream(java.io.ByteArrayInputStream(binaryBytes))

            val strokes = mutableListOf<StrokeRecord>()
            val labels = mutableMapOf<Int, String>()
            val anchors = mutableMapOf<Int, Pair<Float, Float>>()
            val groups = mutableListOf<List<Int>>()
            var currentGroup = mutableListOf<Int>()

            var currentX = 0f
            var currentY = 0f
            var currentTime = 0L
            var currentStroke: StrokeRecord? = null
            val buffer = ByteArray(13)

            while (true) {
                if (dis.available() < 13) break
                dis.readFully(buffer)
                val bits = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.BIG_ENDIAN)
                val dx = bits.getShort(0)
                val dy = bits.getShort(2)
                val dt = bits.getShort(4)
                val p = bits.get(6).toInt() and 0xFF
                // az = bits[7], i = bits[8] — ignorés
                val ps = bits.get(9).toInt() and 0xFF
                // h = bits[10], sr = bits[11], pr = bits[12] — ignorés

                when (ps) {
                    VStarToken.PS_PENDOWN -> {
                        currentX += dx.toFloat()
                        currentY += dy.toFloat()
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
                        currentX += dx.toFloat()
                        currentY += dy.toFloat()
                        currentTime += dt.toLong()
                        if (currentStroke != null) {
                            currentStroke!!.points.add(Pair(currentX, currentY))
                            currentStroke!!.timestamps.add(currentTime)
                            currentStroke!!.pressures.add(p / 255f)
                            strokes.add(currentStroke!!)
                            currentGroup.add(strokes.size - 1)
                            currentStroke = null
                        }
                        // Après PENUP, on attend un nouveau PEN_DOWN → reset position
                        currentX = 0f; currentY = 0f; currentTime = 0L
                    }
                    VStarToken.PS_GROUP_SEP -> {
                        // Lire les données étendues paddées à 13 octets
                        if (dis.available() >= 13) {
                            val ext = ByteArray(13); dis.readFully(ext)
                            val labelLen = ext[0].toInt() and 0xFF
                            val label = if (labelLen > 0) String(ext, 1, labelLen.coerceAtMost(8), Charsets.UTF_8) else ""
                            val ebits = java.nio.ByteBuffer.wrap(ext).order(java.nio.ByteOrder.BIG_ENDIAN)
                            val anchorX = ebits.getShort(9).toFloat()   // après label_len(1) + label(0..8)
                            val anchorY = ebits.getShort(11).toFloat()
                            // Enregistrer le groupe
                            // Enregistrer le groupe
                            if (currentGroup.isNotEmpty()) {
                                val firstIdx = currentGroup.first()
                                labels[firstIdx] = label
                                anchors[firstIdx] = Pair(anchorX, anchorY)
                                groups.add(currentGroup.toList())
                            }
                            currentGroup = mutableListOf()
                            // Réinitialiser la position à l'ancre du groupe pour les strokes suivants
                            currentX = anchorX
                            currentY = anchorY
                            currentTime = 0L
                        }
                    }
                    VStarToken.PS_END -> {
                        // Dernier groupe non fermé
                        if (currentGroup.isNotEmpty()) {
                            groups.add(currentGroup.toList())
                        }
                        break
                    }
                }
            }
            dis.close()
            // Log de trace : premier et dernier point de chaque stroke décodé
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
            Log.i(TAG, "Décodé: ${strokes.size} strokes, ${groups.size} groupes, ${labels.size} labels depuis ${file.name}")
            return DecodeResult(strokes, labels, anchors, groups)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur décodage: ${e.message}")
            return null
        }
    }
}
