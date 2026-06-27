# Architecture IME — Notes de référence

## DisplayController (Organe A)
- `DisplayMode.kt` — enum DU/GU/REGAL
- `EpdPort.kt` — interface testable
- `DisplayController.kt` — chef d'orchestre (entrerEcriture, reasserterDU, poserLabelPuisDU)
- `OnyxEpdPort.kt` — adaptateur SDK Onyx réel
- `DisplayControllerTest.kt` — 5 tests JVM

**Limitation SDK Onyx :** L'IME (InputMethodService) ne peut PAS contrôler le mode DU. Il hérite du mode de l'app hôte. Les appels `setDisplayScheme`, `enterScribbleMode`, `applyAppScopeUpdate/TransientUpdate` sont ignorés en contexte IME.

## GroupManager — Absorption
### Machine à états
- **LOADED** : groupe actif, peut recevoir des strokes
- **SELECTED** : groupe sélectionné par l'utilisateur (long-press)
- **STORED** : groupe archivé, peut être évincé

### Absorption (`onStrokeSealed`)
1. Teste le groupe SELECTED (`pendingGroupId`) — si le stroke intersecte ses bounds expansés → absorption
2. Teste le groupe ACTIF (`activeGroupId`) — si intersection → absorption
3. Sinon → `getOrCreateActiveGroup()` crée un nouveau groupe

### Règle importante
**Ne JAMAIS désélectionner le SELECTED** dans `getOrCreateActiveGroup()` ou `onStrokeSealed`. Le SELECTED reste SELECTED quoi qu'il arrive — c'est l'utilisateur qui le choisit. Modifié dans le commit `95a003b`.

### Synchronisation UI/GroupManager
- `activeBlobGroupId` (UI) suit le groupe visuellement actif
- `selectGroup(gid)` informe GroupManager du SELECTED
- Appelé dans le Runnable du long-press
- Réactivé dans `exitEditMode()` avec les bounds étendus

## MiroirIME.kt — États gestuels
### État A : Écriture normale (✍)
- `onStylusDown/Up/Point` — enregistrement des strokes
- `onStrokeSealed` — absorption via GroupManager
- Inférence automatique après délai (calibration)

### État B : Effacement (⌛ — montre)
**Déclenchement :** long-press sur un blob (300ms sélection → 800ms édition)
**Mécanisme :** 
- `scrubGroup(currentX)` — effacement point par point à rebours
- `dx = scrubBaseX - currentX` — delta depuis la dernière frame (NON cumulé)
- `scrubBaseX = currentX` — la base avance à chaque frame
- Parcours des strokes à rebours, suppression des points les plus récents
- Les strokes vidés sont marqués dans `erasedStrokes` (set d'index)

**Sortie :** `exitEditMode()` dans ACTION_UP (`!tapMoved`)
- Vide `currentStroke` et `currentPath`
- Supprime les strokeIds des strokes vidés du groupe
- Nettrie `groupLabels` et `inferredGroupFirstIdxs`
- Réactive le groupe comme SELECTED dans GroupManager
- Étend ses bounds au blob visuel (+ rx, ry)
- Relance l'inférence

### État C : Déplacement (↕)
- `moveGroup(endX, endY)` — translation des points du groupe
- `gestureStartX/Y` mis à jour continuellement

### Timers
- `selectionDelay` (défaut 300ms) : sélection visuelle + blob
- `editDelay` (défaut 800ms) : mode édition gestuel
- Configurables dans CalibrationActivity

### Points isolés (taps dans le vide)
- Filtrés dans `onStylusUp()` : `if (stroke.points.size < 2) return`
- Ignorés dans `rebuildBitmap()` : `if (sr.points.size < 2) continue`

## Blob et rendu
- `computeBlobPath(group)` — calcul du blob par ellipses (rx, ry paramétrables)
- `groupBlobs[gid]` — map de blobs visuels
- `groupLabels[firstIdx]` — map de labels d'inférence
- Le blob visuel est plus large que les bounds rectangulaires du groupe
- `rebuildBitmap()` — redessine tous les strokes du registre

## CalibrationActivity
- Sliders disponibles : distance spatiale X/Y, délai inférence, sélection court/long, rafraîchissement, densité blob, espacement template
- `params.spatialDistancePx` et `spatialDistanceY` utilisés par GroupManager pour l'absorption

## Commits clés
```
5169872 fix: sortie mode ERASE par tap vide
ddb90d7 feat: délai clic long réglable
2508894 fix: label supprimé avec groupe vidé
95a003b fix: absorption restaurée (éviction désactivée)
890b77d feat: États B/C — effacement, déplacement, témoin
```
