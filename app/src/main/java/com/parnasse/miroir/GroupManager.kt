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

    fun onStrokeSealed(stroke: InkStroke) {
        if (stroke.wasCanceled || stroke.points.isEmpty()) return
        val strokeBounds = BlobAbsorber.computeBounds(stroke)
        if (strokeBounds.isEmpty) return

        // Simplifie : seul le groupe SELECTED peut absorber
        // L'intention est spatiale — stylet dans le blob → ajout, sinon → nouvelle session
        val selected = machine.pendingGroupId?.let { groups[it] }
        Log.d(TAG, "onStrokeSealed: pendingGroupId=${machine.pendingGroupId}, selected=${selected?.id} state=${selected?.state}, near=${if (selected != null) isStrokeNearGroup(stroke, selected) else "N/A"}")
        val group = if (selected != null && selected.state == GroupState.SELECTED
                        && isStrokeNearGroup(stroke, selected)) {
            Log.i(TAG, "Absorption SELECTED " + selected.id + " (stroke proche)")
            selected
        } else {
            // Fermer l'ancien SELECTED avant d'ouvrir une nouvelle session
            if (selected != null) {
                machine.transition(selected, GroupState.STORED)
                onGroupAutoDeselected?.invoke()
                evictGroup(selected.id)
            }
            getOrCreateActiveGroup()
        }

        group.strokeIds.add(stroke.id)
        strokeToGroup[stroke.id] = group.id
        expandBounds(group, strokeBounds)
        group.modifiedAt = System.currentTimeMillis()
        Log.d(TAG, "Stroke #" + stroke.id + " -> groupe " + group.id + " (" + group.strokeCount + " strokes)")
        resetTranscriptionTimeout(group)
    }

    /** Test spatial simple : le stroke est-il dans le perimetre du groupe ? */
    /** Teste si le stroke est dans le blob du groupe (mêmes règles que computeSpatialGroupsRaw). */
    private fun isStrokeNearGroup(stroke: InkStroke, group: InkGroup): Boolean {
        if (group.bounds.isEmpty) return false
        val strokeBounds = BlobAbsorber.computeBounds(stroke)
        if (strokeBounds.isEmpty) return false
        // Marges calibrées sur le blob elliptique (blobRadiusX, blobRadiusY)
        // spatialDistancePx = blobDistX = calX * 0.75 (pas wordSpatial)
        val marginX = (params.spatialDistancePx * 0.7f).coerceIn(20f, 200f)
        val marginY = (params.spatialDistanceY * 0.35f).coerceIn(10f, 70f)
        val expanded = RectF(group.bounds)
        expanded.inset(-marginX, -marginY)
        return RectF.intersects(expanded, strokeBounds)
    }
    fun getOrCreateActiveGroup(): InkGroup {
        val selectedId = machine.pendingGroupId
        if (selectedId != null) {
            val selectedGroup = groups[selectedId]
            if (selectedGroup != null && selectedGroup.state == GroupState.SELECTED) {
                machine.transition(selectedGroup, GroupState.STORED)
                Log.i(TAG, "Groupe " + selectedGroup.id + " deselecte SELECTED->STORED")
                onGroupAutoDeselected?.invoke()
                evictGroup(selectedGroup.id)
            }
        }
        evictAllStored()
        val currentActiveId = machine.activeGroupId
        val oldGroup = currentActiveId?.let { groups[it] }
        val newGroup = InkGroup.create()
        groups[newGroup.id] = newGroup
        machine.makeActive(newGroup.id, oldGroup)
        Log.i(TAG, "Nouveau LOADED: " + newGroup.id + " | cache=" + groups.size)
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
                    Log.i(TAG, "Groupe " + group!!.id + " recharge depuis .groups")
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
        // La machine à états n'enforce pas cet invariant — pendingGroupId
        // est juste écrasé, l'ancien groupe garde son état SELECTED.
        groupsInState(GroupState.SELECTED).forEach { g ->
            if (g.id != groupId) {
                Log.i(TAG, "Auto-deselection: groupe " + g.id + " (double SELECTED detecte)")
                deselectGroup(g.id)
            }
        }
        val group = groups[groupId] ?: return false
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

    fun allGroups(): List<InkGroup> = groups.values.toList()
    fun groupsInState(state: GroupState): List<InkGroup> = groups.values.filter { it.state == state }
    fun cacheSize(): Int = groups.size

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
