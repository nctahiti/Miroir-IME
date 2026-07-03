package com.parnasse.miroir

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * VStarWriter — Le conduit V★ (flux temps réel).
 *
 * Écrit les strokes en flux delta binaire (14 octets/point) directement
 * sur le disque. La capture n'est jamais bloquée : écriture append-only
 * atomique par token.
 *
 * Format v1.1 (14 octets/token) :
 *   [HEADER_JSON + \n---\n]
 *   [token 0 : 14 octets]
 *   [token 1 : 14 octets]
 *   ...
 *   [GROUP_SEP token]
 *   ...
 *   [END token]
 *
 * Chaque token contient un captureIndex pérenne (2 bytes, 0-65535),
 * créé au PEN_DOWN et immuable pour toute la durée de vie du stroke.
 */
class VStarWriter(private val context: Context) {

    companion object {
        private const val TAG = "Miroir/VStarWriter"
        const val HEADER_MARKER = "\n---\n"
    }

    private var outputStream: DataOutputStream? = null
    private var currentFile: File? = null
    private var sessionFile: File? = null

    // État pour le calcul des deltas
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var isFirstPoint = true
    // ═══ Position reconstruite (miroir du décodeur) — évite l'erreur d'arrondi cumulative ═══
    private var reconstructedX = 0f
    private var reconstructedY = 0f

    // ═══ captureIndex pérenne (0-65535) ═══
    private var nextCaptureIndex: Int = 0
    private var currentCaptureIndex: Int = 0

    // Facteur d'échelle (8.0 = sub-pixel 1/8 px, précis et portable)
    private var scaleFactor = 8.0

    /** @return le prochain captureIndex (pour initialisation externe) */
    fun peekNextCaptureIndex(): Int = nextCaptureIndex

    /** Initialise le compteur de captureIndex (après chargement d'une page) */
    fun initCaptureCounter(startFrom: Int) {
        nextCaptureIndex = startFrom
        Log.d(TAG, "CaptureCounter initialisé à $nextCaptureIndex")
    }

    /**
     * Ouvre une nouvelle session V★. Crée le fichier dans filesDir/vstar/.
     * @return le fichier créé, ou null si erreur.
     */
    fun openNewSession(sessionLabel: String = ""): File? {
        close() // fermer la session précédente

        return try {
            val dir = File(context.filesDir, "vstar")
            dir.mkdirs()

            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val safeLabel = if (sessionLabel.isNotEmpty())
                "_" + sessionLabel.replace("[^a-zA-Z0-9_]".toRegex(), "_").take(32)
            else ""
            val file = File(dir, "session_${ts}${safeLabel}.vstar")

            scaleFactor = 8.0

            // Écrire le header JSON v1.1
            val headerJson = buildString {
                append("{")
                append("\"format\":\"miroir-vstar\",")
                append("\"version\":\"1.1\",")
                append("\"created_at\":\"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}\",")
                append("\"scale\":\"pixels×8\",")
                append("\"unit_factor\":0.125,")
                append("\"conversion\":\"px×8→short, ÷8→px\"")
                append("}")
            }

            val fos = FileOutputStream(file, false)
            val bos = java.io.BufferedOutputStream(fos, 65536)
            outputStream = DataOutputStream(bos)

            val headerBytes = (headerJson + HEADER_MARKER).toByteArray(Charsets.UTF_8)
            outputStream!!.write(headerBytes)
            outputStream!!.flush()

            currentFile = file
            sessionFile = file
            isFirstPoint = true
            nextCaptureIndex = 0
            currentCaptureIndex = 0
            reconstructedX = 0f; reconstructedY = 0f

            Log.i(TAG, "Session ouverte v1.1: ${file.absolutePath} (pixels natifs, 14B/token)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Erreur ouverture session: ${e.message}")
            null
        }
    }

    /**
     * Écrit un point de capture. Appelé depuis le thread UI à chaque événement
     * TouchHelper (ACTION_DOWN, ACTION_MOVE, ACTION_UP).
     */
    fun writePoint(
        x: Float, y: Float, t: Long, pressure: Float,
        isPenDown: Boolean = false, isPenUp: Boolean = false
    ) {
        val out = outputStream ?: return

        try {
            val dx: Short
            val dy: Short
            val dt: Short
            val ps: Int

            if (isFirstPoint || isPenDown) {
                // Premier point d'un stroke : coordonnées ABSOLUES
                dx = toAbs(x)
                dy = toAbs(y)
                dt = 0
                ps = VStarToken.PS_PENDOWN
                // ═══ Position reconstruite = valeur décodée (dx/8) ═══
                reconstructedX = dx.toFloat() / scaleFactor.toFloat()
                reconstructedY = dy.toFloat() / scaleFactor.toFloat()
                if (isPenDown) {
                    currentCaptureIndex = nextCaptureIndex++
                }
                isFirstPoint = false
            } else if (isPenUp) {
                // Dernier point : delta depuis la position RECONSTRUITE
                dx = toDelta(reconstructedX, x)
                dy = toDelta(reconstructedY, y)
                dt = toDeltaT(lastT, t)
                ps = VStarToken.PS_PENUP
                reconstructedX += dx.toFloat() / scaleFactor.toFloat()
                reconstructedY += dy.toFloat() / scaleFactor.toFloat()
            } else {
                // Point intermédiaire : delta depuis la position RECONSTRUITE
                dx = toDelta(reconstructedX, x)
                dy = toDelta(reconstructedY, y)
                dt = toDeltaT(lastT, t)
                ps = VStarToken.PS_PENDOWN
                reconstructedX += dx.toFloat() / scaleFactor.toFloat()
                reconstructedY += dy.toFloat() / scaleFactor.toFloat()
            }

            val p = (pressure * 255).toInt().coerceIn(0, 255)
            val az = VStarToken.AZIMUTH_UNSUPPORTED
            val i = VStarToken.TILT_UNSUPPORTED
            val h: Byte = 0

            // ═══ Token 14 bytes v1.1 ═══
            out.writeShort(dx.toInt())                          //  0-1 : dx
            out.writeShort(dy.toInt())                          //  2-3 : dy
            out.writeShort(dt.toInt())                          //  4-5 : dt
            out.writeByte(p)                                    //  6   : p
            out.writeByte(az)                                   //  7   : az
            out.writeByte(i)                                    //  8   : i
            out.writeByte(ps)                                   //  9   : ps
            out.writeByte(h.toInt() and 0xFF)                   // 10   : h
            out.writeByte(0)                                    // 11   : padding
            out.writeShort(currentCaptureIndex.coerceIn(0, 65535)) // 12-13 : captureIndex

            // Mise à jour de l'état
            lastX = x
            lastY = y
            lastT = t

        } catch (e: Exception) {
            Log.e(TAG, "Erreur écriture point: ${e.message}")
        }
    }

    /**
     * Marque la fin d'un groupe de mots. Écrit le token GROUP_SEP
     * suivi de l'ancre absolue du groupe.
     */
    fun writeGroupSep(anchorX: Float = 0f, anchorY: Float = 0f) {
        val out = outputStream ?: return
        try {
            // Token GROUP_SEP (14 octets)
            VStarToken.groupSepToken().toBytes(out)
            // Ancre absolue (14 octets, PS_GROUP_ANCRE)
            val ax = toAbs(anchorX).toInt()
            val ay = toAbs(anchorY).toInt()
            out.writeShort(ax)                                   // dx = ancre X
            out.writeShort(ay)                                   // dy = ancre Y
            out.writeShort(0)                                    // dt = 0
            out.writeByte(0)                                     // p = 0
            out.writeByte(0xFF)                                  // az
            out.writeByte(0xFF)                                  // i
            out.writeByte(VStarToken.PS_GROUP_ANCRE)             // ps = 5
            out.writeByte(0)                                     // h
            out.writeByte(0)                                     // padding
            out.writeShort(currentCaptureIndex.coerceIn(0, 65535)) // captureIndex
            Log.d(TAG, "GROUP_SEP écrit (ci=$currentCaptureIndex, ancre=$ax,$ay)")
            // ═══ Réinitialiser la position reconstruite à l'ancre ═══
            reconstructedX = anchorX
            reconstructedY = anchorY
            lastX = anchorX
            lastY = anchorY
            lastT = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur GROUP_SEP: ${e.message}")
        }
    }

    /** Marque la fin de la session. */
    fun writeEnd() {
        val out = outputStream ?: return
        try {
            VStarToken.endToken().toBytes(out)
            out.flush()
            Log.i(TAG, "Session terminée: ${currentFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur END: ${e.message}")
        }
    }

    /** Ferme le flux proprement. */
    fun close() {
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
        currentFile = null
        isFirstPoint = true
        lastX = 0f; lastY = 0f; lastT = 0L
        reconstructedX = 0f; reconstructedY = 0f
    }

    fun flush() {
        try { outputStream?.flush() } catch (_: Exception) {}
    }

    fun getCurrentFile(): File? = currentFile
    fun isActive(): Boolean = outputStream != null

    /**
     * Sauvegarde un .vstar complet à partir du strokeRegistry.
     * Utilise les MÊMES facteurs de conversion que le flux temps réel.
     */
    fun saveFromStrokes(destFile: File, strokes: List<StrokeRecord>, groups: List<List<Int>>) {
        try {
            val headerJson = buildString {
                append("{")
                append("\"format\":\"miroir-vstar\",")
                append("\"version\":\"1.1\",")
                append("\"created_at\":\"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}\",")
                append("\"scale\":\"pixels×8\",")
                append("\"unit_factor\":0.125,")
                append("\"conversion\":\"px×8→short, ÷8→px\"")
                append("}")
            }
            destFile.parentFile?.mkdirs()
            val fos = FileOutputStream(destFile)
            val bos = java.io.BufferedOutputStream(fos, 65536)
            val out = DataOutputStream(bos)

            out.write((headerJson + HEADER_MARKER).toByteArray(Charsets.UTF_8))

            for (group in groups) {
                var rx = 0f  // position reconstruite (miroir décodeur)
                var ry = 0f
                var isFirstInGroup = true
                for (idx in group) {
                    if (idx >= strokes.size) continue
                    val s = strokes[idx]
                    for (i in s.points.indices) {
                        val (px, py) = s.points[i]
                        val dx: Short
                        val dy: Short
                        val dt: Short
                        if (i == 0) {
                            if (isFirstInGroup) {
                                dx = toAbs(px); dy = toAbs(py)
                            } else {
                                dx = toDelta(rx, px)  // delta depuis position reconstruite
                                dy = toDelta(ry, py)
                            }
                            rx = dx.toFloat() / scaleFactor.toFloat()
                            ry = dy.toFloat() / scaleFactor.toFloat()
                            dt = 0
                            isFirstInGroup = false
                        } else {
                            dx = toDelta(rx, px); dy = toDelta(ry, py)
                            rx += dx.toFloat() / scaleFactor.toFloat()
                            ry += dy.toFloat() / scaleFactor.toFloat()
                            dt = ((s.timestamps[i] - s.timestamps[i - 1]).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        val p = (s.pressures.getOrElse(i) { 1.0f } * 255).toInt().coerceIn(0, 255)
                        val ps = if (i == s.points.size - 1) VStarToken.PS_PENUP else VStarToken.PS_PENDOWN
                        // ═══ Token 14 bytes v1.1 ═══
                        out.writeShort(dx.toInt())
                        out.writeShort(dy.toInt())
                        out.writeShort(dt.toInt())
                        out.writeByte(p)
                        out.writeByte(0xFF)
                        out.writeByte(0xFF)
                        out.writeByte(ps)
                        out.writeByte(0)
                        out.writeByte(0)  // padding
                        out.writeShort(idx.coerceIn(0, 65535))  // captureIndex = idx pérenne
                    }
                }
                // GROUP_SEP + ANCRE
                if (group.isNotEmpty() && group[0] in strokes.indices) {
                    val firstStroke = strokes[group[0]]
                    if (firstStroke.points.isNotEmpty()) {
                        val (ax, ay) = firstStroke.points[0]
                        VStarToken.groupSepToken().toBytes(out)
                        out.writeShort(toAbs(ax).toInt())
                        out.writeShort(toAbs(ay).toInt())
                        out.writeShort(0)
                        out.writeByte(0)
                        out.writeByte(0xFF)
                        out.writeByte(0xFF)
                        out.writeByte(VStarToken.PS_GROUP_ANCRE)
                        out.writeByte(0)
                        out.writeByte(0)  // padding
                        out.writeShort(0)
                    }
                }
            }
            // END
            VStarToken.endToken().toBytes(out)
            out.flush()
            out.close()
            Log.i(TAG, "saveFromStrokes v1.1: ${strokes.size} strokes, ${groups.size} groupes → ${destFile.length()} B")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur saveFromStrokes: ${e.message}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun toDelta(prev: Float, curr: Float): Short {
        val dpx = curr - prev
        val scaled = (dpx * scaleFactor).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun toAbs(coord: Float): Short {
        val scaled = (coord * scaleFactor).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun toDeltaT(prev: Long, curr: Long): Short {
        val dt = (curr - prev).toInt()
        return dt.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
