package com.parnasse.miroir

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

/**
 * VStarDocument �?" Manipulation de fichiers V* (.vstar) pour eǹition et sauvegarde.
 *
 * Gestionnaire de document pour le format V*.
 * API de bas niveau : tokens (VStarToken) <-> StrokeRecord.
 *
 * Champs critiques :
 *   unitFactor : 0.01 (mm/pixel) pour les tokens existants
 *   saveUnitFactor : 0.1 pour la sauvegarde (stockage compresseǹ)
 *   hasOrigin : si le fichier source avait une origine (origin_x, origin_y)
 */
class VStarDocument(private val file: File) {

    companion object {
        private const val TAG = "Miroir/VStarDocument"
        private const val HEADER_MARKER = "\n---\n"
        private const val FLUSH_INTERVAL = 5000  // ms entre sauvegardes automatiques
    }

    // En-tete et meta-donnees
    var headerJson: String = ""
        private set
    var header: JSONObject? = null
        private set
    val strokes: MutableList<StrokeRecord> = ArrayList()
    var resolution: String = "1860×2480"
        private set
    var deviceName: String = ""
        private set
    var createdAt: String = ""
        private set

    // Système de coordonnees
    var hasOrigin: Boolean = false
        internal set
    var unitFactor: Float = 0.01f
        private set
    var isDirty: Boolean = false
        private set
    var tokenCount: Int = 0
        private set
    var groupSepCount: Int = 0
        private set

    // Verification
    var verificationStatus: VerificationStatus = VerificationStatus.UNVERIFIED

    // Nouvelles origines (pour saveAs)
    private var newOriginX: Float? = null
    private var newOriginY: Float? = null
    private val saveUnitFactor: Float = 0.1f

    enum class VerificationStatus(val label: String) {
        UNVERIFIED("non vériﬁé"),
        OK("correct"),
        REWRITE("à réécrire"),
        BROKEN("cassé")
    }

    /**
     * Lit un fichier .vstar existant.
     * Utilise VStarReader pour deǹcoder les tokens binaires,
     * puis les convertit en StrokeRecords.
     */
    fun load(): Boolean {
        return try {
            if (!file.exists()) {
                Log.w(TAG, "Fichier introuvable: ${file.absolutePath}")
                return false
            }

            val reader = VStarReader(file)
            val session = reader.read() ?: return false

            header = session.header
            headerJson = session.header.toString(2)

            // Extraire les meta-donnees
            resolution = header?.optString("resolution", "1860×2480") ?: "1860×2480"
            deviceName = header?.optString("device", "") ?: ""
            createdAt = header?.optString("created_at", "") ?: ""
            unitFactor = parseUnitFactor(header ?: JSONObject())

            // Verifier la presence d'une origine
            if (header?.has("origin_x") == true || header?.has("origin_y") == true) {
                hasOrigin = true
            }

            // Convertir les tokens en strokes
            val newStrokes = tokensToStrokes(session.tokens, unitFactor)
            strokes.clear()
            strokes.addAll(newStrokes)

            tokenCount = session.tokens.size
            isDirty = false

            Log.i(TAG, "Chargeǹ: ${file.name} → ${strokes.size} strokes, ${tokenCount} tokens")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur chargement: ${e.message}")
            false
        }
    }

    // ----- Manipulation des strokes -----------------------------------------

    fun addStroke(stroke: StrokeRecord) {
        strokes.add(stroke)
        isDirty = true
    }

    fun removeStroke(index: Int): Boolean {
        if (index < 0 || index >= strokes.size) return false
        strokes.removeAt(index)
        isDirty = true
        return true
    }

    fun insertStroke(index: Int, stroke: StrokeRecord): Boolean {
        if (index < 0 || index > strokes.size) return false
        strokes.add(index, stroke)
        isDirty = true
        return true
    }

    fun deleteStrokes(indices: List<Int>): Int {
        val sorted = indices.distinct().sortedDescending()
        var count = 0
        for (idx in sorted) {
            if (idx >= 0 && idx < strokes.size) {
                strokes.removeAt(idx)
                count++
            }
        }
        if (count > 0) isDirty = true
        return count
    }

    fun translateAll(dx: Float, dy: Float) {
        for (stroke in strokes) {
            stroke.translate(dx, dy)
        }
        // Propager l'offset
        newOriginX = (newOriginX ?: 0f) + dx
        newOriginY = (newOriginY ?: 0f) + dy
        isDirty = true
    }

    // ----- Sauvegarde -------------------------------------------------------

    fun save(groups: List<List<Int>> = emptyList()): Boolean {
        val tokens = strokesToTokens(groups)
        writeTokens(tokens)
        tokenCount = tokens.size
        isDirty = false
        return true
    }

    fun saveAs(destFile: File, groups: List<List<Int>> = emptyList()): Boolean {
        val tokens = strokesToTokens(groups)
        writeTokensToFile(destFile, tokens)
        newOriginX = null
        newOriginY = null
        tokenCount = tokens.size
        isDirty = false
        return true
    }

    // ----- Utilitaires ------------------------------------------------------

    fun estimatedDurationMs(): Long {
        if (strokes.isEmpty()) return 0L
        var totalMs = 0L
        for (stroke in strokes) {
            if (stroke.timestamps.isNotEmpty()) {
                totalMs += stroke.timestamps.last() - stroke.timestamps.first()
            } else {
                totalMs += 1000 // fallback 1s par stroke
            }
        }
        return totalMs
    }

    fun totalBounds(): RectF {
        if (strokes.isEmpty()) return RectF(0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var maxY = Float.MIN_VALUE
        for (stroke in strokes) {
            for ((x, y) in stroke.points) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        return RectF(minX, minY, maxX, maxY)
    }

    fun fileName(): String = file.name
    fun filePath(): String = file.absolutePath
    fun fileSize(): Long = if (file.exists()) file.length() else 0L

    fun summary(): String {
        return "${file.name} | ${strokes.size} strokes | $tokenCount tokens | ${
            if (hasOrigin) "avec orig" else "sans orig"
        }"
    }

    // ----- Conversion interne -----------------------------------------------

    private fun parseUnitFactor(obj: JSONObject): Float {
        val uf = obj.optDouble("unit_factor", -1.0)
        return if (uf > 0) uf.toFloat() else 0.01f
    }

    private fun tokensToStrokes(tokens: List<VStarToken>, uf: Float): List<StrokeRecord> {
        val result = mutableListOf<StrokeRecord>()
        var currentX = 0f
        var currentY = 0f
        var currentTime = 0L

        var pts = mutableListOf<Pair<Float, Float>>()
        var tss = mutableListOf<Long>()
        var prs = mutableListOf<Float>()

        for (token in tokens) {
            currentX += token.dx * uf
            currentY += token.dy * uf
            currentTime += token.dt.toLong()

            if (token.ps == VStarToken.PS_PENUP || token.ps == VStarToken.PS_END) {
                // Fin de stroke
                if (pts.isNotEmpty()) {
                    result.add(StrokeRecord(
                        points = pts.toMutableList(),
                        timestamps = tss.toMutableList(),
                        pressures = prs.toMutableList()
                    ))
                }
                pts = mutableListOf()
                tss = mutableListOf()
                prs = mutableListOf()

                if (token.ps == VStarToken.PS_END) break
            } else {
                pts.add(Pair(currentX, currentY))
                tss.add(currentTime)
                prs.add(token.p.toFloat() / 255f)
            }
        }

        // Dernier stroke si non fermeǹ
        if (pts.isNotEmpty()) {
            result.add(StrokeRecord(
                points = pts.toMutableList(),
                timestamps = tss.toMutableList(),
                pressures = prs.toMutableList()
            ))
        }

        return result
    }

    private fun strokesToTokens(groups: List<List<Int>>): List<VStarToken> {
        val tokens = mutableListOf<VStarToken>()

        // Si groups est vide, traiter chaque stroke indeǹpendamment
        val groupsToUse = if (groups.isEmpty()) {
            strokes.indices.map { listOf(it) }
        } else {
            groups
        }

        for (group in groupsToUse) {
            var groupX = 0f
            var groupY = 0f

            for (idx in group) {
                if (idx >= strokes.size) continue
                val stroke = strokes[idx]

                for (i in stroke.points.indices) {
                    val (px, py) = stroke.points[i]
                    val dt = (if (i > 0) stroke.timestamps[i] - stroke.timestamps[i - 1] else 0L).toInt()
                    val p = (stroke.pressures.getOrElse(i) { 0.5f } * 255).toInt().coerceIn(0, 255)

                    val dx = ((px - groupX) / saveUnitFactor).toInt()
                    val dy = ((py - groupY) / saveUnitFactor).toInt()

                    tokens.add(VStarToken(
                        dx = dx.coerceIn(-32768, 32767).toShort(),
                        dy = dy.coerceIn(-32768, 32767).toShort(),
                        dt = dt.coerceIn(-32768, 32767).toShort(),
                        p = p,
                        az = 0xFF,
                        i = 0xFF,
                        ps = VStarToken.PS_PENDOWN,
                        h = 0
                    ))

                    groupX = px
                    groupY = py
                }

                // Marquage de fin de stroke
                tokens.add(VStarToken(0, 0, 0, 0, 0xFF, 0xFF, VStarToken.PS_PENUP, 0))
            }
        }

        // Token de fin
        tokens.add(VStarToken(0, 0, 0, 0, 0xFF, 0xFF, VStarToken.PS_END, 0))

        return tokens
    }

    private fun writeTokens(tokens: List<VStarToken>) {
        writeTokensToFile(file, tokens)
    }

    private fun writeTokensToFile(dest: File, tokens: List<VStarToken>) {
        try {
            val headerStr = buildFreshHeader()
            dest.parentFile?.mkdirs()

            val fos = FileOutputStream(dest)
            val bos = BufferedOutputStream(fos)

            // Ecrire l'en-tete
            bos.write(headerStr.toByteArray(Charsets.UTF_8))
            bos.write(HEADER_MARKER.toByteArray(Charsets.UTF_8))

            // Ecrire les tokens
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            for (token in tokens) {
                token.toBytes(dos)
            }
            dos.flush()
            bos.write(baos.toByteArray())
            bos.flush()
            fos.fd.sync()
            bos.close()

            Log.i(TAG, "Sauvegarde: ${dest.name} → ${tokens.size} tokens (${headerStr.length} octets en-tete)")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sauvegarde: ${e.message}")
        }
    }

    private fun buildFreshHeader(): String {
        val now = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val obj = JSONObject()

        obj.put("version", "0.5")
        obj.put("created_at", if (createdAt.isNotEmpty()) createdAt else now)
        obj.put("modified_at", now)
        obj.put("device", if (deviceName.isNotEmpty()) deviceName else "Boox Note Air 5C")
        obj.put("resolution", resolution)
        obj.put("unit_factor", saveUnitFactor.toDouble())
        obj.put("stroke_count", strokes.size)
        obj.put("token_count", tokenCount)

        if (hasOrigin) {
            obj.put("origin_x", newOriginX?.toDouble() ?: 0.0)
            obj.put("origin_y", newOriginY?.toDouble() ?: 0.0)
        }

        return obj.toString()
    }
}
