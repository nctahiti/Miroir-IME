package com.parnasse.miroir

/**
 * Un trait — séquence de points entre ACTION_DOWN et ACTION_UP.
 * Structure append-only pendant la capture, immuable une fois scellée.
 *
 * Principe fondamental : aucun point n'est modifié, réordonné ou interpolé.
 * L'ordre d'insertion EST l'ordre chronologique. Pas de tri nécessaire.
 *
 * @param id          Identifiant unique du trait (UUID ou incrémental)
 * @param sessionId   Identifiant de la session de capture parente
 * @param points      Liste ordonnée chronologiquement, append-only
 * @param startNano   Timestamp du premier point (nanosecondes)
 * @param endNano     Timestamp du dernier point (nanosecondes), -1 si en cours
 * @param isSealed    true = trait terminé (ACTION_UP reçu), false = en cours
 * @param wasCanceled true = trait annulé (ACTION_CANCEL ou palm rejection)
 * @param toolParams  Snapshot des paramètres d'outil au moment du ACTION_DOWN.
 *                    Nullable dans le V3 — sera rétabli au branchement CaptureView.
 *                    Immuable — représente l'intention de l'utilisateur pour CE trait.
 */
data class InkStroke(
    val id: Long,
    val sessionId: Long,
    val points: MutableList<InkPoint> = mutableListOf(),
    val startNano: Long = 0L,
    var endNano: Long = -1L,
    var isSealed: Boolean = false,
    var wasCanceled: Boolean = false,
    val toolParams: Any? = null  // Nullable dans V3 — sera typé au branchement
) {
    /**
     * Durée du trait en nanosecondes.
     * -1 si le trait est encore en cours.
     */
    val durationNano: Long
        get() = if (endNano > 0) endNano - startNano else -1L

    /**
     * Nombre de points capturés.
     */
    val pointCount: Int
        get() = points.size

    /**
     * Append un point — seule opération d'écriture autorisée.
     * Appelé uniquement depuis le thread de capture, sans lock
     * car le service est single-threaded sur la capture.
     */
    fun appendPoint(point: InkPoint) {
        check(!isSealed) { "Cannot append to a sealed stroke [id=$id]" }
        points.add(point)
    }

    /**
     * Scelle le trait — aucun point ne peut être ajouté après.
     * Déclenche la notification aux clients abonnés.
     */
    fun seal(endTimestamp: Long, canceled: Boolean = false) {
        endNano = endTimestamp
        isSealed = true
        wasCanceled = canceled
    }
}
