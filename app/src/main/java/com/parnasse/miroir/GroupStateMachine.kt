package com.parnasse.miroir

/**
 * GroupStateMachine — règles de transition entre états de groupe.
 *
 * Garantit les invariants :
 *   - Un seul groupe SELECTED à la fois
 *   - Les transitions sont atomiques et validées
 *   - Un groupe STORED n'est pas en cache RAM
 *
 * Graphe de transitions autorisées :
 *
 *   STORED ──(survol stylet)────────→ LOADED    (chargé en cache)
 *   LOADED ──(hover maintenu >1s)───→ SELECTED  (phare actif)
 *   LOADED ──(inactivité >1s)───────→ STORED    (déchargé du cache)
 *   SELECTED ─(nouveau stroke)──────→ LOADED    (absorption terminée)
 *   SELECTED ─(inactivité >1s)──────→ STORED    (déchargé)
 *   (DELEGATED = état de session, pas de groupe)

 */
class GroupStateMachine {

    /** ID du groupe actuellement SELECTED (hover maintenu), null si aucun */
    var activeGroupId: String? = null
        private set

    /** ID du groupe LOADED en attente de sélection, null si aucun */
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
            from == GroupState.LOADED && to == GroupState.SELECTED  -> true  // timeout
            from == GroupState.LOADED && to == GroupState.STORED   -> true  // fermeture manuelle

            // Depuis PENDING
            from == GroupState.SELECTED && to == GroupState.STORED  -> true  // transcription reçue
            from == GroupState.SELECTED && to == GroupState.LOADED  -> true  // re-capture avant transcription

            // Depuis CLOSED
            from == GroupState.STORED && to == GroupState.LOADED   -> true  // survol long
            

            // Depuis EXPORTED — callback externe uniquement
            

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

        // ═══ NE PAS transitionner l'ancien groupe à STORED ═══
        // Dans l'IME, les groupes doivent rester LOADED pour que :
        //   - scheduleGroupInference() les trouve (cherche LOADED)
        //   - leur timer d'inférence puisse tirer
        //   - l'absorption reste active (transcriptionTimeoutMs = Long.MAX_VALUE)
        // Un groupe LOADED inactif sera nettoyé par evictInactive() si nécessaire,
        // mais jamais pendant la session d'écriture active.

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
            GroupState.LOADED -> {
                if (activeGroupId == group.id) activeGroupId = null
            }
            GroupState.SELECTED -> {
                if (pendingGroupId == group.id) pendingGroupId = null
            }
            else -> {}
        }

        when (to) {
            GroupState.LOADED -> {
                activeGroupId = group.id
                pendingGroupId = null  // annule tout pending pour ce groupe
            }
            GroupState.SELECTED -> {
                pendingGroupId = group.id
            }
            GroupState.STORED -> {
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
