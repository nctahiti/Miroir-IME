# UxK — Cinématique de sélection et absorption (29 juin 2026)

## Architecture du flux de sélection

```
Stylet DOWN sur blob
    │
    ├─→ blob.bounds.contains(x,y)  ← zone elliptique
    │   + |y - snapToLine(blob.centerY)| < lineSnapMarginPx  ← restriction interligne (±30px)
    │
    └─→ activeBlobGroupId = gid  (détection, pas d'affichage)
         │
         └─→ Timer long-press (500ms)
              │
              ├─ si immobile → selectGroup(gid)
              │    ├─→ désélectionne tous les autres SELECTED
              │    ├─→ affiche le blob (témoin de sélection)
              │    └─→ labels : noir/gras pour SELECTED, gris/normal pour les autres
              │
              └─ si mouvement > 10px → écriture normale
                   │
                   └─→ onStrokeSealed → blob elliptique (rx,ry) = seul critère d'absorption
                        │
                        ├─ fastReject : RectF.intersects (pas de restriction interligne)
                        └─ isStrokeNearGroup : test point-contre-point elliptique
```

## Nouveaux paramètres

### BlobParams.lineSnapMarginPx
- **Défaut** : 30f
- **Rôle** : limite la zone de sélection/désignation à ±30px autour de l'interligne du groupe
- **Ne s'applique PAS à l'absorption** — le blob elliptique reste seul maître
- **Persisté** dans SharedPreferences (`line_snap_margin_px`)

## Comportements corrigés

### Double SELECTED
Deux gardes garantissent un seul groupe SELECTED :
1. `selectGroup()` : désélectionne tout autre SELECTED avant de transitionner
2. `requestTranscription()` : idem, pour l'inférence automatique
3. `getOrCreateActiveGroup()` : désélectionne le SELECTED quand un nouveau groupe est créé hors zone

### Curseur d'insertion (`finishInsertionMode`)
- Avant : `setSelection(-len, -len)` → curseur au début du textfield
- Après : `setSelection(savedPos, savedPos)` puis `commitText` puis `setSelection(savedPos, savedPos+len)` → sélectionne le texte inséré

### Sélection markdown (`injectMarkdown`)
- Avec sélection existante : enveloppe + sélectionne le texte enveloppé
- Sans sélection : insère la paire, curseur au milieu (inchangé)

### Labels grisés (`drawGroupLabels`)
- `labelPaint` : `Color.BLACK` + `Typeface.DEFAULT_BOLD` → groupe SELECTED
- `dimLabelPaint` : `Color.GRAY` + `Typeface.DEFAULT` → autres groupes

### Affichage du blob
- Supprimé : blob de `activeBlobGroupId` (survol immédiat)
- Conservé : blob du groupe `SELECTED` (apparaît après le timer)
