package com.parnasse.miroir

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** VStarSculptor — le sculpteur de forme. « Quand une note vit mal, façonner la forme, jamais le sens. »
 *  La sentinelle veille (forme ancienne détectée) ; le sculpteur façonne (v1.1 14 B → v2 16 B, ci pérennes
 *  intacts). Le sens (points, labels, ancres) n'est jamais retouché. L'original reste en .bak (la trace).
 *  Règle d'or : ne façonner que la forme reconnue ; l'inconnue reste en trace. */
object VStarSculptor {

    private const val TAG = "Miroir/Sculpteur"
    private val V1_HEADER_MARKER = "\n---\n".toByteArray(Charsets.UTF_8)

    // Pen states v1.1 (VStarToken)
    private const val PS_PENUP = 0
    private const val PS_PENDOWN = 1
    private const val PS_END = 3
    private const val PS_GROUP_SEP = 4
    private const val PS_GROUP_ANCRE = 5

    /** La sentinelle : le fichier porte-t-il la forme v1.1 ? */
    fun needsSculpting(file: File): Boolean {
        if (!file.exists() || file.length() < V1_HEADER_MARKER.size + 28) return false
        return try {
            val head = ByteArray(512)
            FileInputStream(file).use { ins -> ins.read(head) }
            val n = minOf(head.size, file.length().toInt())
            val sample = String(head, 0, n, Charsets.UTF_8)
            sample.contains("\"format\":\"miroir-vstar\"") && sample.contains("---")
        } catch (e: Exception) {
            Log.w(TAG, "needsSculpting: ${e.message}")
            false
        }
    }

    /** Façonner : migrer la forme. @return true si la forme a été migrée. */
    fun sculpter(file: File): Boolean {
        if (!needsSculpting(file)) return false
        val start = System.currentTimeMillis()
        return try {
            val bak = File(file.parentFile, file.name + ".bak")
            if (bak.exists()) bak.delete()
            file.copyTo(bak, overwrite = true)          // ⚠️ la trace avant le geste

            val raw = file.readBytes()
            val headerEnd = indexOfMarker(raw, V1_HEADER_MARKER)
            if (headerEnd < 0) return false
            val bodyStart = headerEnd + V1_HEADER_MARKER.size

            val temp = File(file.parentFile, file.name + ".sculpt")
            val out = FileOutputStream(temp)

            var read = bodyStart
            var count = 0
            var prevCi = -1
            var penDown = true                            // le 1er point d'un groupe est absolu
            var stop = false

            while (!stop && read + 14 <= raw.size) {
                val b = raw
                val dx = ((b[read].toInt() and 0xFF) or ((b[read + 1].toInt() and 0xFF) shl 8)).toShort()
                val dy = ((b[read + 2].toInt() and 0xFF) or ((b[read + 3].toInt() and 0xFF) shl 8)).toShort()
                val dt = ((b[read + 4].toInt() and 0xFF) or ((b[read + 5].toInt() and 0xFF) shl 8)).toShort()
                val p = b[read + 6].toInt() and 0xFF
                val az = b[read + 7].toInt() and 0xFF
                val i2 = b[read + 8].toInt() and 0xFF
                val ps = b[read + 9].toInt() and 0xFF
                val h = b[read + 10]
                val ci = ((b[read + 12].toInt() and 0xFF) or ((b[read + 13].toInt() and 0xFF) shl 8))

                // ═══ FRONTIÈRE V2 : le parse 14B sur la zone 16B casse la forme v1 —
                // ps illégal ou ci hors séquence → la suite est v2 → laisser en trace.
                if (ps !in setOf(PS_PENDOWN, PS_PENUP, PS_END, PS_GROUP_SEP, PS_GROUP_ANCRE)) { stop = true; break }
                if (prevCi >= 0 && ci != prevCi + 1 && ci != prevCi) { stop = true; break }

                val flags = when {
                    ps == PS_END || ps == PS_GROUP_SEP || ps == PS_GROUP_ANCRE -> 8   // META (séparateur/ancre)
                    ps == PS_PENUP -> 2                                                // FIN de trait
                    penDown -> 1                                                       // DÉBUT de trait
                    else -> 0                                                          // continuation
                }
                penDown = (ps == PS_PENUP || ps == PS_GROUP_SEP || ps == PS_GROUP_ANCRE)

                out.write(to16(dx, dy, dt, p, az, i2, ps, h, flags, ci))
                prevCi = ci
                read += 14
                count++
                if (ps == PS_END) stop = true
            }
            out.close()

            if (count > 0 && temp.length() > 0) {
                if (file.exists()) file.delete()
                temp.renameTo(file)
                Log.i(TAG, "Forme façonnée : ${file.name} — $count tokens v1.1 → v2 (${System.currentTimeMillis() - start}ms, ${file.length()} B, .bak conservé)")
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "sculpter: ${e.message}")
            false
        }
    }

    private fun to16(dx: Short, dy: Short, dt: Short, p: Int, az: Int, i: Int, ps: Int, h: Byte, flags: Int, ci: Int): ByteArray {
        val buf = ByteArray(16)
        putShort(buf, 0, dx); putShort(buf, 2, dy); putShort(buf, 4, dt)
        buf[6] = p.toByte(); buf[7] = az.toByte(); buf[8] = i.toByte(); buf[9] = ps.toByte(); buf[10] = h
        buf[11] = 0
        putShort(buf, 12, flags.toShort()); putShort(buf, 14, ci.toShort())
        return buf
    }

    private fun putShort(buf: ByteArray, off: Int, v: Short) {
        buf[off] = (v.toInt() and 0xFF).toByte()
        buf[off + 1] = ((v.toInt() shr 8) and 0xFF).toByte()
    }

    private fun indexOfMarker(bytes: ByteArray, marker: ByteArray): Int {
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
