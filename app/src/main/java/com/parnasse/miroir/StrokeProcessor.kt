package com.parnasse.miroir

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

/**
 * StrokeProcessor — Inférence asynchrone sur thread background.
 *
 * Reçoit les strokes d'un groupe complété, les RASTÉRISE en bitmap
 * (contrôle visuel avant inférence), puis les envoie à ML Kit Digital Ink
 * sur un thread dédié. Le résultat est livré au thread UI via callback.
 *
 * Architecture :
 *   Capture (UI) ──► processGroup() ──► Background Thread
 *                       │                    ├─ rastérise strokes → Bitmap
 *                       │                    ├─ sauvegarde debug raster (si activé)
 *                       │                    ├─ ML Kit recognize
 *                       │                    └─ callback.onResult(text)
 *                       ◄────────────────────┘   (sur UI thread)
 *
 * Avantage : la capture n'est JAMAIS bloquée par l'inférence.
 * La reconnaissance peut prendre 50-200ms sans affecter le 60Hz du stylet.
 *
 * La rastérisation permet de contrôler visuellement ce qui est envoyé
 * à ML Kit — utile pour déboguer les erreurs de reconnaissance.
 */
class StrokeProcessor(
    private val recognizer: WordRecognizer?
) {
    companion object {
        private const val TAG = "Miroir/StrokeProcessor"
    }

    /** Executor multi-thread pour traiter les inférences en parallèle.
     *  Pool de 2 threads max — assez pour désengorger sans saturer le CPU.
     *  Chaque groupe est indépendant (snapshot) → pas de concurrence. */
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "stroke-infer").apply {
            priority = Thread.NORM_PRIORITY - 1  // priorité basse
        }
    }

    private val uiHandler = Handler(Looper.getMainLooper())

    /** Future de la dernière tâche soumise (pour annulation) */
    private var lastFuture: Future<*>? = null

    // ═══ Rastérisation de contrôle ═══

    /** Compteur de groupes pour nommer les fichiers raster */
    private val groupCounter = AtomicInteger(0)

    /** Dossier de sortie pour les bitmaps rastérisés (null = pas de sauvegarde) */
    var debugDir: File? = null

    /** Active/désactive la rastérisation de contrôle (défaut: false — perf) */
    var enableRasterDebug: Boolean = false

    /** Nombre max de rasters avant désactivation automatique (0 = illimité) */
    var maxRasterCount: Int = 0

    /** Interligne moyenne pour la normalisation (défaut 70px = distY calibration) */
    var lineHeight: Float = 70f

    /** Callback optionnel appelé sur UI thread avec le bitmap rastérisé */
    var onRasterized: ((Bitmap, groupIndex: Int) -> Unit)? = null

    /** Source unique de vérité pour le texte reconnu (écrit chaque mot dans .transcription) */
    var transcriptionWriter: TranscriptionWriter? = null

    /**
     * Traite un groupe de strokes en arrière-plan.
     *
     * @param strokes snapshot du strokeRegistry (tous les strokes)
     * @param group indices des strokes dans ce groupe
     * @param onResult callback appelé sur le UI thread avec le texte reconnu
     * @param onError callback appelé sur le UI thread en cas d'erreur
     */
    fun processGroup(
        strokes: List<StrokeRecord>,
        group: List<Int>,
        groupIndex: Int = -1,
        onResult: (String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        val rec = recognizer ?: run {
            onError?.let { uiHandler.post { it("recognizer null") } }
            return
        }
        if (!rec.isLoaded) {
            onError?.let { uiHandler.post { it("recognizer not loaded") } }
            return
        }

        // Copie défensive des strokes pour le thread background
        val strokesCopy = strokes.toList()
        val groupCopy = group.toList()
        val groupIdx = groupCounter.getAndIncrement()

        val future = executor.submit {
            try {
                // ── snapY commun pour rastérisation ET transcription ──
                val snapY = StrokeRenderer.computeSnapY(strokesCopy, groupCopy)
                // ── ÉTAPE 1 : Rastérisation de contrôle (AVANT inférence) ──
                if (enableRasterDebug) {
                    val raster = StrokeRenderer.rasterizeGroupNormalized(
                        strokesCopy, groupCopy, lineHeight, snapY
                    )
                    if (raster != null) {
                        // Sauvegarder le bitmap pour inspection
                        saveRasterForDebug(raster, groupIdx)

                        // Désactiver après N rasters pour éviter la latence disque
                        if (maxRasterCount > 0 && groupIdx >= maxRasterCount) {
                            enableRasterDebug = false
                            Log.i(TAG, "🛑 Rasters désactivés après $maxRasterCount groupes (limite atteinte)")
                        }

                        // Notifier l'UI (optionnel)
                        val cb = onRasterized
                        if (cb != null) {
                            uiHandler.post { cb(raster, groupIdx) }
                        }
                    }
                }

                // ── ÉTAPE 2 : Inférence ML Kit ──
                val text = rec.recognize(strokesCopy, groupCopy)
                if (text.isNotBlank()) {
                    // ═══ SOURCE UNIQUE HORIZON : écrire dans .transcription ═══
                    transcriptionWriter?.writeWord(snapY, text, orderIndex = groupIndex)
                    uiHandler.post { onResult(text) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur inférence: ${e.message}")
                onError?.let { uiHandler.post { it(e.message ?: "unknown") } }
            }
        }
        lastFuture = future
    }

    /**
     * Sauvegarde un bitmap rastérisé dans le dossier debug.
     * Format : raster_<timestamp>_g<groupIdx>.png
     */
    private fun saveRasterForDebug(bitmap: Bitmap, groupIdx: Int) {
        val dir = debugDir ?: return
        try {
            if (!dir.exists()) dir.mkdirs()

            val ts = SimpleDateFormat("HHmmss-SSS", Locale.US).format(Date())
            val file = File(dir, "raster_${ts}_g${groupIdx}.png")

            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }

            Log.i(TAG, "📸 Raster sauvegardé: ${file.name} (${bitmap.width}×${bitmap.height})")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sauvegarde raster: ${e.message}")
        }
    }

    /** Annule l'inférence en cours (si pas encore démarrée). */
    fun cancelPending() {
        lastFuture?.cancel(false)
    }

    /** Arrête le processeur proprement. */
    fun shutdown() {
        executor.shutdown()
    }
}
