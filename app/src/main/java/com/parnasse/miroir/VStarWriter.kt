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
                append("\"unit_factor\":${1.0 / pxTo001mm},")
                append("\"conversion\":\"px*${String.format("%.2f", pxTo001mm)}->0.01mm\"")
                append("}")
            }

            val fos = FileOutputStream(file, false) // pas d'append — nouveau fichier
            val bos = java.io.BufferedOutputStream(fos, 65536)  // buffer 64 Ko
            outputStream = DataOutputStream(bos)

            // Header JSON + marqueur binaire
            val headerBytes = (headerJson + HEADER_MARKER).toByteArray(Charsets.UTF_8)
            outputStream!!.write(headerBytes)
            outputStream!!.flush()  // forcer l'écriture du header immédiatement

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
                // Premier point d'un stroke : coordonnées ABSOLUES en 0.01mm
                // (pas de delta — les deltas nuls perdent la position)
                dx = toAbs(x)
                dy = toAbs(y)
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
     * Marque la fin d'un groupe de mots. Écrit le token GROUP_SEP
     * suivi de l'ancre absolue du groupe (x, y en 0.01mm).
     * L'ancre permet au reader de positionner le groupe sans dérive.
     */
    fun writeGroupSep(anchorX: Float = 0f, anchorY: Float = 0f) {
        val out = outputStream ?: return
        try {
            // Token GROUP_SEP
            val sep = VStarToken.groupSepToken()
            sep.toBytes(out)
            // Ancre absolue (2 tokens spéciaux)
            val ax = toAbs(anchorX).toInt()
            val ay = toAbs(anchorY).toInt()
            out.writeByte(VStarToken.PS_GROUP_ANCRE)
            out.writeShort(ax)
            out.writeShort(ay)
            // padding pour garder 13 octets d'alignement
            out.writeShort(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(0)
            out.writeByte(strokeIndex.coerceIn(0, 255))
            out.writeByte(0)
            Log.d(TAG, "GROUP_SEP écrit (strokeIndex=$strokeIndex, ancre=$ax,$ay)")
            // Réinitialiser les deltas — le prochain stroke commence depuis l'ancre
            lastX = anchorX
            lastY = anchorY
            lastT = System.currentTimeMillis()
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

    /** Flush sans fermer — force l'écriture des données bufferisées. */
    fun flush() {
        try { outputStream?.flush() } catch (_: Exception) {}
    }

    /** @return le fichier de la session courante, ou null */
    fun getCurrentFile(): File? = currentFile

    /**
     * Sauvegarde un .vstar complet à partir du strokeRegistry.
     * Utilise les MÊMES facteurs de conversion que le flux temps réel,
     * garantissant un aller-retour parfait.
     */
    fun saveFromStrokes(destFile: File, strokes: List<StrokeRecord>, groups: List<List<Int>>) {
        try {
            val headerJson = buildString {
                append("{")
                append("\"format\":\"miroir-vstar\",")
                append("\"version\":\"0.5\",")
                append("\"created_at\":\"${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())}\",")
                append("\"xdpi\":${context.resources.displayMetrics.xdpi},")
                append("\"unit_factor\":${1.0 / pxTo001mm},")
                append("\"conversion\":\"px*${String.format("%.2f", pxTo001mm)}->0.01mm\"")
                append("}")
            }
            destFile.parentFile?.mkdirs()
            val fos = FileOutputStream(destFile)
            val bos = java.io.BufferedOutputStream(fos, 65536)
            val out = DataOutputStream(bos)

            out.write((headerJson + HEADER_MARKER).toByteArray(Charsets.UTF_8))

            var globalStrokeIdx = 0
            for (group in groups) {
                // Ancre du groupe = premier point du premier stroke
                var groupAnchorX = 0f
                var groupAnchorY = 0f
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
                                // Premier stroke du groupe : absolu
                                groupAnchorX = px; groupAnchorY = py
                                dx = toAbs(px); dy = toAbs(py)
                            } else {
                                // Stroke suivant : delta depuis l'ancre du groupe
                                dx = toDelta(groupAnchorX, px)
                                dy = toDelta(groupAnchorY, py)
                            }
                            dt = 0
                        } else {
                            val (ppx, ppy) = s.points[i - 1]
                            dx = toDelta(ppx, px); dy = toDelta(ppy, py)
                            dt = ((s.timestamps[i] - s.timestamps[i - 1]).toInt()).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                        }
                        val p = (s.pressures.getOrElse(i) { 1.0f } * 255).toInt().coerceIn(0, 255)
                        out.writeShort(dx.toInt())
                        out.writeShort(dy.toInt())
                        out.writeShort(dt.toInt())
                        out.writeByte(p)
                        out.writeByte(0xFF)
                        out.writeByte(0xFF)
                        val ps = if (i == s.points.size - 1) VStarToken.PS_PENUP else VStarToken.PS_PENDOWN
                        out.writeByte(ps)
                        out.writeByte(0)
                        out.writeByte(globalStrokeIdx.coerceIn(0, 255))
                        out.writeByte(i.coerceIn(0, 255))
                    }
                    globalStrokeIdx++
                }
                // GROUP_SEP + ANCRE (format VStarToken correct)
                if (group.isNotEmpty() && group[0] in strokes.indices) {
                    val firstStroke = strokes[group[0]]
                    if (firstStroke.points.isNotEmpty()) {
                        val (ax, ay) = firstStroke.points[0]
                        // Token GROUP_SEP (13 octets)
                        VStarToken.groupSepToken().toBytes(out)
                        // Token ANCRE (13 octets) : dx=ax, dy=ay, dt=0, p=0, az=0xFF, i=0xFF, ps=PS_GROUP_ANCRE, h=0, sr=idx, pr=0
                        out.writeShort(toAbs(ax).toInt())   // dx = ancre X
                        out.writeShort(toAbs(ay).toInt())   // dy = ancre Y
                        out.writeShort(0)                    // dt = 0
                        out.writeByte(0)                     // p = 0
                        out.writeByte(0xFF)                  // az
                        out.writeByte(0xFF)                  // i
                        out.writeByte(VStarToken.PS_GROUP_ANCRE) // ps
                        out.writeByte(0)                     // h
                        out.writeByte(globalStrokeIdx.coerceIn(0, 255)) // sr
                        out.writeByte(0)                     // pr
                    }
                }
            }
            // END
            out.writeByte(VStarToken.PS_END)
            for (i in 0..10) out.writeByte(0)
            out.flush()
            out.close()
            Log.i(TAG, "saveFromStrokes: ${strokes.size} strokes, ${groups.size} groupes → ${destFile.length()} B")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur saveFromStrokes: ${e.message}")
        }
    }

    /** @return true si une session est active */
    fun isActive(): Boolean = outputStream != null

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Convertit un delta px en unités 0.01mm (Short). */
    private fun toDelta(prev: Float, curr: Float): Short {
        val dpx = curr - prev
        val d001mm = (dpx * pxTo001mm).roundToInt()
        return d001mm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Convertit une coordonnée absolue px en unités 0.01mm (Short). */
    private fun toAbs(coord: Float): Short {
        val abs001mm = (coord * pxTo001mm).roundToInt()
        return abs001mm.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** Convertit un delta temps (ms) en Short. */
    private fun toDeltaT(prev: Long, curr: Long): Short {
        val dt = (curr - prev).toInt()
        return dt.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
