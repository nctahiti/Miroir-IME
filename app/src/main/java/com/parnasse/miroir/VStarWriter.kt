package com.parnasse.miroir

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * VStarWriter — Le conduit V★.
 *
 * Écrit les strokes en flux delta binaire (13 octets/point) directement
 * sur le disque. La capture n'est jamais bloquée : écriture append-only
 * atomique par token.
 *
 * Format :
 *   [HEADER_JSON + \n---\n]
 *   [token 0 : 13 octets]
 *   [token 1 : 13 octets]
 *   ...
 *   [GROUP_SEP token]
 *   ...
 *   [END token]
 *
 * Les deltas sont en 0.01 mm. La conversion px → mm utilise la densité
 * d'écran (xdpi/ydpi) fournie par le Context.
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
    private var strokeIndex = 0
    private var pointIndex = 0
    private var isFirstPoint = true

    // Facteur de conversion px → 0.01mm
    private var pxTo001mm = 1.0

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

            // Calculer le facteur de conversion px → 0.01mm
            val metrics = context.resources.displayMetrics
            val xdpi = metrics.xdpi.coerceAtLeast(1f)
            val mmPerPx = 25.4f / xdpi  // 1 px = combien de mm
            pxTo001mm = mmPerPx * 100.0  // conversion en unités 0.01mm

            // Écrire le header JSON
            val headerJson = buildString {
                append("{")
                append("\"format\":\"miroir-vstar\",")
                append("\"version\":\"0.5\",")
                append("\"created_at\":\"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}\",")
                append("\"xdpi\":$xdpi,")
                append("\"conversion\":\"px*${String.format("%.2f", pxTo001mm)}->0.01mm\"")
                append("}")
            }

            val fos = FileOutputStream(file, false) // pas d'append — nouveau fichier
            val bos = java.io.BufferedOutputStream(fos, 65536)  // buffer 64 Ko
            outputStream = DataOutputStream(bos)

            // Header JSON + marqueur binaire
            val headerBytes = (headerJson + HEADER_MARKER).toByteArray(Charsets.UTF_8)
            outputStream!!.write(headerBytes)

            currentFile = file
            sessionFile = file
            isFirstPoint = true
            strokeIndex = 0
            pointIndex = 0

            Log.i(TAG, "Session ouverte: ${file.absolutePath} (${String.format("%.2f", pxTo001mm)} u01mm/px)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Erreur ouverture session: ${e.message}")
            null
        }
    }

    /**
     * Écrit un point de capture. Appelé depuis le thread UI à chaque événement
     * TouchHelper (ACTION_DOWN, ACTION_MOVE, ACTION_UP).
     *
     * @param x coordonnée X absolue en pixels
     * @param y coordonnée Y absolue en pixels
     * @param t timestamp de l'événement (ms)
     * @param pressure pression 0.0..1.0
     * @param isPenDown true si le stylet vient de se poser (ACTION_DOWN)
     * @param isPenUp true si le stylet vient de se lever (ACTION_UP)
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
                // Premier point d'un stroke : delta = 0
                dx = 0
                dy = 0
                dt = 0
                ps = VStarToken.PS_PENDOWN
                if (isPenDown) {
                    strokeIndex++
                    pointIndex = 0
                }
                isFirstPoint = false
            } else if (isPenUp) {
                // Dernier point : delta depuis le dernier point
                dx = toDelta(lastX, x)
                dy = toDelta(lastY, y)
                dt = toDeltaT(lastT, t)
                ps = VStarToken.PS_PENUP
                pointIndex++
            } else {
                // Point intermédiaire
                dx = toDelta(lastX, x)
                dy = toDelta(lastY, y)
                dt = toDeltaT(lastT, t)
                ps = VStarToken.PS_PENDOWN
                pointIndex++
            }

            val p = (pressure * 255).toInt().coerceIn(0, 255)
            val az = VStarToken.AZIMUTH_UNSUPPORTED
            val i = VStarToken.TILT_UNSUPPORTED
            val h: Byte = 0

            // Écrire le token (13 octets)
            out.writeShort(dx.toInt())
            out.writeShort(dy.toInt())
            out.writeShort(dt.toInt())
            out.writeByte(p)
            out.writeByte(az)
            out.writeByte(i)
            out.writeByte(ps)
            out.writeByte(h.toInt() and 0xFF)
            out.writeByte(strokeIndex.coerceIn(0, 255))
            out.writeByte(pointIndex.coerceIn(0, 255))

            // Mise à jour de l'état
            lastX = x
            lastY = y
            lastT = t

        } catch (e: Exception) {
            Log.e(TAG, "Erreur écriture point: ${e.message}")
        }
    }

    /**
     * Marque la fin d'un groupe de mots. Écrit un token GROUP_SEP.
     * Appelé par checkAutoInfer() quand un groupe est confirmé.
     */
    fun writeGroupSep() {
        val out = outputStream ?: return
        try {
            val sep = VStarToken.groupSepToken()
            sep.toBytes(out)
            Log.d(TAG, "GROUP_SEP écrit (strokeIndex=$strokeIndex)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur GROUP_SEP: ${e.message}")
        }
    }

    /**
     * Marque la fin de la session. Écrit un token END et ferme le flux.
     */
    fun writeEnd() {
        val out = outputStream ?: return
        try {
            val end = VStarToken.endToken()
            end.toBytes(out)
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
    }

    /** @return le fichier de la session courante, ou null */
    fun getCurrentFile(): File? = currentFile

    /** @return true si une session est active */
    fun isActive(): Boolean = outputStream != null

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Convertit un delta px en unités 0.01mm (Short). */
    private fun toDelta(prev: Float, curr: Float): Short {
        val dpx = curr - prev
        val d001mm = (dpx * pxTo001mm).toInt()
        return d001mm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Convertit un delta temps (ms) en Short. */
    private fun toDeltaT(prev: Long, curr: Long): Short {
        val dt = (curr - prev).toInt()
        return dt.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
