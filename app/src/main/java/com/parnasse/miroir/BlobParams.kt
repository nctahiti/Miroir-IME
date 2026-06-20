package com.parnasse.miroir

import android.content.Context
import android.content.SharedPreferences

/**
 * BlobParams — paramètres du blob d'absorption.
 *
 * Contrôlent l'algorithme de groupement des strokes (BlobAbsorber).
 * Persistés dans SharedPreferences — survivent aux redémarrages du service.
 *
 * @param spatialDistancePx  Distance spatiale max pour fusionner deux strokes
 * @param minOverlapPercent  % de chevauchement horizontal minimum requis
 * @param temporalDistanceMs Distance temporelle max entre deux strokes
 * @param transcriptionTimeoutMs Délai avant déclenchement transcription
 * @param groupLevel         Niveau de groupement (WORD/LINE/PARAGRAPH)
 * @param captureAnchor      Ancrage de la zone de capture (BOTTOM/TOP)
 */
data class BlobParams(
    val spatialDistancePx: Float = DEFAULT_SPATIAL_DISTANCE_PX,
    val minOverlapPercent: Int = DEFAULT_MIN_OVERLAP_PERCENT,
    val temporalDistanceMs: Long = DEFAULT_TEMPORAL_DISTANCE_MS,
    val transcriptionTimeoutMs: Long = DEFAULT_TRANSCRIPTION_TIMEOUT_MS,
    val groupLevel: GroupLevel = GroupLevel.WORD,
    val captureAnchor: CaptureAnchor = CaptureAnchor.BOTTOM
) {
    companion object {
        // Valeurs par défaut — calibrées pour écriture latine standard
        const val DEFAULT_SPATIAL_DISTANCE_PX    = 20f  // écart entre lettres ~20px, entre mots >40px
        const val DEFAULT_MIN_OVERLAP_PERCENT     = 30
        const val DEFAULT_TEMPORAL_DISTANCE_MS    = 800L
        const val DEFAULT_TRANSCRIPTION_TIMEOUT_MS = 2000L

        // Limites des curseurs
        const val MIN_SPATIAL_PX  = 2f
        const val MAX_SPATIAL_PX  = 80f
        const val MIN_OVERLAP     = 0
        const val MAX_OVERLAP     = 100
        const val MIN_TEMPORAL_MS = 100L
        const val MAX_TEMPORAL_MS = 3000L
        const val MIN_TIMEOUT_MS  = 500L
        const val MAX_TIMEOUT_MS  = 5000L

        /** Charge les paramètres depuis SharedPreferences */
        fun load(prefs: SharedPreferences): BlobParams = BlobParams(
            spatialDistancePx       = prefs.getFloat(KEY_SPATIAL, DEFAULT_SPATIAL_DISTANCE_PX),
            minOverlapPercent       = prefs.getInt(KEY_OVERLAP, DEFAULT_MIN_OVERLAP_PERCENT),
            temporalDistanceMs      = prefs.getLong(KEY_TEMPORAL, DEFAULT_TEMPORAL_DISTANCE_MS),
            transcriptionTimeoutMs  = prefs.getLong(KEY_TIMEOUT, DEFAULT_TRANSCRIPTION_TIMEOUT_MS),
            groupLevel              = GroupLevel.valueOf(
                prefs.getString(KEY_LEVEL, GroupLevel.WORD.name) ?: GroupLevel.WORD.name
            ),
            captureAnchor           = CaptureAnchor.valueOf(
                prefs.getString(KEY_ANCHOR, CaptureAnchor.BOTTOM.name) ?: CaptureAnchor.BOTTOM.name
            )
        )

        /** Sauvegarde les paramètres dans SharedPreferences */
        fun save(prefs: SharedPreferences, params: BlobParams) {
            prefs.edit()
                .putFloat(KEY_SPATIAL, params.spatialDistancePx)
                .putInt(KEY_OVERLAP, params.minOverlapPercent)
                .putLong(KEY_TEMPORAL, params.temporalDistanceMs)
                .putLong(KEY_TIMEOUT, params.transcriptionTimeoutMs)
                .putString(KEY_LEVEL, params.groupLevel.name)
                .putString(KEY_ANCHOR, params.captureAnchor.name)
                .apply()
        }

        // Clés SharedPreferences
        private const val PREFS_NAME = "miroir_blob_params"
        private const val KEY_SPATIAL  = "spatial_distance_px"
        private const val KEY_OVERLAP  = "min_overlap_pct"
        private const val KEY_TEMPORAL = "temporal_distance_ms"
        private const val KEY_TIMEOUT  = "transcription_timeout_ms"
        private const val KEY_LEVEL    = "group_level"
        private const val KEY_ANCHOR   = "capture_anchor"

        /** Obtient les SharedPreferences nommées */
        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * Ancrage de la zone de capture.
 *
 * BOTTOM — zone en bas de l'écran (défaut, majorité des scripteurs)
 * TOP    — zone en haut (gauchers, style alternatif)
 */
enum class CaptureAnchor {
    BOTTOM,
    TOP
}
