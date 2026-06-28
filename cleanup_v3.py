#!/usr/bin/env python3
"""
Cleanup V3: precise string replacements on CaptureView.kt.
Restore from .bak2 before running.
"""
import sys

TARGET = "app/src/main/java/com/parnasse/miroir/CaptureView.kt"

def main():
    with open(TARGET, 'r', encoding='utf-8') as f:
        text = f.read()
    
    original_len = len(text)
    applied = 0
    
    def replace(desc, old, new):
        nonlocal text, applied
        if old in text:
            text = text.replace(old, new, 1)
            applied += 1
            print(f"  ✅ {desc}")
        else:
            print(f"  ❌ {desc} — NOT FOUND")
            # Show first 80 chars of old for debugging
            print(f"     old[:80]: {old[:80]}")
    
    print("=== Nettoyage chirurgical CaptureView.kt ===\n")
    
    # ── 1. invalidateWordGroups → stub ──
    replace("invalidateWordGroups stub", 
        '''    /** Invalide le cache des groupes (appeler apres ajout/suppression/deplacement) */
    private fun invalidateWordGroups() {
        wordGroupsCache = null
    }''',
        '''    /** Stub — le cache est géré par GroupManager. */
    private fun invalidateWordGroups() { }''')
    
    # ── 2. findWordGroup → stub ──
    replace("findWordGroup stub",
        '''    /** Retourne le groupe de mots contenant l'index stroke, ou null.
     *  Cherche dans les deux caches : fullGroupsCache (archivés) + wordGroupsCache (actif). */
    private fun findWordGroup(strokeIndex: Int): List<Int>? {
        // Chercher d'abord dans le groupe actif (wordGroupsCache)
        wordGroupsCache?.find { strokeIndex in it }?.let { return it }
        // Puis dans les groupes archivés (fullGroupsCache, construit incrémentalement)
        return fullGroupsCache?.find { strokeIndex in it }
    }''',
        '''    /** Stub — la sélection passe par GroupManager. */
    private fun findWordGroup(strokeIndex: Int): List<Int>? = null''')
    
    # ── 3. Remove wordGroupsCache + fullGroupsCache field declarations ──
    replace("wordGroupsCache field",
        '''    private var wordGroupsCache: List<List<Int>>? = null
    /** Cache des groupes COMPLETS (tous les strokes, ignore activeStrokeBase).
     *  Utilisé par findWordGroup() pour la sélection même après archivage. */
    private var fullGroupsCache: MutableList<List<Int>>? = null''',
        '''    // wordGroupsCache + fullGroupsCache supprimés — GroupManager gère les groupes''')
    
    # ── 4. Remove useGroupManager flag ──
    replace("useGroupManager flag",
        '''    /** Flag : utiliser GroupManager (machine à états) au lieu de checkAutoInfer */
    private var useGroupManager = true''',
        '''    // useGroupManager supprimé — GroupManager est le seul chemin''')
    
    # ── 5. Remove inferredGroupCount field ──
    replace("inferredGroupCount field",
        '''    /** Nombre de groupes deja inferes (pour eviter les doublons) */
    private var inferredGroupCount = 0''',
        '''    // inferredGroupCount supprimé — GroupManager gère l'inférence''')
    
    # ── 6. Remove activeStrokeBase field ──
    replace("activeStrokeBase field",
        '''    /** Offset dans strokeRegistry : les strokes avant cet index sont archivés (groupes figés).
     *  computeWordGroups() ne les traite plus, réduisant le calcul O(n²). */
    private var activeStrokeBase = 0''',
        '''    // activeStrokeBase supprimé — GroupManager gère l'archivage''')
    
    # ── 7. reactivatedGroupIndex → computed property ──
    replace("reactivatedGroupIndex → computed",
        '''    /** Index du groupe réactivé (-1 = aucun), pour forcer l'absorption */
    var reactivatedGroupIndex = -1
        private set''',
        '''    /** Index du groupe sélectionné (-1 = aucun). Calculé depuis GroupManager. */
    var reactivatedGroupIndex: Int
        get() {
            val sel = groupManager.groupsInState(GroupState.SELECTED).firstOrNull() ?: return -1
            return groupManager.allGroups().indexOfFirst { it.id == sel.id }
        }
        private set''')
    
    # ── 8. Clean registerCompletedStroke ──
    replace("registerCompletedStroke cleanup",
        '''    private fun registerCompletedStroke() {
        val ds = drawingStroke ?: return
        strokeRegistry.add(ds)

        // ═══ GROUPES : incrémental si réactivé, recalcul sinon ═══
        val groups: List<List<Int>>
        if (reactivatedGroupIndex >= 0 && wordGroupsCache != null) {
            // Mode réactivation : préserver le cache incrémentalement
            // (ne pas recalculer → l'absorption précédente serait perdue)
            groups = wordGroupsCache!!
        } else {
            invalidateWordGroups()
            // fullGroupsCache est construit incrémentalement — NE PAS le vider ici
            groups = computeWordGroups()
        }

        // Absorption directe si groupe réactivé
        if (reactivatedGroupIndex >= 0 && strokeRegistry.size >= 2) {
            val targetGroup = groups.getOrNull(reactivatedGroupIndex)
            val newIdx = strokeRegistry.size - 1
            Log.i(TAG, "⚡ ABSORB CHECK: reactIdx=$reactivatedGroupIndex groups=${groups.size} target=${targetGroup?.size} newIdx=$newIdx")
            if (targetGroup != null && targetGroup.isNotEmpty()) {
                // ═══ DENSITÉ D'ABSORPTION : compter les contacts proches ═══
                val distThreshold = CalibrationActivity.getSpatialDistanceX(context)
                val contactThreshold = CalibrationActivity.getAbsorbContacts(context)
                var contacts = 0
                for (tidx in targetGroup) {
                    if (tidx >= newIdx) continue
                    val st = strokeRegistry[tidx]
                    for (k1 in 0 until st.activePoints step 4) {
                        val p1x = st.points[k1].first; val p1y = st.points[k1].second
                        for (k2 in 0 until ds.activePoints step 4) {
                            val dx = p1x - ds.points[k2].first
                            val dy = p1y - ds.points[k2].second
                            val d = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                            if (d < distThreshold) {
                                contacts++
                                if (contacts >= contactThreshold) break
                            }
                        }
                        if (contacts >= contactThreshold) break
                    }
                    if (contacts >= contactThreshold) break
                }
                Log.i(TAG, "⚡ ABSORB: contacts=$contacts/${contactThreshold} distThreshold=$distThreshold")
                if (contacts >= contactThreshold) {
                    val sourceGroupIdx = groups.indexOfFirst { newIdx in it }
                    val merged = groups.mapIndexed { gi, g ->
                        when {
                            gi == reactivatedGroupIndex -> g + newIdx
                            gi == sourceGroupIdx -> g.filter { it != newIdx }
                            else -> g
                        }
                    }.filter { it.isNotEmpty() }  // ⚠️ absorber le dernier stroke d'un groupe → liste vide → crash
                    wordGroupsCache = merged
                    Log.i(TAG, "⚡ ABSORB OK: stroke #$newIdx → groupe #$reactivatedGroupIndex (retiré de #$sourceGroupIdx)")
                } else {
                    Log.i(TAG, "⚡ ABSORB FAIL: dist trop grande, fin réactivation")
                    reactivatedGroupIndex = -1
                }
            }
        }

        Log.d(TAG, "Stroke #${strokeRegistry.size}: ${ds.activePoints} pts")
        drawingStroke = null

        // ═══ GroupManager : convertir, mapper, injecter ═══
        val registryIdx = strokeRegistry.size - 1  // index 0-based
        val inkStrokeId = (registryIdx + 1).toLong()
        val inkStroke = strokeRecordToInkStroke(ds, inkStrokeId)
        inkStrokeIdToRegistryIndex[inkStrokeId] = registryIdx
        registryIndexToInkStrokeId[registryIdx] = inkStrokeId
        groupManager.onStrokeSealed(inkStroke)
        Log.d(TAG, "GroupManager: stroke #$inkStrokeId → ${groupManager.allGroups().size} groupes actifs")

        // Passer le cache courant à checkAutoInfer (fallback si GroupManager désactivé)
        if (!useGroupManager) {
            checkAutoInfer(wordGroupsCache)
        }
    }''',
        '''    private fun registerCompletedStroke() {
        val ds = drawingStroke ?: return
        strokeRegistry.add(ds)

        Log.d(TAG, "Stroke #${strokeRegistry.size}: ${ds.activePoints} pts")
        drawingStroke = null

        // ═══ GroupManager : convertir, mapper, injecter ═══
        val registryIdx = strokeRegistry.size - 1  // index 0-based
        val inkStrokeId = (registryIdx + 1).toLong()
        val inkStroke = strokeRecordToInkStroke(ds, inkStrokeId)
        inkStrokeIdToRegistryIndex[inkStrokeId] = registryIdx
        registryIndexToInkStrokeId[registryIdx] = inkStrokeId
        groupManager.onStrokeSealed(inkStroke)
        Log.d(TAG, "GroupManager: stroke #$inkStrokeId → ${groupManager.allGroups().size} groupes actifs")
    }''')
    
    # ── 9. Clean saveCurrentNote (L1214-1219) ──
    replace("saveCurrentNote cleanup",
        '''        // Sauvegarde : voir TOUS les strokes (ignore l'archivage temporaire)
        val savedBase = activeStrokeBase
        activeStrokeBase = 0
        invalidateWordGroups()
        val rawWords = computeWordGroups()
        activeStrokeBase = savedBase
        invalidateWordGroups()  // revenir à la vue archivée''',
        '''        // Groupes depuis GroupManager (ignore l'archivage)
        val rawWords = computeWordGroupsForSave()''')
    
    # ── 10. Clean recalculateWordGroups ──
    replace("recalculateWordGroups cleanup",
        '''    /** Force le recalcul complet des groupes et le rafraîchissement de l'affichage */
    fun recalculateWordGroups() {
        invalidateWordGroups()
        val groups = computeWordGroups()
        Log.i(TAG, "♻️ Recalcul groupes: ${groups.size} groupes, ${strokeRegistry.size} strokes")
        throttledInvalidate()
    }''',
        '''    /** Force le rafraîchissement de l'affichage */
    fun recalculateWordGroups() {
        Log.i(TAG, "♻️ Recalcul groupes: ${groupManager.allGroups().size} groupes, ${strokeRegistry.size} strokes")
        throttledInvalidate()
    }''')
    
    # ── 11. Clean computeWordGroupsForSave ──
    replace("computeWordGroupsForSave cleanup",
        '''    fun computeWordGroupsForSave(): List<List<Int>> {
        // Sauvegarder/restaurer activeStrokeBase pour voir tous les strokes
        val savedBase = activeStrokeBase
        val savedInferred = inferredGroupCount
        activeStrokeBase = 0
        invalidateWordGroups()
        val groups = computeWordGroups()
        val ordered = computeVisualOrder(groups) ?: groups
        // Restaurer l'état de la fenêtre active
        activeStrokeBase = savedBase
        inferredGroupCount = savedInferred
        invalidateWordGroups()
        return ordered
    }''',
        '''    /** Point d'accès pour la sauvegarde : groupes depuis GroupManager. */
    fun computeWordGroupsForSave(): List<List<Int>> {
        val groups = groupManager.allGroups().map { group ->
            group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }
        }.filter { it.isNotEmpty() }
        return computeVisualOrder(groups) ?: groups
    }''')
    
    # ── 12. Clean initReflow (L2337) ──
    replace("initReflow computeWordGroups → computeWordGroupsForSave",
        '        val words = computeWordGroups()',
        '        val words = computeWordGroupsForSave()')
    
    # ── 13. Clean decomposeGroupAt (L1978) ──
    # Replace wordGroupsCache assignments
    replace("decomposeGroupAt wordGroupsCache",
        '        wordGroupsCache = newGroups',
        '        // wordGroupsCache removed — groups from GroupManager')
    
    # ── 14. Clean loadNote (L1369-1370) ──
    replace("loadNote cache cleanup",
        '''            // Restaurer les groupes et marquer comme tous inférés
            wordGroupsCache = loadedGroups
            inferredGroupCount = loadedGroups.size''',
        '''            // Groupes chargés — GroupManager gère maintenant''')
    
    # ── 15. Clean EDIT mode reset (L163-168) ──
    replace("EDIT mode activeStrokeBase reset",
        '''            // Mode édition : réactiver tous les strokes archivés
            if (!isCapture && activeStrokeBase > 0) {
                Log.i(TAG, "Archive: reset base $activeStrokeBase → 0 (mode EDIT)")
                activeStrokeBase = 0
                invalidateWordGroups()
            }''',
        '''            // Mode édition : GroupManager gère les groupes''')
    
    # ── 16. Clean long press reactivation (L1013-1049) ──
    replace("long press reactivation stub",
        '''                    if (dt > 500 && dx < 15f && dy < 15f) {
                        longPressTriggered = true
                        val hitIdx = hitTest(event.x, event.y)
                        if (hitIdx != null) {
                            val group = findWordGroup(hitIdx)
                            if (group != null) {
                                // Chercher dans fullGroupsCache (archivés) ou wordGroupsCache (actif)
                                val allGroups = fullGroupsCache
                                val groupIndex: Int
                                val canReactivate: Boolean
                                if (allGroups != null) {
                                    groupIndex = allGroups.indexOfFirst { it == group }
                                    // Les groupes archivés sont tous inférés → réactivables
                                    canReactivate = groupIndex >= 0 && groupIndex < allGroups.size
                                } else {
                                    // Pas d'archivage : utiliser wordGroupsCache + inferredGroupCount
                                    val activeGroups = wordGroupsCache ?: return
                                    groupIndex = activeGroups.indexOfFirst { it == group }
                                    canReactivate = groupIndex >= 0 && groupIndex < inferredGroupCount
                                }
                                if (canReactivate) {
                                    Log.i(TAG, "Appui long — réactivation du groupe $groupIndex")
                                    reactivatedGroupIndex = groupIndex
                                    // Réactiver tous les strokes archivés pour permettre la correction
                                    if (activeStrokeBase > 0) {
                                        Log.i(TAG, "Archive: reset base $activeStrokeBase → 0 (réactivation)")
                                        activeStrokeBase = 0
                                        invalidateWordGroups()
                                    }
                                    // cancelAutoInferTimeout (GroupManager gere)
                                    val snapshot = strokeRegistry.toList()
                                    val seq = groupSequenceCounter.getAndIncrement()
            onWordGroupCompleted?.invoke(snapshot, group.toList(), seq)
                                    // Annuler le stroke en cours
                                    drawingStroke = null
                                    currentPath.clear()
                                    hasPrevPoint = false''',
        '''                    if (dt > 500 && dx < 15f && dy < 15f) {
                        longPressTriggered = true
                        // Toucher long — réactivation déléguée à GroupManager (hover long → selectGroup)
                        Log.d(TAG, "Toucher long — GroupManager gère la réactivation")''')
    
    # ── 17. Clean double-tap (L707-729) ──
    replace("double-tap stub",
        '''        // Double-tap : < 400ms et < 40px d'écart
        if (dt in 100..400 && dx < 40f && dy < 40f) {
            val hitIdx = hitTest(x, y) ?: return false
            val group = findWordGroup(hitIdx) ?: return false
            val allGroups = wordGroupsCache ?: computeWordGroups()
            val groupIndex = allGroups.indexOfFirst { it == group }
            if (groupIndex < 0 || groupIndex >= inferredGroupCount) return false  // déjà actif

            Log.i(TAG, "Double-tap — réactivation du groupe $groupIndex (${group.size} strokes)")
            inferredGroupCount = groupIndex
            reactivatedGroupIndex = groupIndex
            wordGroupsCache = null
            // cancelAutoInferTimeout (GroupManager gere)

            // Notifier pour relancer la reconnaissance
            val snapshot = strokeRegistry.toList()
            val seq = groupSequenceCounter.getAndIncrement()
            onWordGroupCompleted?.invoke(snapshot, group.toList(), seq)

            // Feedback visuel : faire pulser le point plus fort
            lastTapTime = 0  // reset pour éviter triple-tap
            return true
        }''',
        '''        // Double-tap : délégué à GroupManager (hover long gère la sélection)
        if (dt in 100..400 && dx < 40f && dy < 40f) {
            Log.d(TAG, "Double-tap détecté (géré par GroupManager)")
            lastTapTime = 0
            return true
        }''')
    
    # ── 18. Clean deselectAllGroups reactivatedGroupIndex (L666) ──
    replace("deselectAllGroups reactivatedGroupIndex",
        '            reactivatedGroupIndex = -1\n            Log.d(TAG, "Déselection',
        '            Log.d(TAG, "Déselection')
    
    # ── 19. Remove useGroupManager checks ──
    replace("useGroupManager check 1",
        '        if (!isBlocnoteMode || !useGroupManager) return',
        '        if (!isBlocnoteMode) return')
    
    # The second occurrence will be caught by replace_all=False automatically
    
    # ── 20. Clean clear() (L2835-2836) ──
    replace("clear() cleanup",
        '''        reactivatedGroupIndex = -1
        activeStrokeBase = 0''',
        '''        // reactivatedGroupIndex + activeStrokeBase supprimés — GroupManager gère''')
    
    # ── 21. Clean survol long reactivatedGroupIndex (L645-649) ──
    replace("survol long reactivatedGroupIndex set",
        '''                reactivatedGroupIndex = if (indices.isNotEmpty()) {
                    val groupList = indices.sorted()
                    wordGroupsCache?.indexOfFirst { it.toSet() == groupList.toSet() } ?: -1
                } else -1
                Log.i(TAG, "Survol long — groupe ${g.id} SELECTED (phare allumé, ${g.strokeCount} strokes, idx=$reactivatedGroupIndex)")''',
        '''                Log.i(TAG, "Survol long — groupe ${g.id} SELECTED (phare allumé, ${g.strokeCount} strokes)")''')
    
    # ── 22. Clean handleCaptureEvent wordGroupsCache reference (L818) ──
    replace("handleCaptureEvent wordGroupsCache",
        '                        wordGroupsCache = newOrder',
        '                        // wordGroupsCache removed — groups from GroupManager')
    
    print(f"\n{'='*60}")
    print(f"✅ {applied} changements appliqués")
    
    if applied > 0:
        with open(TARGET, 'w', encoding='utf-8') as f:
            f.write(text)
        new_len = len(text)
        print(f"📏 {original_len} → {new_len} octets (delta: {new_len - original_len:+d})")
        # Count lines
        line_count = text.count('\n') + 1
        print(f"📄 ~{line_count} lignes (était 2842)")
    else:
        print("❌ Aucun changement appliqué — fichier non modifié")

if __name__ == '__main__':
    main()
