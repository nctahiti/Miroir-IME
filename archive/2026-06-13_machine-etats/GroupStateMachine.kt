package dev.parnasse.inkservice

/**
 * GroupStateMachine — règles de transition entre états de groupe.
 *
 * Garantit les invariants :
 *   - Un seul groupe ACTIVE à la fois
 *   - Les transitions sont atomiques et validées
 *   - Impossible de régresser de EXPORTED à CLOSED (sauf callback externe)
 *
 * Graphe de transitions autorisées :
 *
 *   ACTIVE ──(timeout/stylet levé)──→ PENDING
 *   ACTIVE ──(fermeture manuelle)───→ CLOSED
 *   PENDING ─(transcription reçue)──→ CLOSED
 *   PENDING ─(re-capture)───────────→ ACTIVE
 *   CLOSED ──(survol long)──────────→ ACTIVE   (réactivation)
 *   CLOSED ──(déplacé hors étagère)─→ EXPORTED
 *   EXPORTED ─(rappel Cœur/Flutter)─→ ACTIVE   (modification à distance)
 */
class GroupStateMachine {

    /** ID du groupe actuellement ACTIVE, null si aucun */
    var activeGroupId: String? = null
        private set

    /** ID du groupe en PENDING (transcription en attente), null si aucun */
    var pendingGroupId: String? = null
        private set

    /**
     * Vérifie si une transition est autorisée entre deux états.
     *
     * @return true si la transition est légale
     */
    fun canTransition(from: GroupState, to: GroupState, isActiveOrPending: Boolean = false): Boolean {
        return when {
            // Depuis ACTIVE
            from == GroupState.ACTIVE && to == GroupState.PENDING  -> true  // timeout
            from == GroupState.ACTIVE && to == GroupState.CLOSED   -> true  // fermeture manuelle

            // Depuis PENDING
            from == GroupState.PENDING && to == GroupState.CLOSED  -> true  // transcription reçue
            from == GroupState.PENDING && to == GroupState.ACTIVE  -> true  // re-capture avant transcription

            // Depuis CLOSED
            from == GroupState.CLOSED && to == GroupState.ACTIVE   -> true  // survol long
            from == GroupState.CLOSED && to == GroupState.EXPORTED -> true  // déplacé hors étagère

            // Depuis EXPORTED — callback externe uniquement
            from == GroupState.EXPORTED && to == GroupState.ACTIVE -> true  // demande Cœur/Flutter

            // Même état → no-op autorisé
            from == to -> true

            else -> false
        }
    }

    /**
     * Déclare le groupe comme ACTIVE.
     * L'ancien groupe ACTIVE est automatiquement fermé (→ CLOSED) si nécessaire.
     *
     * @param newActiveId  ID du groupe qui devient ACTIVE
     * @param oldGroup     Ancien groupe ACTIVE (sera transitionné → CLOSED), nullable
     * @return             true si la transition a été effectuée
     */
    fun makeActive(newActiveId: String, oldGroup: InkGroup?): Boolean {
        if (activeGroupId == newActiveId) return true  // déjà actif

        // Clôturer l'ancien groupe actif
        if (oldGroup != null && activeGroupId == oldGroup.id) {
            transition(oldGroup, GroupState.CLOSED)
        }

        activeGroupId = newActiveId
        return true
    }

    /**
     * Exécute la transition d'un groupe vers un nouvel état.
     * Met à jour les trackers internes (activeGroupId, pendingGroupId).
     *
     * @param group    Le groupe à transitionner
     * @param to       État cible
     * @return         true si la transition a été effectuée
     */
    fun transition(group: InkGroup, to: GroupState): Boolean {
        val from = group.state

        if (!canTransition(from, to)) {
            return false
        }

        // Mise à jour des trackers
        when (from) {
            GroupState.ACTIVE -> {
                if (activeGroupId == group.id) activeGroupId = null
            }
            GroupState.PENDING -> {
                if (pendingGroupId == group.id) pendingGroupId = null
            }
            else -> {}
        }

        when (to) {
            GroupState.ACTIVE -> {
                activeGroupId = group.id
                pendingGroupId = null  // annule tout pending pour ce groupe
            }
            GroupState.PENDING -> {
                pendingGroupId = group.id
            }
            GroupState.CLOSED -> {
                if (activeGroupId == group.id) activeGroupId = null
                if (pendingGroupId == group.id) pendingGroupId = null
            }
            GroupState.EXPORTED -> {
                if (activeGroupId == group.id) activeGroupId = null
                if (pendingGroupId == group.id) pendingGroupId = null
            }
        }

        group.state = to
        group.modifiedAt = System.currentTimeMillis()
        return true
    }

    /** Réinitialise la machine à états (utilisé en mode reset) */
    fun reset() {
        activeGroupId = null
        pendingGroupId = null
    }
}
