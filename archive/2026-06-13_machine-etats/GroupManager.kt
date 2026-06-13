package dev.parnasse.inkservice

import android.graphics.RectF
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * GroupManager — orchestrateur du groupement de strokes.
 *
 * Greffé dans ParnasseInkService. Reçoit chaque stroke scellé,
 * le rattache au groupe ACTIVE, et gère les transitions d'état.
 *
 * Responsabilités :
 *   - Créer/gérer les groupes de strokes (InkGroup)
 *   - Appliquer les règles de transition (GroupStateMachine)
 *   - Déclencher la transcription après timeout (→ PENDING)
 *   - Notifier quand un groupe est prêt (→ CLOSED, transcription disponible)
 *
 * NE FAIT PAS :
 *   - La transcription elle-même (déléguée au StrokeTranscriber)
 *   - La persistance des groupes (déléguée au MiroirSessionManager)
 *   - Le rendu visuel du blob (futur : MiroirView)
 *
 * Thread safety : le stroke scellé arrive du thread UI. Les callbacks
 * de transcription sont postés sur le thread worker.
 *
 * @param onGroupTranscribed  Callback appelé quand un groupe est CLOSED
 *                            avec transcription → pousser vers le Cœur
 */
class GroupManager(
    private val onGroupTranscribed: (InkGroup) -> Unit = {}
) {
    companion object {
        private const val TAG = "GroupManager"

        /** Timeout par défaut avant déclenchement transcription (ms) */
        const val DEFAULT_TRANSCRIPTION_TIMEOUT_MS = 2000L

        /** Timeout de survol pour réactiver un groupe fermé (ms) */
        const val LONG_HOVER_TIMEOUT_MS = 1500L
    }

    // ── État interne ──────────────────────────────────────────────────────

    private val groups = mutableMapOf<String, InkGroup>()
    private val machine = GroupStateMachine()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "GroupManager-timeout").apply { isDaemon = true }
        }

    private var timeoutFuture: ScheduledFuture<*>? = null
    private var timeoutGroupId: String? = null

    /** Paramètres du blob — mis à jour depuis la calibration */
    var params: BlobParams = BlobParams()
        set(value) {
            field = value
            absorber.updateParams(value)
        }

    /** Algorithme d'absorption — décide si un stroke rejoint le groupe actif */
    private val absorber = BlobAbsorber(params)

    /** Registre strokeId → groupId pour retrouver le groupe d'un stroke */
    private val strokeToGroup = mutableMapOf<Long, String>()

    // ── API publique ──────────────────────────────────────────────────────

    /**
     * Reçoit un stroke scellé. Point d'entrée principal.
     * Appelé depuis ParnasseInkService.onStrokeSealed().
     *
     * Utilise BlobAbsorber pour décider si le stroke rejoint le groupe
     * ACTIVE existant ou crée un nouveau groupe.
     */
    fun onStrokeSealed(stroke: InkStroke) {
        if (stroke.wasCanceled || stroke.points.isEmpty()) return

        val strokeBounds = BlobAbsorber.computeBounds(stroke)
        if (strokeBounds.isEmpty) return

        val strokeTime = stroke.startNano / 1_000_000L  // ns → ms

        // Décider : absorption ou nouveau groupe ?
        val currentActive = machine.activeGroupId?.let { groups[it] }
        val group = if (currentActive != null &&
                        currentActive.state == GroupState.ACTIVE &&
                        absorber.shouldAbsorb(currentActive, strokeBounds, strokeTime)) {
            // Absorber dans le groupe existant
            currentActive
        } else {
            // Clôturer l'ancien et créer un nouveau
            getOrCreateActiveGroup()
        }

        // Associer le stroke au groupe
        group.strokeIds.add(stroke.id)
        strokeToGroup[stroke.id] = group.id

        // Étendre la boîte englobante du groupe
        expandBounds(group, strokeBounds)

        group.modifiedAt = System.currentTimeMillis()

        Log.d(TAG, "Stroke #${stroke.id} → groupe ${group.id} (${group.strokeCount} strokes)")

        // Chaque stroke repousse le timeout de transcription
        resetTranscriptionTimeout(group)
    }

    /**
     * Retourne le groupe ACTIVE courant, ou en crée un nouveau.
     * L'ancien groupe ACTIVE est automatiquement fermé (→ CLOSED).
     */
    fun getOrCreateActiveGroup(): InkGroup {
        val currentActiveId = machine.activeGroupId

        if (currentActiveId != null) {
            val existing = groups[currentActiveId]
            if (existing != null && existing.state == GroupState.ACTIVE) {
                return existing
            }
        }

        // Créer un nouveau groupe
        val newGroup = InkGroup.create()
        groups[newGroup.id] = newGroup

        // Clôturer l'ancien groupe actif
        val oldGroup = currentActiveId?.let { groups[it] }
        machine.makeActive(newGroup.id, oldGroup)

        Log.i(TAG, "Nouveau groupe ACTIVE : ${newGroup.id} (ancien: ${oldGroup?.id ?: "aucun"})")

        return newGroup
    }

    /**
     * Active la transcription d'un groupe (passe ACTIVE → PENDING).
     * Appelé automatiquement après le timeout, ou manuellement.
     */
    fun requestTranscription(group: InkGroup): Boolean {
        if (!machine.canTransition(group.state, GroupState.PENDING)) {
            Log.w(TAG, "Transition refusée : ${group.state} → PENDING pour groupe ${group.id}")
            return false
        }
        return machine.transition(group, GroupState.PENDING)
    }

    /**
     * Marque la transcription comme reçue (passe PENDING → CLOSED).
     * Appelé par le StrokeTranscriber quand le texte est disponible.
     */
    fun onTranscriptionReceived(group: InkGroup, text: String, confidence: Float) {
        group.transcription = text
        group.confidence = confidence

        if (machine.transition(group, GroupState.CLOSED)) {
            Log.i(TAG, "Groupe ${group.id} CLOSED — \"${text.take(40)}${if (text.length > 40) "..." else ""}\" (confiance: $confidence)")
            onGroupTranscribed(group)
        }
    }

    /**
     * Réactive un groupe CLOSED (passe CLOSED → ACTIVE).
     * Déclenché par survol long d'un mot appartenant au groupe.
     *
     * @param strokeId  ID du stroke survolé
     * @return          Le groupe réactivé, ou null si impossible
     */
    fun reactivateGroup(strokeId: Long): InkGroup? {
        val groupId = strokeToGroup[strokeId] ?: return null
        val group = groups[groupId] ?: return null

        if (group.state != GroupState.CLOSED && group.state != GroupState.EXPORTED) {
            return null
        }

        if (machine.transition(group, GroupState.ACTIVE)) {
            Log.i(TAG, "Groupe ${group.id} réactivé (CLOSED → ACTIVE)")
            // Réinitialiser le timeout — l'utilisateur peut modifier
            resetTranscriptionTimeout(group)
            return group
        }
        return null
    }

    /**
     * Recherche le groupe contenant un stroke donné.
     */
    fun findGroupByStroke(strokeId: Long): InkGroup? {
        val groupId = strokeToGroup[strokeId] ?: return null
        return groups[groupId]
    }

    /**
     * Recherche un groupe par son ID.
     */
    fun getGroup(groupId: String): InkGroup? = groups[groupId]

    /** Tous les groupes gérés */
    fun allGroups(): List<InkGroup> = groups.values.toList()

    /** Groupes dans un état donné */
    fun groupsInState(state: GroupState): List<InkGroup> =
        groups.values.filter { it.state == state }

    /**
     * Libère les ressources (threads, timers).
     */
    fun release() {
        timeoutFuture?.cancel(false)
        scheduler.shutdown()
    }

    // ── Interne ───────────────────────────────────────────────────────────

    /**
     * Annule le timer de transcription existant et en démarre un nouveau.
     */
    private fun resetTranscriptionTimeout(group: InkGroup) {
        timeoutFuture?.cancel(false)
        timeoutGroupId = group.id

        timeoutFuture = scheduler.schedule({
            handleTimeout(group.id)
        }, params.transcriptionTimeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Appelé quand le timeout expire. Déclenche la transcription.
     */
    private fun handleTimeout(groupId: String) {
        val group = groups[groupId] ?: return

        if (group.state != GroupState.ACTIVE) return  // déjà transitionné

        if (requestTranscription(group)) {
            Log.d(TAG, "Timeout transcription pour groupe ${group.id} (${group.strokeCount} strokes)")

            // La transcription sera appelée par le StrokeTranscriber.
            // Pour l'instant, le groupe est en PENDING.
            // Quand le transcriber aura fini, il appellera onTranscriptionReceived().
        }
    }

    /**
     * Étend la boîte englobante du groupe avec les bounds d'un stroke.
     */
    private fun expandBounds(group: InkGroup, strokeBounds: RectF) {
        if (group.bounds.isEmpty) {
            group.bounds.set(strokeBounds)
        } else {
            group.bounds.union(strokeBounds)
        }
    }
}
