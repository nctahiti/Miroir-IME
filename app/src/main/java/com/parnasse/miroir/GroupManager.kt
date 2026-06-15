package com.parnasse.miroir

import android.graphics.RectF
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * GroupManager — orchestrateur du groupement de strokes.
 *
 * Greffé dans le service Miroir. Reçoit chaque stroke scellé,
 * le rattache au groupe ACTIVE, et gère les transitions d'état.
 *
 * Responsabilités :
 *   - Créer/gérer les groupes de strokes (InkGroup)
 *   - Appliquer les règles de transition (GroupStateMachine)
 *   - Charger/décharger les groupes du cache (survol → LOADED)
 *   - Sélectionner après hover maintenu (→ SELECTED, phare actif)
 *   - Notifier quand un groupe est prêt (→ STORED, transcription disponible)
 *
 * NE FAIT PAS :
 *   - La transcription elle-même (déléguée au transcripteur)
 *   - La persistance des groupes (déléguée au gestionnaire de session)
 *   - Le rendu visuel du blob
 *
 * Thread safety : le stroke scellé arrive du thread UI. Les callbacks
 * de transcription sont postés sur le thread worker.
 *
 * @param onGroupTranscribed  Callback appelé quand un groupe est STORED
 *                            avec transcription → écrire dans le fichier
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
     * Appelé depuis le service Miroir après ACTION_UP.
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
                        currentActive.state == GroupState.LOADED &&
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
     * Retourne le groupe LOADED courant, ou en crée un nouveau.
     * L'ancien groupe ACTIVE est automatiquement fermé (→ CLOSED).
     */
    fun getOrCreateActiveGroup(): InkGroup {
        val currentActiveId = machine.activeGroupId

        if (currentActiveId != null) {
            val existing = groups[currentActiveId]
            if (existing != null && existing.state == GroupState.LOADED) {
                return existing
            }
        }

        // Créer un nouveau groupe
        val newGroup = InkGroup.create()
        groups[newGroup.id] = newGroup

        // Clôturer l'ancien groupe actif
        val oldGroup = currentActiveId?.let { groups[it] }
        machine.makeActive(newGroup.id, oldGroup)

        Log.i(TAG, "Nouveau groupe LOADED : ${newGroup.id} (ancien: ${oldGroup?.id ?: "aucun"})")

        return newGroup
    }

    /**
     * Active la transcription d'un groupe (passe ACTIVE → PENDING).
     * Appelé automatiquement après le timeout, ou manuellement.
     */
    fun requestTranscription(group: InkGroup): Boolean {
        if (!machine.canTransition(group.state, GroupState.SELECTED)) {
            Log.w(TAG, "Transition refusée : ${group.state} → PENDING pour groupe ${group.id}")
            return false
        }
        return machine.transition(group, GroupState.SELECTED)
    }

    /**
     * Marque la transcription comme reçue (passe PENDING → CLOSED).
     * Appelé par le transcripteur quand le texte est disponible.
     */
    fun onTranscriptionReceived(group: InkGroup, text: String, confidence: Float) {
        group.transcription = text
        group.confidence = confidence

        if (machine.transition(group, GroupState.STORED)) {
            Log.i(TAG, "Groupe ${group.id} STORED — \"${text.take(40)}${if (text.length > 40) "..." else ""}\" (confiance: $confidence)")
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

        if (group.state != GroupState.STORED && group.state != GroupState.STORED) {
            return null
        }

        if (machine.transition(group, GroupState.LOADED)) {
            Log.i(TAG, "Groupe ${group.id} chargé (STORED → LOADED)")
            // ⚠️ Ne pas relancer le timeout ici — le chargement ne compte pas
            // comme une modification. Le timeout repart uniquement quand un
            // nouveau stroke est écrit (via onStrokeSealed).
            return group
        }
        return null
    }

    /**
     * Sélectionne un groupe LOADED (passe LOADED → SELECTED).
     * Le phare s'allume. Appelé après hover maintenu > 1s.
     *
     * @param groupId  ID du groupe à sélectionner
     * @return         true si la sélection a réussi
     */
    fun selectGroup(groupId: String): Boolean {
        val group = groups[groupId] ?: return false
        if (group.state != GroupState.LOADED) return false
        if (machine.transition(group, GroupState.SELECTED)) {
            Log.i(TAG, "Groupe ${group.id} SELECTED — phare allumé (${group.strokeCount} strokes)")
            return true
        }
        return false
    }

    /**
     * Désélectionne un groupe SELECTED (SELECTED → STORED).
     * Appelé quand le stylet quitte le hover.
     *
     * @param groupId  ID du groupe à désélectionner
     * @return         true si la désélection a réussi
     */
    fun deselectGroup(groupId: String): Boolean {
        val group = groups[groupId] ?: return false
        if (group.state != GroupState.SELECTED) return false
        if (machine.transition(group, GroupState.STORED)) {
            Log.i(TAG, "Groupe ${group.id} désélectionné (SELECTED → STORED)")
            return true
        }
        return false
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

        if (group.state != GroupState.LOADED) return

        // 2s d'inactivité = le mot est fini → clôture → inférence
        machine.transition(group, GroupState.STORED)
        Log.i(TAG, "Timeout — groupe ${group.id} STORED (${group.strokeCount} strokes)")
        onGroupTranscribed(group)
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
