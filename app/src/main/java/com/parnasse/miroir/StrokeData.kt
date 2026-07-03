package com.parnasse.miroir

import org.json.JSONObject
import org.json.JSONArray
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log

/**
 * StrokeData — Format V* (version 0.5)
 *
 * Atome : VStarToken = [dx, dy, dt, p, az, i, ps, h, sr, pr]
 *   dx, dy   : Short (int16) — delta position en 0.01 mm
 *   dt       : UShort (int16) — delta temps ms (signé pour v0.5)
 *   p        : pressure 0..255
 *   az       : azimuth 0..360 (ou 0xFF si non supporté)
 *   i        : inclination 0..90 (ou 0xFF si non supporté)
 *   ps       : pen state 0=penup 1=pendown 2=survol 3=fin
 *   h        : modulation d'ornement (-128..127, 0 = pas d'ornement)
 *   sr       : Byte — index séquentiel du stroke (0, 1, 2...)
 *   pr       : Byte — index séquentiel du point dans le stroke (0, 1, 2...)
 *
 * Fichier .vstar (binaire) :
 *   [HEADER_JSON: jusqu'à 4 Ko | \n---\n]
 *   [token 0 : 13 octets]
 *   [token 1 : 13 octets]
 *   ...
 *
 * HEADER_JSON :
 *   { "format":"miroir-vstar", "version":"0.5",
 *     "dimensions":["x","y","t","p","az","i","ps","h","sr","pr"],
 *     "device":"...", "resolution":"...", "created_at":"..." }
 */

// ── Token V* 10D (format mémoire) ────────────────────────────────────
// Conservé pour le mode EDIT (VStarDocument).
// Le format binaire V* n'est plus écrit (remplacé par le CSV de CaptureView).
// VStarWriter a été supprimé — il n'était jamais instancié.

data class VStarToken(
    val dx: Short,          // Delta x en 0.01 mm
    val dy: Short,          // Delta y en 0.01 mm
    val dt: Short,          // Delta t en ms (signé)
    val p: Int,             // Pression 0..255 (UByte)
    val az: Int,            // Azimuth 0..360 (ou 0xFF = non supporté)
    val i: Int,             // Inclinaison 0..90 (ou 0xFF = non supporté)
    val ps: Int,            // Pen state 0..5
    val h: Byte,            // Ornement (-128..127, 0 = pas d'ornement)
    val captureIndex: Int = 0  // Index de capture pérenne (0-65535, Short)
) {

    /** Sérialise ce token en 14 octets */
    fun toBytes(out: DataOutputStream) {
        out.writeShort(dx.toInt())
        out.writeShort(dy.toInt())
        out.writeShort(dt.toInt())
        out.writeByte(p and 0xFF)
        out.writeByte(az and 0xFF)
        out.writeByte(i and 0xFF)
        out.writeByte(ps and 0xFF)
        out.writeByte(h.toInt() and 0xFF)
        out.writeByte(0)  // padding (→ 14 bytes)
        out.writeShort(captureIndex.coerceIn(0, 65535))
    }

    companion object {
        const val SIZE_BYTES = 14

        // Flags dimensionnels
        const val AZIMUTH_UNSUPPORTED = 0xFF
        const val TILT_UNSUPPORTED = 0xFF

        // Pen states
        const val PS_PENUP = 0
        const val PS_PENDOWN = 1
        const val PS_HOVER = 2
        const val PS_END = 3
        const val PS_GROUP_SEP = 4
        const val PS_GROUP_ANCRE = 5  // suivi de 12 octets: ancre X (Short) + Y (Short)

        /** Token separateur de groupe de mots (ps=4) */
        fun groupSepToken(): VStarToken {
            return VStarToken(0, 0, 0, 0, 0, 0, PS_GROUP_SEP, 0, captureIndex = 0)
        }

        /** Désérialise 14 octets (v1.1) ou 13 octets (v0.5/v1.0) → VStarToken */
        fun fromBytes(`in`: DataInputStream, extended: Boolean = true, isV11: Boolean = false): VStarToken {
            val dx = `in`.readShort()
            val dy = `in`.readShort()
            val dt = `in`.readShort()
            val p = `in`.readUnsignedByte()
            val az = `in`.readUnsignedByte()
            val i = `in`.readUnsignedByte()
            val ps = `in`.readUnsignedByte()
            val h = `in`.readByte()
            val captureIndex: Int
            if (isV11) {
                `in`.readByte()  // skip padding byte
                captureIndex = `in`.readUnsignedShort()  // 2 bytes, 0-65535
            } else {
                val sr = if (extended) `in`.readUnsignedByte() else 0
                val pr = if (extended) `in`.readUnsignedByte() else 0
                captureIndex = sr  // rétrocompatibilité : sr devient captureIndex
            }
            return VStarToken(dx, dy, dt, p, az, i, ps, h, captureIndex = captureIndex)
        }

        /** Token de fin de session */
        fun endToken(): VStarToken {
            return VStarToken(0, 0, 0, 0, 0, 0, PS_END, 0, captureIndex = 0)
        }
    }

    override fun toString(): String {
        val dims = buildString {
            append("[dx=$dx dy=$dy dt=$dt")
            if (p != 0) append(" p=$p")
            if (az != AZIMUTH_UNSUPPORTED) append(" az=$az")
            if (i != TILT_UNSUPPORTED) append(" i=$i")
            append(" ps=$ps")
            if (h != 0.toByte()) append(" h=$h")
            append("]")
        }
        return dims
    }
}

// ── Lecteur de flux V* ─────────────────────────────────────────────
// Conservé pour le mode EDIT (VStarDocument).

class VStarReader(private val file: File) {

    companion object {
        private const val TAG = "Miroir/VStarReader"
        private const val HEADER_MARKER = "\n---\n"
    }

    data class VStarSession(
        val header: JSONObject,
        val tokens: List<VStarToken>
    )

    /** Lit et retourne la session V* complete */
    fun read(): VStarSession? {
        return try {
            val bytes = file.readBytes()
            val content = bytes.toString(Charsets.UTF_8)

            val markerIdx = content.indexOf(HEADER_MARKER)
            if (markerIdx < 0) {
                Log.e(TAG, "Marqueur d'en-tete introuvable")
                return null
            }

            val headerJson = content.substring(0, markerIdx)
            val header = JSONObject(headerJson)

            val binaryStart = markerIdx + HEADER_MARKER.length
            val binaryBytes = bytes.copyOfRange(
                minOf(binaryStart, bytes.size),
                bytes.size
            )

            // Detecter le format de version pour la taille des tokens
            val ver = header.optString("version", "0.4")
            val isV05 = ver.startsWith("0.5")
            val isV10 = ver.startsWith("1.0")
            val isV11 = ver.startsWith("1.1")
            val tokenSize = when {
                isV11 -> 14
                isV05 || isV10 -> 13
                else -> 11
            }

            val tokens = mutableListOf<VStarToken>()
            val bais = ByteArrayInputStream(binaryBytes)
            val dis = DataInputStream(bais)

            while (bais.available() >= tokenSize) {
                val token = VStarToken.fromBytes(dis, isV05 || isV10, isV11)
                tokens.add(token)
                if (token.ps == VStarToken.PS_END) break
            }

            dis.close()
            Log.i(TAG, "Lu: ${tokens.size} tokens (v$ver, ${tokenSize}B/token) depuis ${file.absolutePath}")
            VStarSession(header, tokens)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lecture: ${e.message}")
            null
        }
    }
}
