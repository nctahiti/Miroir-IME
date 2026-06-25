package com.parnasse.miroir

import android.graphics.RectF
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class GroupManager(
    private val onGroupTranscribed: (InkGroup) -> Unit = {},
    var onGroupAutoDeselected: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "GroupManager"
        const val DEFAULT_TRANSCRIPTION_TIMEOUT_MS = 2000L
        const val LONG_HOVER_TIMEOUT_MS = 1500L
    }

    private val groups = mutableMapOf<String, InkGroup>()
    private val machine = GroupStateMachine()
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "GroupManager-timeout").apply { isDaemon = true }
        }

    /** Compteur de séquence — assigne un orderIndex unique à chaque groupe créé.
     *  Initialisé à 0 ; après chargement d'une page, appeler initSequenceCounter(max). */
    var nextOrderIndex: Int = 0
        private set

    /** Initialise le compteur après chargement d'une page
     *  pour éviter les collisions avec les orderIndex existants. */
    fun initSequenceCounter(maxExisting: Int) {
        nextOrderIndex = maxExisting + 1
        Log.d(TAG, "SequenceCounter initialisé à $nextOrderIndex (max existant: $maxExisting)")
    }

    private var timeoutFuture: ScheduledFuture<*>? = null
    private var timeoutGroupId: String? = null

    var params: BlobParams = BlobParams()
        set(value) {
            field = value
            absorber.updateParams(value)
        }

    private val absorber = BlobAbsorber(params)
    private val strokeToGroup = mutableMapOf<Long, String>()
    var persistence: GroupPersistence? = null

    /**
     * Résout un strokeId → liste de points (x, y).
     * Branché par CaptureView pour que isStrokeNearGroup puisse tester
     * point-contre-point sans passer par le rectangle englobant.
     */
    var pointProvider: ((Long) -> List<Pair<Float, Float>>?)? = null

    fun onStrokeSealed(stroke: InkStroke) {
        if (stroke.wasCanceled || stroke.points.isEmpty()) return
        val strokeBounds = BlobAbsorber.computeBounds(stroke)
        if (strokeBounds.isEmpty) return

        // ═══ 1. Priorité au groupe SELECTED ═══
        val selected = machine.pendingGroupId?.let { groups[it] }
        val nearSelected = selected != null && selected.state == GroupState.SELECTED && run {
            val expanded = RectF(selected.bounds)
            expanded.inset(-params.spatialDistancePx, -params.spatialDistanceY)
            RectF.intersects(expanded, strokeBounds)
        }
        Log.i(TAG, "onStrokeSealed: pendingGroupId=${machine.pendingGroupId}, selected=${selected?.id} state=${selected?.state} strokes=${selected?.strokeCount}, bounds=${selected?.bounds?.toShortString()}, fastReject=${if (selected != null) nearSelected else "N/A"}, params(rx=${params.spatialDistancePx}, ry=${params.spatialDistanceY})")
        
        val group = if (nearSelected && isStrokeNearGroup(stroke, selected!!)) {
            Log.i(TAG, "Absorption SELECTED " + selected.id + " (stroke proche)")
            selected
        } else {
            // ═══ 2. Blob contre le groupe ACTIF (celui qui reçoit les strokes en cours) ═══
            // Ne pas tester TOUS les LOADED — sinon les strokes du mot 2 sont absorbés par le mot 1.
            val activeId = machine.activeGroupId
            val active = if (activeId != null && activeId != selected?.id) groups[activeId] else null
            val nearActive = active != null && active.state == GroupState.LOADED && run {
                val expanded = RectF(active.bounds)
                expanded.inset(-params.spatialDistancePx, -params.spatialDistanceY)
                RectF.intersects(expanded, strokeBounds)
            }
            if (nearActive && isStrokeNearGroup(stroke, active!!)) {
                Log.i(TAG, "Absorption ACTIVE " + active.id + " (stroke proche)")
                active
            } else {
                // ═══ 3. Aucun blob ne touche → nouveau groupe (le SELECTED reste intact si présent) ═══
                getOrCreateActiveGroup()
            }
        }

        group.strokeIds.add(stroke.id)
        strokeToGroup[stroke.id] = group.id
        expandBounds(group, strokeBounds)
        group.modifiedAt = System.currentTimeMillis()
        Log.d(TAG, "Stroke #" + stroke.id + " -> groupe " + group.id + " (" + group.strokeCount + " strokes)")
        resetTranscriptionTimeout(group)
    }

    /**
     * Teste si le stroke touche le blob du groupe.
     * Blob = union des ellipses (rx, ry) centrées sur chaque point du groupe.
     * Le bouncing box (fast-reject dans onStrokeSealed) filtre avant — 
     * ici on fait le vrai test point-contre-point.
     */
    private fun isStrokeNearGroup(stroke: InkStroke, group: InkGroup): Boolean {
        if (stroke.points.isEmpty()) return false
        val provider = pointProvider ?: return false
        if (group.bounds.isEmpty) return false

        val rx = params.spatialDistancePx
        val ry = params.spatialDistanceY

        // Collecter les points du groupe (via le provider branché sur strokeRegistry)
        val groupPoints = mutableListOf<Pair<Float, Float>>()
        for (sid in group.strokeIds) {
            provider(sid)?.let { groupPoints.addAll(it) }
        }
        if (groupPoints.isEmpty()) {
            Log.w(TAG, "isStrokeNearGroup: 0 groupPoints pour ${group.strokeIds.size} strokeIds")
            return false
        }

        // Test point-contre-point : chaque point du stroke contre chaque point du groupe
        for (sp in stroke.points) {
            for (gp in groupPoints) {
                val dx = (sp.x - gp.first) / rx
                val dy = (sp.y - gp.second) / ry
                if (dx * dx + dy * dy <= 1.0f) return true  // dans l'ellipse
            }
        }
        return false
    }
    fun getOrCreateActiveGroup(): InkGroup {
        val selectedId = machine.pendingGroupId
        // ═══ Si un groupe est SELECTED, NE PAS le désélectionner (l'utilisateur l'a choisi) ═══
        // Le nouveau stroke non-absorbé crée un nouveau groupe, mais le SELECTED reste.
        if (selectedId != null) {
            val selectedGroup = groups[selectedId]
            if (selectedGroup != null && selectedGroup.state == GroupState.SELECTED) {
                Log.i(TAG, "SELECTED present (${selectedId.take(8)}), pas de deselec automatique")
            }
        }
        evictAllStored()
        val currentActiveId = machine.activeGroupId
        val oldGroup = currentActiveId?.let { groups[it] }
        val newGroup = InkGroup.create()
        // ═══ Assigner orderIndex à la CRÉATION (pas à la fermeture) ═══
        // Principe : « La permissivité est le meilleur gage de stabilité. »
        // Un groupe existe dès sa création avec son numéro d'ordre.
        newGroup.orderIndex = nextOrderIndex++
        groups[newGroup.id] = newGroup
        machine.makeActive(newGroup.id, oldGroup)
        Log.i(TAG, "Nouveau LOADED: " + newGroup.id + " | seq=${newGroup.orderIndex} | cache=" + groups.size)
        return newGroup
    }

    fun requestTranscription(group: InkGroup): Boolean {
        if (!machine.canTransition(group.state, GroupState.SELECTED)) {
            Log.w(TAG, "Transition refusee: " + group.state + " -> PENDING")
            return false
        }
        return machine.transition(group, GroupState.SELECTED)
    }

    fun onTranscriptionReceived(group: InkGroup, text: String, confidence: Float) {
        group.transcription = text
        group.confidence = confidence
        if (machine.transition(group, GroupState.STORED)) {
            Log.i(TAG, "Groupe " + group.id + " STORED: \"" + text.take(40) + "\"")
            onGroupTranscribed(group)
            evictGroup(group.id)
        }
    }

    fun reactivateGroup(strokeId: Long): InkGroup? {
        val groupId = strokeToGroup[strokeId] ?: return null
        var group = groups[groupId]
        if (group == null) {
            val p = persistence
            if (p != null && p.exists()) {
                group = p.readGroup(groupId)
                if (group != null) {
                    groups[groupId] = group
                    Log.i(TAG, "Groupe " + group!!.id + " recharge depuis .groups (${group!!.strokeIds.size} strokeIds: ${group!!.strokeIds.take(3).joinToString(",")}...)")
                }
            }
        }
        if (group == null) return null
        if (group.state != GroupState.STORED && group.state != GroupState.LOADED) return null
        if (machine.transition(group, GroupState.LOADED)) {
            Log.i(TAG, "Groupe " + group.id + " -> LOADED")
            return group
        }
        return null
    }

    fun selectGroup(groupId: String): Boolean {
        // ═══ Garantir UN SEUL groupe SELECTED à la fois ═══
        groupsInState(GroupState.SELECTED).forEach { g ->
            if (g.id != groupId) {
                Log.i(TAG, "Auto-deselection: groupe " + g.id + " (double SELECTED detecte)")
                deselectGroup(g.id)
            }
        }
        var group = groups[groupId]
        // ═══ Groupe STORD ou évincé → recharger depuis la persistence ═══
        if (group == null || group.state == GroupState.STORED) {
            if (group == null) {
                persistence?.readGroup(groupId)?.let { loaded ->
                    groups[groupId] = loaded
                    group = loaded
                    Log.i(TAG, "Groupe " + groupId + " rechargé depuis .groups (${loaded.strokeCount} strokes)")
                }
            }
            if (group != null && group.state == GroupState.STORED) {
                machine.transition(group, GroupState.LOADED)
                Log.i(TAG, "Groupe " + group!!.id + " STORED->LOADED (réactivation)")
            }
        }
        if (group == null) return false
        if (group.state != GroupState.LOADED) return false
        if (machine.transition(group, GroupState.SELECTED)) {
            Log.i(TAG, "Groupe " + group.id + " SELECTED (" + group.strokeCount + " strokes)")
            return true
        }
        return false
    }

    fun deselectGroup(groupId: String): Boolean {
        val group = groups[groupId] ?: return false
        if (group.state != GroupState.SELECTED) return false
        if (machine.transition(group, GroupState.STORED)) {
            Log.i(TAG, "Groupe " + group.id + " SELECTED->STORED")
            onGroupAutoDeselected?.invoke()
            evictGroup(group.id)
            return true
        }
        return false
    }

    fun findGroupByStroke(strokeId: Long): InkGroup? {
        val groupId = strokeToGroup[strokeId] ?: return null
        groups[groupId]?.let { return it }
        return persistence?.readGroup(groupId)
    }

    fun getGroup(groupId: String): InkGroup? {
        groups[groupId]?.let { return it }
        return persistence?.readGroup(groupId)
    }

    /** Enregistre un groupe charge depuis une note (loadNoteFile).
     *  Remplit strokeToGroup pour que le survol long fonctionne. */
    fun registerLoadedGroup(group: InkGroup) {
        groups[group.id] = group
        for (sid in group.strokeIds) {
            strokeToGroup[sid] = group.id
        }
        machine.transition(group, GroupState.STORED)
        persistence?.writeGroup(group)
        Log.d(TAG, "Groupe charge: " + group.id + " (" + group.strokeCount + " strokes)")
    }

    /** Synchronise les strokeIds d'un groupe avec la liste fournie (groupe spatial).
     *  Met à jour strokeToGroup, les bounds, et persiste. */
    fun syncStrokeIds(groupId: String, strokeIds: List<Long>) {
        val group = groups[groupId] ?: return
        val oldCount = group.strokeIds.size
        group.strokeIds.clear()
        group.strokeIds.addAll(strokeIds)
        for (sid in strokeIds) {
            strokeToGroup[sid] = groupId
        }
        // ═══ Recalculer les bounds à partir de TOUS les strokeIds ═══
        val provider = pointProvider
        if (provider != null) {
            group.bounds.setEmpty()
            for (sid in strokeIds) {
                val pts = provider(sid) ?: continue
                for ((px, py) in pts) {
                    if (group.bounds.isEmpty) {
                        group.bounds.set(px, py, px, py)
                    } else {
                        group.bounds.union(px, py)
                    }
                }
            }
        }
        Log.i(TAG, "Groupe " + groupId + " strokeIds synchronises: $oldCount → ${strokeIds.size}, bounds=${group.bounds.toShortString()}")
    }

    fun allGroups(): List<InkGroup> = groups.values.toList()
    
    /** Tous les groupes (cache + persistance). Pour getSpatialGroupsFromGM. */
    fun allGroupsFull(): List<InkGroup> {
        val cached = groups.values.toList()
        val cachedIds = cached.map { it.id }.toSet()
        val persisted = persistence?.readAllGroups()?.filter { it.id !in cachedIds } ?: emptyList()
        return cached + persisted
    }
    
    fun groupsInState(state: GroupState): List<InkGroup> = groups.values.filter { it.state == state }
    fun cacheSize(): Int = groups.size

    /** Vide tous les groupes (cache + machine à états). Appelé au clear(). */
    fun clearAll() {
        groups.clear()
        strokeToGroup.clear()
        machine.reset()
        timeoutFuture?.cancel(false)
        timeoutGroupId = null
        Log.i(TAG, "GroupManager: tous les groupes vidés")
    }

    fun release() {
        timeoutFuture?.cancel(false)
        scheduler.shutdown()
    }

    private fun evictGroup(groupId: String) {
        val group = groups[groupId] ?: return
        if (group.state != GroupState.STORED) return
        persistence?.writeGroup(group)
        groups.remove(groupId)
        Log.d(TAG, "Evince: " + groupId + " -> .groups | cache=" + groups.size)
    }

    /** Supprime definitivement un groupe (quel que soit son etat). */
    fun removeGroup(groupId: String) {
        groups.remove(groupId)
    }

    private fun evictAllStored() {
        val storedIds = groups.filter { it.value.state == GroupState.STORED }.keys.toList()
        for (id in storedIds) { evictGroup(id) }
        if (storedIds.isNotEmpty()) Log.i(TAG, "Eviction massive: " + storedIds.size + " groupes")
    }

    /** Évince tous les groupes STORED + les LOADED qui ne sont pas le groupe actif.
     *  Appelé après chaque stroke pour garder le cache propre. */
    fun evictInactive() {
        evictAllStored()
        val activeId = machine.activeGroupId
        val toEvict = groups.filter { (id, g) ->
            g.state == GroupState.LOADED && id != activeId
        }.keys.toList()
        for (id in toEvict) {
            val g = groups[id]!!
            machine.transition(g, GroupState.STORED)
            evictGroup(id)
            Log.d(TAG, "Eviction LOADED inactif: " + id + " -> .groups")
        }
        if (toEvict.isNotEmpty()) Log.i(TAG, "Eviction LOADED inactifs: " + toEvict.size + " groupes")
    }

    private fun resetTranscriptionTimeout(group: InkGroup) {
        timeoutFuture?.cancel(false)
        timeoutGroupId = group.id
        timeoutFuture = scheduler.schedule({ handleTimeout(group.id) }, params.transcriptionTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun handleTimeout(groupId: String) {
        val group = groups[groupId] ?: return
        if (group.state != GroupState.LOADED) return
        machine.transition(group, GroupState.STORED)
        Log.i(TAG, "Timeout - groupe " + group.id + " STORED (" + group.strokeCount + " strokes)")
        onGroupTranscribed(group)
        evictGroup(groupId)
    }

    private fun expandBounds(group: InkGroup, strokeBounds: RectF) {
        if (group.bounds.isEmpty) group.bounds.set(strokeBounds)
        else group.bounds.union(strokeBounds)
    }
}
