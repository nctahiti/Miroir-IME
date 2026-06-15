#!/usr/bin/env python3
"""
Surgical cleanup of CaptureView.kt — old pipeline removal.
Applies transformations in REVERSE index order (largest first) to avoid shifts.
Run from miroir-fusion root.
"""
import sys

TARGET = "app/src/main/java/com/parnasse/miroir/CaptureView.kt"

def read_lines():
    with open(TARGET, 'r', encoding='utf-8') as f:
        return f.readlines()

def write_lines(lines):
    with open(TARGET, 'w', encoding='utf-8') as f:
        f.writelines(lines)

def apply_stub(lines, start_0, end_0, replacement):
    """Replace lines[start_0:end_0+1] with replacement (list of strings)."""
    lines[start_0:end_0+1] = replacement
    return lines

def main():
    lines = read_lines()
    original_len = len(lines)
    print(f"📄 CaptureView.kt: {original_len} lignes")

    # All changes in REVERSE order (highest index first)
    changes = []

    # === BLOCK 12: L2835-2836 clear() — remove reactivatedGroupIndex, activeStrokeBase ===
    # Before: "        reactivatedGroupIndex = -1\n        activeStrokeBase = 0\n"
    # After: remove both lines
    l2835 = 2834  # 0-indexed
    # Verify
    if 'reactivatedGroupIndex' in lines[l2835] and 'activeStrokeBase' in lines[l2835+1]:
        changes.append(('L2835-2836 clear() refs', l2835, l2835+1, []))
    else:
        print(f"  ⚠️ L2835 mismatch: {lines[l2835][:80]}")

    # === BLOCK 11: L2744 useGroupManager check in another handler ===
    # This is a `if (!isBlocnoteMode || !useGroupManager) return` — remove useGroupManager check
    # We'll handle this with a simpler approach later

    # === BLOCK 10: L2337 initReflow — replace computeWordGroups() ===
    # "val words = computeWordGroups()" → "val words = computeWordGroupsForSave()"
    l2337 = 2336
    if 'val words = computeWordGroups()' in lines[l2337]:
        old = lines[l2337]
        new_line = old.replace('computeWordGroups()', 'computeWordGroupsForSave()')
        changes.append(('L2337 initReflow', l2337, l2337, [new_line + '\n']))
    else:
        print(f"  ⚠️ L2337 mismatch: {lines[l2337][:80]}")

    # === BLOCK 9: L2199-2212 computeWordGroupsForSave — rewrite ===
    l2199_start = 2198
    l2212_end = 2211
    new_func = [
        '    /** Point d\'accès pour la sauvegarde : groupes de mots depuis GroupManager. */\n',
        '    fun computeWordGroupsForSave(): List<List<Int>> {\n',
        '        return groupManager.allGroups().map { group ->\n',
        '            group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }\n',
        '        }.filter { it.isNotEmpty() }\n',
        '    }\n',
    ]
    if 'fun computeWordGroupsForSave(): List<List<Int>>' in lines[l2199_start]:
        changes.append(('L2199-2212 computeWordGroupsForSave', l2199_start, l2212_end, new_func))
    else:
        print(f"  ⚠️ L2199 mismatch: {lines[l2199_start][:80]}")

    # === BLOCK 8: L2187-2192 findWordGroup — stub ===
    # grep confirms: 2187:    private fun findWordGroup(...) {
    l2187_0 = 2186  # 0-indexed for line 2187
    l2192_0 = 2191  # 0-indexed for line 2192 (estimate, end of small function)
    stub_find = [
        '    /** Stub — remplacé par GroupManager. Retourne null (la sélection passe par GroupManager). */\n',
        '    private fun findWordGroup(strokeIndex: Int): List<Int>? = null\n',
    ]
    if 'private fun findWordGroup(strokeIndex: Int): List<Int>?' in lines[l2187_0]:
        changes.append(('L2187-2192 findWordGroup stub', l2187_0, l2192_0, stub_find))
    else:
        print(f"  ⚠️ L2187 mismatch: {lines[l2187_0][:80]}")

    # === BLOCK 7: L2006-2182 computeWordGroups — replace with GroupManager version ===
    l2006_start = 2005
    l2182_end = 2181
    new_cwg = [
        '    /** Groupes de mots depuis GroupManager (remplace l\'ancien calcul O(n²)). */\n',
        '    private fun computeWordGroups(): List<List<Int>> {\n',
        '        return groupManager.allGroups().map { group ->\n',
        '            group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }\n',
        '        }.filter { it.isNotEmpty() }\n',
        '    }\n',
    ]
    if 'private fun computeWordGroups(): List<List<Int>>' in lines[l2006_start]:
        changes.append(('L2006-2182 computeWordGroups', l2006_start, l2182_end, new_cwg))
    else:
        print(f"  ⚠️ L2006 mismatch: {lines[l2006_start][:80]}")

    # === BLOCK 6: L1977-1999 decomposeGroupAt — adapt ===
    # Replace computeWordGroups() → computeWordGroups() (now GroupManager-based) + wordGroupsCache
    l1978 = 1977
    if 'val groups = computeWordGroups()' in lines[l1978]:
        old_d = lines[l1978]
        new_d = old_d.replace('computeWordGroups()', 'computeWordGroups()') + '  // now GroupManager-backed'
        changes.append(('L1978 decomposeGroupAt', l1978, l1978, [new_d + '\n']))
        # Also remove wordGroupsCache = newGroups at L1995
        l1995 = 1994
        if 'wordGroupsCache = newGroups' in lines[l1995]:
            changes.append(('L1995 wordGroupsCache assignment', l1995, l1995, ['        // wordGroupsCache removed — groups from GroupManager\n']))
    
    # === BLOCK 5: L1962-1967 recalculateWordGroups — simplify ===
    l1962 = 1961
    l1967 = 1966
    new_recalc = [
        '    /** Force le recalcul complet des groupes et le rafraîchissement de l\'affichage */\n',
        '    fun recalculateWordGroups() {\n',
        '        Log.i(TAG, "♻️ Recalcul groupes: ${groupManager.allGroups().size} groupes, ${strokeRegistry.size} strokes")\n',
        '        throttledInvalidate()\n',
        '    }\n',
    ]
    if 'fun recalculateWordGroups()' in lines[l1962]:
        changes.append(('L1962-1967 recalculateWordGroups', l1962, l1967, new_recalc))

    # === BLOCK 4: L1957-1959 invalidateWordGroups — stub ===
    l1957 = 1956
    l1959 = 1958
    if 'private fun invalidateWordGroups()' in lines[l1957]:
        changes.append(('L1957-1959 invalidateWordGroups', l1957, l1959, [
            '    /** Stub — le cache est géré par GroupManager. */\n',
            '    private fun invalidateWordGroups() { }\n',
        ]))

    # === BLOCK 3: L1633-1635 checkAutoInfer — already stub, keep ===
    # No changes needed

    # === BLOCK 2: L1507-1586 registerCompletedStroke — remove old absorption, keep GroupManager bridge ===
    l1507 = 1506
    l1586 = 1585
    new_reg = [
        '    private fun registerCompletedStroke() {\n',
        '        val ds = drawingStroke ?: return\n',
        '        strokeRegistry.add(ds)\n',
        '\n',
        '        Log.d(TAG, "Stroke #${strokeRegistry.size}: ${ds.activePoints} pts")\n',
        '        drawingStroke = null\n',
        '\n',
        '        // ═══ GroupManager : convertir, mapper, injecter ═══\n',
        '        val registryIdx = strokeRegistry.size - 1  // index 0-based\n',
        '        val inkStrokeId = (registryIdx + 1).toLong()\n',
        '        val inkStroke = strokeRecordToInkStroke(ds, inkStrokeId)\n',
        '        inkStrokeIdToRegistryIndex[inkStrokeId] = registryIdx\n',
        '        registryIndexToInkStrokeId[registryIdx] = inkStrokeId\n',
        '        groupManager.onStrokeSealed(inkStroke)\n',
        '        Log.d(TAG, "GroupManager: stroke #$inkStrokeId → ${groupManager.allGroups().size} groupes actifs")\n',
        '    }\n',
    ]
    if 'private fun registerCompletedStroke()' in lines[l1507]:
        changes.append(('L1507-1586 registerCompletedStroke', l1507, l1586, new_reg))
    else:
        print(f"  ⚠️ L1507 mismatch: {lines[l1507][:80]}")

    # === BLOCK 1: L1497-1505 field declarations — remove inferredGroupCount, reactivatedGroupIndex, activeStrokeBase ===
    l1497 = 1496
    l1505 = 1504
    if 'private var inferredGroupCount = 0' in lines[l1497]:
        changes.append(('L1497-1505 field decls', l1497, l1505, [
            '    /** Index du groupe sélectionné (-1 = aucun). Délégué à GroupManager.groupsInState(SELECTED). */\n',
            '    var reactivatedGroupIndex: Int\n',
            '        get() {\n',
            '            val sel = groupManager.groupsInState(GroupState.SELECTED).firstOrNull() ?: return -1\n',
            '            return groupManager.allGroups().indexOfFirst { it.id == sel.id }\n',
            '        }\n',
            '        private set\n',
        ]))
    else:
        print(f"  ⚠️ L1497 mismatch: {lines[l1497][:80]}")

    # === BLOCK 0: L1369-1370 loadNote — remove wordGroupsCache/inferredGroupCount ===
    l1369 = 1368
    l1370 = 1369
    if 'wordGroupsCache = loadedGroups' in lines[l1369]:
        changes.append(('L1369-1370 loadNote', l1369, l1370, [
            '            // Groupes chargés — GroupManager gère maintenant\n',
        ]))
    
    # === Save current note L1214-1219 — replace activeStrokeBase logic ===
    l1214 = 1213
    l1219 = 1218
    if 'val savedBase = activeStrokeBase' in lines[l1214]:
        changes.append(('L1214-1219 saveCurrentNote', l1214, l1219, [
            '        // Groupes depuis GroupManager (ignore l\'archivage)\n',
            '        val rawWords = computeWordGroupsForSave()\n',
        ]))

    # === Double-tap L707-731 — stub ===
    l707 = 706
    l731 = 730
    if 'Double-tap' in lines[l707]:
        changes.append(('L707-731 double-tap', l707, l731, [
            '        // Double-tap — délégué à GroupManager (hover long gère la sélection)\n',
            '        if (dt in 100..400 && dx < 40f && dy < 40f) {\n',
            '            Log.d(TAG, "Double-tap détecté (géré par GroupManager)")\n',
            '            lastTapTime = 0\n',
            '            return true\n',
            '        }\n',
        ]))

    # === Survol long L645-649 — simplify reactivatedGroupIndex set ===
    l645 = 644
    l649 = 648
    if 'reactivatedGroupIndex = if (indices.isNotEmpty())' in lines[l645]:
        changes.append(('L645-649 survol long', l645, l649, [
            '                // reactivatedGroupIndex est maintenant une propriété calculée depuis GroupManager\n',
            '                Log.i(TAG, "Survol long — groupe ${g.id} SELECTED (phare allumé, ${g.strokeCount} strokes)")\n',
            '                invalidate()\n',
        ]))

    # === deselectAllGroups L666 — remove reactivatedGroupIndex = -1 ===
    l666 = 665
    if 'reactivatedGroupIndex = -1' in lines[l666]:
        changes.append(('L666 deselect', l666, l666, [
            '        // reactivatedGroupIndex est calculé depuis GroupManager\n',
        ]))

    # === Toucher long L1015-1045 — stub ===
    # The long-press reactivation is now handled by GroupManager (hover long → selectGroup)
    l1015 = 1014
    l1020 = 1019
    if l1015 < len(lines) and 'val hitIdx = hitTest(event.x, event.y)' in lines[l1015]:
        # Find the end of this long-press block
        # It's inside a larger when block, so we stub just the reactivation logic
        changes.append(('L1015-1045 toucher long stub', l1015, l1045_end, [
            '                        // Toucher long — réactivation déléguée à GroupManager (hover long)\n',
            '                        Log.d(TAG, "Toucher long ignoré (GroupManager gère la réactivation)")\n',
        ]))
    else:
        print(f"  L1015 context: {lines[l1015][:100] if l1015 < len(lines) else 'OOB'}")

    # === L760 selectedWordGroup = findWordGroup ===
    l760 = 759
    if 'selectedWordGroup = findWordGroup(hitIdx)' in lines[l760]:
        changes.append(('L760 findWordGroup', l760, l760, [
            '                    // selectedWordGroup — findWordGroup est stubbé, la sélection passe par GroupManager\n',
            '                    selectedWordGroup = null\n',
        ]))

    # === L163-168 mode EDIT reset activeStrokeBase ===
    l163 = 162
    l168 = 167
    if 'if (!isCapture && activeStrokeBase > 0)' in lines[l163]:
        changes.append(('L163-168 EDIT mode', l163, l168, [
            '            // Mode édition : GroupManager gère les groupes\n',
        ]))

    # === L595 useGroupManager check ===
    l595 = 594
    if 'if (!isBlocnoteMode || !useGroupManager) return' in lines[l595]:
        changes.append(('L595 useGroupManager', l595, l595, [
            '        if (!isBlocnoteMode) return\n',
        ]))

    # === L218-221 + L233 field declarations — remove wordGroupsCache, fullGroupsCache, useGroupManager ===
    l218 = 217
    l221 = 220
    if 'private var wordGroupsCache: List<List<Int>>? = null' in lines[l218]:
        changes.append(('L218-221 cache fields', l218, l221, []))  # remove
    
    l233 = 232
    if 'private var useGroupManager = true' in lines[l233]:
        changes.append(('L233 useGroupManager flag', l233, l233, []))  # remove

    # === L1360 cleanup: groupIndices references ===
    # This is in loadNote(). We keep the stroke loading but remove cache assignment.

    # === Now sort changes by start index DESCENDING ===
    changes.sort(key=lambda c: c[1], reverse=True)
    
    print(f"\n🔧 {len(changes)} modifications à appliquer (ordre inverse):")
    for name, start, end, repl in changes:
        old_snippet = ''.join(lines[start:end+1])[:60].replace('\n', '\\n')
        new_snippet = ''.join(repl)[:60].replace('\n', '\\n') if repl else '<DELETE>'
        print(f"  {name}: L{start+1}-{end+1} → {len(repl)} lines")

    # Apply changes
    for name, start, end, repl in changes:
        lines[start:end+1] = repl

    # Write
    write_lines(lines)
    print(f"\n✅ Écrit: {len(lines)} lignes (était {original_len})")

if __name__ == '__main__':
    main()
