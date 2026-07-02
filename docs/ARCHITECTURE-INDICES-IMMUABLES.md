# Architecture — Indices Immuables & Tombstones

> « Un stroke ne meurt jamais. Il devient tombeau. »
> — Esquisse du 2 juillet 2026

## Problème

Les strokes sont identifiés par leur **position** dans `strokeRegistry` (indices 0,1,2...).  
Les groupes référencent des **inkId** pérennes. Le mapping `inkId → index` se corrompt
quand `exitEditMode()` supprime le mapping ET remplace le stroke par un objet vide.

## Solution

### Indice immuable

L'indice d'un stroke dans `strokeRegistry` est attribué à sa création et **jamais réutilisé**.
Un stroke effacé reste à sa place, marqué `isDeleted = true`, points vidés.

### Mapping pérenne

`inkStrokeIdToRegistryIndex` n'est **jamais** modifié après création d'un stroke.
Le mapping survit à l'effacement. Les groupes peuvent toujours retrouver leurs strokes,
même supprimés (filtrés à l'affichage par `isDeleted`).

### Tombstone strict

```kotlin
// ❌ Aujourd'hui (exitEditMode)
strokeRegistry[idx] = StrokeRecord(id = "")  // remplacement → perte de l'identité
inkStrokeIdToRegistryIndex.remove(sid)        // mapping perdu → groupes orphelins

// ✅ Cible
strokeRegistry[idx].isDeleted = true          // le stroke devient tombeau 🪦
strokeRegistry[idx].points.clear()            // vidé visuellement
// inkStrokeIdToRegistryIndex INTACT          // le mapping survit
```

## Ce qui ne change pas

- **VStarEncoder** : filtre déjà `isDeleted` et `points.isEmpty()` → OK
- **VStarDecoder** : restaure les strokes avec de nouveaux inkId séquentiels → OK
- **`savePage()`** : filtre `allLiveIndices` déjà correct → OK
- **`groups.json`** : stocke les inkId → au rechargement, le mapping restauré permet de retrouver les indices

## Point d'attention : rechargement

Au rechargement, les strokes reçoivent de nouveaux inkId (1,2,3...).
`groups.json` référence les **anciens** inkId (47,48,49...).

**Solutions possibles :**
- **A)** Sauvegarder l'inkId original dans le V★ (champ par stroke)
- **B)** Utiliser l'ordre des strokes comme identité implicite (le Nème stroke = stroke #N)
  → Cohérent avec la philosophie V★ v1.0 : « l'ordre = l'identité »

## Fichiers à modifier

| Fichier | Changement |
|---------|-----------|
| `MiroirIME.kt:exitEditMode()` | `isDeleted = true` au lieu de `StrokeRecord("")` |
| `MiroirIME.kt:exitEditMode()` | Ne PAS supprimer le mapping `inkStrokeIdToRegistryIndex` |
| `MiroirIME.kt:scrubGroup()` | Marquer `isDeleted = true` au lieu de juste vider les points |
| `GroupManager.kt` | `strokeIds` filtrés par `isDeleted` à l'affichage (optionnel) |

## État actuel (2 juillet 2026, 21h)

- ✅ Bug V★ IndexOutOfBounds corrigé (timestamps vides)
- ✅ `groups.json` sauvegardé en mode V★ only
- ✅ Groupes V★Decoder ignorés (doublons)
- ⬜ Architecture indices immuables — à implémenter
