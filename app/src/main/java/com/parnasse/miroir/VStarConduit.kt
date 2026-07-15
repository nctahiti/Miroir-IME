package com.parnasse.miroir

import android.util.Log
import java.io.*
import kotlin.math.roundToInt

/**
 * VStarConduit — Le conduit V★ v2.0 unifié (16B alignés).
 *
 * Remplace le double système VStarWriter (v1.1, vstar/) + savePageV2() (v2.0, blocks/).
 * Un seul format, un seul emplacement, un seul mécanisme : append-only temps réel
 * directement dans le fichier de page définitif.
 *
 * Format V★ v2.0 (VStarTokenV2, 16 bytes) :
 *   [HEADER JSON + \n---\n]
 *   [token 0 : 16 octets]
 *   [token 1 : 16 octets]
 *   ...
 *   [END token]
 *
 * Chaque token contient un captureIndex pérenne (Short, 0-65535),
 * créé au PEN_DOWN et immuable pour toute la durée de vie du stroke.
 */
class VStarConduit {

    companion object {
        private const val TAG = "Miroir/VStarConduit"
        const val HEADER_MARKER = "\n---\n"
        const val SCALE_FACTOR = 8.0f
    }

    private var outputStream: DataOutputStream? = null
    private var currentFile: File? = null

    // État pour le calcul des deltas (position reconstruite = miroir du décodeur)
    private var reconstructedX = 0f
    private var reconstructedY = 0f
    private var lastT = 0L
    private var isFirstPoint = true

    // captureIndex pérenne (0-65535)
    private var nextCaptureIndex: Short = 0
    private var currentCaptureIndex: Short = 0

    // Compteur de strokes pour les logs
    private var strokeCount = 0
    private var pointCount = 0

    /** @return le prochain captureIndex (pour initialisation externe après loadPage) */
    fun peekNextCaptureIndex(): Short = nextCaptureIndex

    /** Initialise le compteur de captureIndex (après chargement d'une page existante) */
    fun initCaptureCounter(startFrom: Short) {
        nextCaptureIndex = startFrom
        currentCaptureIndex = (startFrom - 1).coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
        Log.d(TAG, "CaptureCounter initialisé à $nextCaptureIndex")
    }

    /**
     * Ouvre une session d'écriture sur le fichier spécifié.
     * Si le fichier existe déjà, il est OUVERT EN APPEND (session suspendue reprise).
     * Sinon, un nouveau fichier est créé avec le header V★ v2.0.
     *
     * @param file Fichier .vstar cible (ex: blocks/UUID/page_N/page.vstar)
     * @param pageLabel Label pour les logs
     */
    fun open(file: File, pageLabel: String = ""): Boolean {
        close() // fermer la session précédente

        return try {
            file.parentFile?.mkdirs()

            val exists = file.exists()
            val fos = FileOutputStream(file, true) // APPEND — reprend une session suspendue
            val bos = BufferedOutputStream(fos, 65536)
            outputStream = DataOutputStream(bos)

            if (!exists) {
                // Nouveau fichier : pas de header, tokens V★ v2.0 16B bruts dès l'offset 0
                // Compatible avec VStarDataRegion.readAll() qui lit directement les 16B.
                outputStream!!.flush()
                strokeCount = 0
                pointCount = 0
                nextCaptureIndex = 0
                currentCaptureIndex = 0
            } else {
                // Session existante : reprendre après le dernier END token
                // (on écrase le END token précédent — il sera réécrit à la fermeture)
                // Note: on pourrait chercher le dernier END, mais c'est plus simple
                // de laisser le décodeur ignorer les tokens après le premier END.
                // Pour l'instant, on ajoute simplement à la fin.
                Log.i(TAG, "Session reprise: ${file.name} (${file.length()}B existants)")
            }

            currentFile = file
            isFirstPoint = true
            reconstructedX = 0f; reconstructedY = 0f

            Log.i(TAG, "Conduit ouvert: ${file.absolutePath} (V★ v2.0, 16B/token, append=${exists})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur ouverture conduit: ${e.message}", e)
            false
        }
    }

    /**
     * Écrit un point de capture. Appelé depuis le thread UI à chaque événement stylet.
     * Thread-safe : les appels sont sérialisés par le thread UI Android.
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
            val flags: Short

            if (isPenDown) {
                // ═══ PEN_DOWN : coordonnées ABSOLUES ×8 ═══
                dx = toAbs(x)
                dy = toAbs(y)
                dt = 0
                currentCaptureIndex = nextCaptureIndex++
                reconstructedX = dx.toFloat() / SCALE_FACTOR
                reconstructedY = dy.toFloat() / SCALE_FACTOR
                isFirstPoint = false

                // Si un seul point (pas de MOVE), on met les deux flags
                flags = if (isPenUp) {
                    (VStarTokenV2.FLAG_PEN_DOWN.toInt() or VStarTokenV2.FLAG_PEN_UP.toInt()).toShort()
                } else {
                    VStarTokenV2.FLAG_PEN_DOWN
                }
                strokeCount++
            } else if (isPenUp) {
                // ═══ PEN_UP : delta depuis position reconstruite ═══
                dx = toDelta(reconstructedX, x)
                dy = toDelta(reconstructedY, y)
                dt = toDeltaT(lastT, t)
                reconstructedX += dx.toFloat() / SCALE_FACTOR
                reconstructedY += dy.toFloat() / SCALE_FACTOR
                flags = VStarTokenV2.FLAG_PEN_UP
            } else {
                // ═══ MOVE : delta depuis position reconstruite ═══
                dx = toDelta(reconstructedX, x)
                dy = toDelta(reconstructedY, y)
                dt = toDeltaT(lastT, t)
                reconstructedX += dx.toFloat() / SCALE_FACTOR
                reconstructedY += dy.toFloat() / SCALE_FACTOR
                flags = VStarTokenV2.FLAG_NONE
            }

            val p = (pressure * 255).toInt().coerceIn(0, 255)

            val token = VStarTokenV2(
                dx = dx, dy = dy, dt = dt,
                p = p,
                az = VStarTokenV2.AZIMUTH_UNSUPPORTED,
                i = VStarTokenV2.TILT_UNSUPPORTED,
                ps = if (isPenDown) VStarTokenV2.PS_PENDOWN
                     else if (isPenUp) VStarTokenV2.PS_PENUP
                     else VStarTokenV2.PS_PENDOWN,
                h = 0,
                flags = flags,
                captureIndex = currentCaptureIndex
            )
            token.write(out)
            pointCount++

            lastT = t

        } catch (e: Exception) {
            Log.e(TAG, "Erreur écriture point: ${e.message}", e)
        }
    }

    /**
     * Écrit un séparateur de groupe avec son ancre.
     */
    fun writeGroupSep(anchorX: Float = 0f, anchorY: Float = 0f) {
        val out = outputStream ?: return
        try {
            // Réinitialiser la position reconstruite à l'ancre
            reconstructedX = anchorX
            reconstructedY = anchorY
            lastT = System.currentTimeMillis()
            isFirstPoint = false

            val token = VStarTokenV2.groupMeta(anchorX, anchorY, SCALE_FACTOR)
            token.write(out)
            Log.d(TAG, "GROUP_SEP écrit ancre=($anchorX,$anchorY)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur GROUP_SEP: ${e.message}", e)
        }
    }

    /**
     * Marque la fin de la session. Écrit le token END et flush.
     */
    fun endSession() {
        val out = outputStream ?: return
        try {
            VStarTokenV2.endToken().write(out)
            out.flush()
            Log.i(TAG, "Session terminée: ${currentFile?.name} ($strokeCount strokes, $pointCount points)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur END: ${e.message}", e)
        }
    }

    /** Ferme le conduit proprement (sans END token — pour les fermetures temporaires). */
    fun close() {
        try {
            outputStream?.flush()
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null
        currentFile = null
        isFirstPoint = true
        reconstructedX = 0f; reconstructedY = 0f
        lastT = 0L
    }

    /** Flush les buffers sans fermer. */
    fun flush() {
        try { outputStream?.flush() } catch (_: Exception) {}
    }

    fun isActive(): Boolean = outputStream != null
    fun getCurrentFile(): File? = currentFile

    // ── Helpers de conversion ──────────────────────────────────────────

    private fun toDelta(prev: Float, curr: Float): Short {
        val dpx = curr - prev
        val scaled = (dpx * SCALE_FACTOR).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun toAbs(coord: Float): Short {
        val scaled = (coord * SCALE_FACTOR).roundToInt()
        return scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun toDeltaT(prev: Long, curr: Long): Short {
        val dt = (curr - prev).toInt()
        return dt.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
