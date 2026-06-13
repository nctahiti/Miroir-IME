package dev.parnasse.inkservice

import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * StrokeBuffer — buffer d'écriture asynchrone.
 *
 * Accumule les strokes en RAM et les flushe par lots vers le stockage disque,
 * plutôt que d'écrire un fichier à chaque stroke (I/O bloquant sur le thread UI).
 *
 * Stratégie :
 *   - Accumulation jusqu'à BATCH_SIZE strokes ou FLUSH_INTERVAL_MS écoulé
 *   - Flush asynchrone sur un thread worker dédié
 *   - Le stroke reste en RAM (dans la session) — le disque est une sauvegarde
 *
 * Utilisation :
 *   val buffer = StrokeBuffer(storage)
 *   buffer.append(strokeJson)  // non-bloquant
 *   buffer.flush()             // force flush (ex: extinction écran)
 */
class StrokeBuffer(
    private val storage: MiroirStrokeStorage
) {
    companion object {
        private const val TAG = "StrokeBuffer"
        private const val BATCH_SIZE = 5          // Nombre de strokes avant flush
        private const val FLUSH_INTERVAL_MS = 500L // Intervalle max entre deux flushs
    }

    private val buffer = mutableListOf<BufferedStroke>()
    private var flushScheduled = false
    private var lastFlushTime = System.currentTimeMillis()

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "StrokeBuffer-worker").apply { isDaemon = true }
    }

    /**
     * Ajoute un stroke au buffer. Non-bloquant.
     *
     * @param sessionId  ID de la session parente
     * @param strokeJson JSON du stroke (produit par MiroirStrokeCapture)
     * @param index      Index du stroke dans la session
     */
    fun append(sessionId: Long, strokeJson: JSONObject, index: Int) {
        synchronized(buffer) {
            buffer.add(BufferedStroke(sessionId, strokeJson, index))
        }

        // Déclencher flush si on a atteint le seuil
        val shouldFlush = synchronized(buffer) {
            buffer.size >= BATCH_SIZE ||
            (System.currentTimeMillis() - lastFlushTime) >= FLUSH_INTERVAL_MS
        }

        if (shouldFlush && !flushScheduled) {
            scheduleFlush()
        }
    }

    /**
     * Force un flush immédiat de tout le buffer.
     * Appelé lors d'événements critiques (extinction écran, arrêt service).
     * Bloquant — à appeler hors du thread UI.
     */
    fun flushAll() {
        val batch: List<BufferedStroke> = synchronized(buffer) {
            buffer.toList().also { buffer.clear() }
        }
        if (batch.isNotEmpty()) {
            writeBatch(batch)
        }
    }

    /**
     * Planifie un flush asynchrone. Idempotent — un seul flushScheduled à la fois.
     */
    private fun scheduleFlush() {
        flushScheduled = true
        worker.execute {
            try {
                val batch: List<BufferedStroke> = synchronized(buffer) {
                    buffer.toList().also { buffer.clear() }
                }
                if (batch.isNotEmpty()) {
                    writeBatch(batch)
                }
            } finally {
                flushScheduled = false
                lastFlushTime = System.currentTimeMillis()
            }
        }
    }

    /**
     * Écrit un lot de strokes sur le disque.
     */
    private fun writeBatch(batch: List<BufferedStroke>) {
        var written = 0
        var errors = 0

        for (item in batch) {
            try {
                val path = storage.saveStroke(item.sessionId, item.strokeJson, item.index)
                if (path != null) written++
            } catch (e: Exception) {
                errors++
                Log.w(TAG, "Erreur écriture stroke #${item.index}: ${e.message}")
            }
        }

        if (written > 0 || errors > 0) {
            Log.d(TAG, "Batch flush: $written écrits, $errors erreurs (${batch.size} strokes)")
        }
    }

    /**
     * Libère le thread worker.
     */
    fun release() {
        flushAll()
        worker.shutdown()
        try {
            worker.awaitTermination(2, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Worker shutdown interrompu")
        }
    }

    /**
     * Un stroke en attente d'écriture.
     */
    private data class BufferedStroke(
        val sessionId: Long,
        val strokeJson: JSONObject,
        val index: Int
    )
}
