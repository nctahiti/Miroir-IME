package com.parnasse.miroir

import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

/**
 * DatasetExporter — Récolte et recyclage des paires (geste, label).
 *
 * Deux modes :
 *   Récolter : exporte sans détruire, marque .exported (anti-doublon)
 *   Recycler  : exporte puis supprime le bloc, booste la confiance locale
 *
 * Format permissif : capture TOUTES les dimensions disponibles —
 * positions, pressions, timestamps, azimuth, tilt. L'anonymisation
 * (reset des deltas) se fait à l'export, pas au stockage.
 *
 * Licence du dataset exporté : ODbL (Open Database License)
 */

// ── Format permissif — toutes les dimensions de la capture ──

/** Un échantillon complet : un groupe annoté avec tous ses strokes. */
data class PermissiveSample(
    val sample_id: String,           // SHA-256 du contenu
    val label: String,               // texte du label (groupe annoté)
    val char_count: Int,             // nombre de caractères
    val stroke_count: Int,           // nombre de strokes dans le groupe
    val strokes: List<PermissiveStroke>,
    val metadata: SampleMetadata     // contexte de capture
)

/** Métadonnées de l'échantillon (origine, calibration). */
data class SampleMetadata(
    val source_app: String,          // app où le bloc a été capturé
    val captured_at: String,         // timestamp ISO du bloc
    val template_spacing: Float,     // interligne utilisée
    val dpi: Float                   // résolution de l'écran
)

/** Un stroke complet — toutes les dimensions capturées. */
data class PermissiveStroke(
    val points: List<PermissivePoint>
)

/** Un point avec les dimensions disponibles à la capture. */
data class PermissivePoint(
    val x: Float,     // position absolue X (pixels)
    val y: Float,     // position absolue Y (pixels)
    val t: Long,      // timestamp absolu (ms depuis epoch)
    val p: Float      // pression [0.0, 1.0]
)

// ── Version anonymisée (pour publication) ──

/** Version anonymisée : deltas reset, pas d'absolus. */
data class AnonSample(
    val sample_id: String,
    val label: String,
    val char_count: Int,
    val stroke_count: Int,
    val strokes: List<AnonStroke>
)

data class AnonStroke(
    val points: List<AnonPoint>
)

data class AnonPoint(
    val dx: Float,   // delta X (ancre à 0)
    val dy: Float,   // delta Y
    val dt: Short,   // delta temps (reset depuis t₀)
    val p: Int       // pression [0-255]
)

// ── Exporteur ──

class DatasetExporter(private val cacheDir: File) {

    companion object {
        private const val TAG = "Miroir/DatasetExporter"
    }

    /**
     * Récolte les paires (geste, label) des blocs sélectionnés.
     *
     * @param blocks   liste des dossiers de blocs à traiter
     * @param destroy  true = Recycler (supprime après export)
     *                 false = Récolter (marque .exported, ne supprime pas)
     * @param anon     true = version anonymisée (deltas reset)
     *                 false = version permissive (toutes les dimensions)
     * @return le fichier dataset JSONL généré
     */
    fun exportBlocks(
        blocks: List<File>,
        destroy: Boolean,
        anon: Boolean = false
    ): File {
        val samples = mutableListOf<Any>()  // PermissiveSample ou AnonSample
        var totalChars = 0

        for (blockDir in blocks) {
            // Anti-doublon : sauter les blocs déjà exportés
            val exportedFlag = File(blockDir, ".exported")
            if (exportedFlag.exists()) continue

            // Métadonnées du bloc
            val name = blockDir.name
            val lastUnderscore = name.lastIndexOf('_')
            val appName = if (lastUnderscore > 0)
                name.substring(0, lastUnderscore).replace("_", ".") else "inconnu"
            val ts = if (lastUnderscore > 0)
                name.substring(lastUnderscore + 1).toLongOrNull() ?: 0L else 0L
            val capturedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(ts))

            // Lire chaque page
            val pages = blockDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("page_") }
                ?: continue

            for (pageDir in pages) {
                val groupsFile = File(pageDir, "groups.json")
                val labelsFile = File(pageDir, "labels.json")
                val stateFile = File(pageDir, "state.json")

                if (!groupsFile.exists() && !labelsFile.exists()) continue

                // Charger les groupes et labels
                val groups = loadGroupsFromJson(groupsFile)
                val labels = loadLabelsFromJson(labelsFile)

                // Pour chaque groupe annoté → un sample
                for (group in groups) {
                    val label = labels[group.firstIdx] ?: continue
                    if (label.isEmpty()) continue

                    // Extraire les strokes du groupe depuis le fichier V★ ou state.json
                    val groupStrokes = loadStrokesForGroup(pageDir, group)

                    if (anon) {
                        val anonStrokes = anonymizeStrokes(groupStrokes)
                        val hash = sha256(anonStrokes, label)
                        samples.add(AnonSample(
                            sample_id = hash,
                            label = label,
                            char_count = label.length,
                            stroke_count = anonStrokes.size,
                            strokes = anonStrokes
                        ))
                    } else {
                        val meta = SampleMetadata(
                            source_app = appName,
                            captured_at = capturedAt,
                            template_spacing = 0f,  // TODO: lire depuis state.json
                            dpi = 0f                 // TODO: lire depuis state.json
                        )
                        val hash = sha256Permissive(groupStrokes, label)
                        samples.add(PermissiveSample(
                            sample_id = hash,
                            label = label,
                            char_count = label.length,
                            stroke_count = groupStrokes.size,
                            strokes = groupStrokes.map { stroke ->
                                PermissiveStroke(
                                    points = stroke.points.indices.map { i ->
                                        val (px, py) = stroke.points[i]
                                        PermissivePoint(
                                            x = px, y = py,
                                            t = stroke.timestamps.getOrElse(i) { 0L },
                                            p = stroke.pressures.getOrElse(i) { 1.0f }
                                        )
                                    }
                                )
                            },
                            metadata = meta
                        ))
                    }
                    totalChars += label.length
                }
            }

            // Marquer ou détruire
            if (destroy) {
                blockDir.deleteRecursively()
                Log.i(TAG, "♻️ Bloc recyclé: ${blockDir.name}")
            } else {
                exportedFlag.createNewFile()
                Log.i(TAG, "🌾 Bloc récolté: ${blockDir.name}")
            }
        }

        // ── Écrire le dataset JSONL ──
        val exportDir = File(cacheDir, "exports")
        exportDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val variant = if (anon) "anon" else "full"
        val datasetFile = File(exportDir, "parnasse-dataset-$variant-$timestamp.jsonl")

        datasetFile.bufferedWriter().use { writer ->
            for (sample in samples) {
                val json = org.json.JSONObject()
                when (sample) {
                    is PermissiveSample -> {
                        json.put("sample_id", sample.sample_id)
                        json.put("label", sample.label)
                        json.put("char_count", sample.char_count)
                        json.put("stroke_count", sample.stroke_count)
                        val strokesArr = org.json.JSONArray()
                        for (s in sample.strokes) {
                            val ptsArr = org.json.JSONArray()
                            for (p in s.points) {
                                val pt = org.json.JSONObject()
                                pt.put("x", p.x.toDouble()); pt.put("y", p.y.toDouble())
                                pt.put("t", p.t); pt.put("p", p.p.toDouble())
                                ptsArr.put(pt)
                            }
                            val so = org.json.JSONObject(); so.put("points", ptsArr)
                            strokesArr.put(so)
                        }
                        json.put("strokes", strokesArr)
                        val meta = org.json.JSONObject()
                        meta.put("source_app", sample.metadata.source_app)
                        meta.put("captured_at", sample.metadata.captured_at)
                        meta.put("template_spacing", sample.metadata.template_spacing.toDouble())
                        meta.put("dpi", sample.metadata.dpi.toDouble())
                        json.put("metadata", meta)
                    }
                    is AnonSample -> {
                        json.put("sample_id", sample.sample_id)
                        json.put("label", sample.label)
                        json.put("char_count", sample.char_count)
                        json.put("stroke_count", sample.stroke_count)
                        val strokesArr = org.json.JSONArray()
                        for (s in sample.strokes) {
                            val ptsArr = org.json.JSONArray()
                            for (p in s.points) {
                                val pt = org.json.JSONObject()
                                pt.put("dx", p.dx.toDouble()); pt.put("dy", p.dy.toDouble())
                                pt.put("dt", p.dt.toInt()); pt.put("p", p.p)
                                ptsArr.put(pt)
                            }
                            val so = org.json.JSONObject(); so.put("points", ptsArr)
                            strokesArr.put(so)
                        }
                        json.put("strokes", strokesArr)
                    }
                }
                writer.write(json.toString())
                writer.newLine()
            }
        }

        // ── README ──
        val readmeFile = File(exportDir, "README-$variant-$timestamp.txt")
        readmeFile.writeText("""
Parnasse Dataset v1 — ${if (anon) "anonymisé" else "permissif (complet)"}
Licence : ODbL (Open Database License)
${samples.size} échantillons — $totalChars caractères
Hash d'intégrité par échantillon (SHA-256)
${if (anon) "Deltas réinitialisés — positions absolues absentes" else "Toutes les dimensions de capture préservées"}
Format : JSONL (un échantillon par ligne)

Pour contribuer : https://huggingface.co/datasets/nctahiti/Miroir-IME
        """.trimIndent())

        Log.i(TAG, "📦 Dataset exporté: ${datasetFile.absolutePath} — ${samples.size} échantillons")
        return datasetFile
    }

    // ── Anonymisation ──

    /**
     * Réinitialise les deltas : chaque stroke commence à (0,0, t₀=0).
     * Le geste pur, sans l'espace ni le temps.
     */
    private fun anonymizeStrokes(strokes: List<StrokeRecord>): List<AnonStroke> {
        return strokes.map { stroke ->
            val points = mutableListOf<AnonPoint>()
            var rx = 0f; var ry = 0f
            var lastTime = 0L

            for (i in stroke.points.indices) {
                val (px, py) = stroke.points[i]
                val time = stroke.timestamps.getOrElse(i) { 0L }
                val pressure = stroke.pressures.getOrElse(i) { 1.0f }

                if (i == 0) {
                    points.add(AnonPoint(0f, 0f, 0, (pressure * 255).toInt().coerceIn(0, 255)))
                    rx = px; ry = py; lastTime = time
                } else {
                    points.add(AnonPoint(
                        dx = px - rx,
                        dy = py - ry,
                        dt = (time - lastTime).toInt().coerceIn(0, 32767).toShort(),
                        p = (pressure * 255).toInt().coerceIn(0, 255)
                    ))
                    rx = px; ry = py; lastTime = time
                }
            }
            AnonStroke(points)
        }
    }

    // ── Hash d'intégrité ──

    private fun sha256(strokes: List<AnonStroke>, label: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(label.toByteArray())
        for (s in strokes) {
            for (p in s.points) {
                md.update(p.dx.toBits().toString().toByteArray())
                md.update(p.dy.toBits().toString().toByteArray())
                md.update(p.dt.toString().toByteArray())
                md.update(p.p.toString().toByteArray())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Permissive(strokes: List<StrokeRecord>, label: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(label.toByteArray())
        for (s in strokes) {
            for (i in s.points.indices) {
                val (px, py) = s.points[i]
                md.update(px.toBits().toString().toByteArray())
                md.update(py.toBits().toString().toByteArray())
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── Chargement depuis le disque ──

    /** Structure minimale pour représenter un groupe chargé depuis groups.json. */
    data class LoadedGroup(val firstIdx: Int, val strokeIndices: List<Int>)

    private fun loadGroupsFromJson(file: File): List<LoadedGroup> {
        if (!file.exists()) return emptyList()
        try {
            val json = org.json.JSONObject(file.readText())
            val arr = json.optJSONArray("groups") ?: return emptyList()
            val groups = mutableListOf<LoadedGroup>()
            for (i in 0 until arr.length()) {
                val g = arr.getJSONObject(i)
                val sids = g.optJSONArray("strokeIds") ?: continue
                val indices = (0 until sids.length()).map { sids.getInt(it) }
                if (indices.isNotEmpty()) {
                    groups.add(LoadedGroup(indices.first(), indices))
                }
            }
            return groups
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lecture groupes: ${e.message}")
            return emptyList()
        }
    }

    private fun loadLabelsFromJson(file: File): Map<Int, String> {
        if (!file.exists()) return emptyMap()
        try {
            val json = org.json.JSONObject(file.readText())
            val labelsObj = json.optJSONObject("labels") ?: return emptyMap()
            val map = mutableMapOf<Int, String>()
            for (key in labelsObj.keys()) {
                val idx = key.toIntOrNull() ?: continue
                map[idx] = labelsObj.optString(key, "")
            }
            return map
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lecture labels: ${e.message}")
            return emptyMap()
        }
    }

    /**
     * Charge les strokes d'un groupe depuis le fichier V★ de la page.
     * Utilise VStarDecoder pour reconstruire les StrokeRecord.
     */
    private fun loadStrokesForGroup(pageDir: File, group: LoadedGroup): List<StrokeRecord> {
        val vstarFile = File(pageDir, "page.vstar")
        if (!vstarFile.exists()) return emptyList()

        try {
            val decoder = VStarDecoder(vstarFile)
            val result = decoder.decode() ?: return emptyList()
            return group.strokeIndices.mapNotNull { ci ->
                result.strokes.getOrNull(ci)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erreur décodage V★ pour groupe: ${e.message}")
            return emptyList()
        }
    }
}
