#!/usr/bin/env python3
"""
Surgical cleanup — regex-based, no fragile line indices.
Each transformation is a (pattern, replacement) pair.
Only non-comment lines are matched.
"""
import re
import sys

TARGET = "app/src/main/java/com/parnasse/miroir/CaptureView.kt"

def main():
    with open(TARGET, 'r', encoding='utf-8') as f:
        text = f.read()
    
    original = text
    changes = 0
    
    def apply(desc, pattern, replacement, count=0):
        nonlocal text, changes
        new_text, n = re.subn(pattern, replacement, text, count=count)
        if n > 0:
            text = new_text
            changes += n
            print(f"  ✅ {desc}: {n} match(es)")
        else:
            print(f"  ⚠️  {desc}: 0 match — SKIPPED")
    
    print("=== Nettoyage CaptureView.kt ===\n")
    
    # ── 1. invalidateWordGroups → stub vide ──
    apply("invalidateWordGroups stub",
          r'    private fun invalidateWordGroups\(\) \{\s*\n\s*wordGroupsCache = null\s*\n\s*\}',
          r'    /** Stub — cache géré par GroupManager. */\n    private fun invalidateWordGroups() { }')
    
    # ── 2. findWordGroup → stub null ──
    apply("findWordGroup stub",
          r'    /\*\* Retourne le groupe de mots contenant.*?\*/\s*\n    private fun findWordGroup\(strokeIndex: Int\): List<Int>\? \{.*?\n    \}',
          r'    /** Stub — la sélection passe par GroupManager. */\n    private fun findWordGroup(strokeIndex: Int): List<Int>? = null',
          count=1)
    
    # ── 3. computeWordGroups → GroupManager delegation ──
    # This is a big function (~176 lines). Match from "/**" to the closing "}"
    apply("computeWordGroups → GroupManager",
          r'    /\*\*\s*\n     \* Calcule les groupes de mots.*?\n     \*/\s*\n    private fun computeWordGroups\(\): List<List<Int>> \{\s*\n.*?\n    \}\s*\n    \n    /\*\* Retourne le groupe de mots contenant',
          r'    /** Groupes de mots depuis GroupManager (remplace O(n²)). */\n    private fun computeWordGroups(): List<List<Int>> {\n        return groupManager.allGroups().map { group ->\n            group.strokeIds.mapNotNull { inkStrokeIdToRegistryIndex[it] }\n        }.filter { it.isNotEmpty() }\n    }\n\n    /** Retourne le groupe de mots contenant',
          count=1)
    
    print(f"\n📊 {changes} changements appliqués")
    
    if changes > 0:
        with open(TARGET, 'w', encoding='utf-8') as f:
            f.write(text)
        print(f"✅ Écrit: {len(text)} octets (était {len(original)})")
    else:
        print("❌ Aucun changement — fichier non modifié")

if __name__ == '__main__':
    main()
