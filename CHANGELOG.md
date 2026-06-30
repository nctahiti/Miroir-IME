# Changelog — Miroir IME

## 2026-06-30 — Session « Correction de label — insertion/suppression + cinétique »

### `14af910` — feat: correction label — insertion/suppression + cinétique robuste

**Correction de label (swipe ↑)** :
- Puces `+` (insertion) et `−` (suppression) avec hit-test rectangulaire large
- Désélection précoce du groupe original → anti-absorption parasite des strokes de correction
- `onStylusUp` : filtrage des strokes sans cible en mode correction
- `recognizeGroup` : détection groupe temporaire par `firstIdx` (au lieu de `correctionPaths.isNotEmpty()`)
- Nettoyage `inferredGroupFirstIdxs` après suppression groupe temporaire
- `correctLetterIndex` réinitialisé après correction (permet la sortie du mode)

**Cinétique** :
- `onStylusDown` : muet si correction sans cible → clic de sortie sans trait
- `onStylusPoint` : protégé par `isStylusDown` → anti trait fantôme (0,0)→clic
- TouchHelper : fonctionnement normal, le filtrage se fait dans `onStylusUp`

**Démarrage** :
- `bitmap`/`template` via `surface.post` au premier basculement capture (surface `GONE` → dimensions 0)
- `postInvalidate()` dans `exitEditMode()` → rafraîchissement vue après sortie correction

**Clavier** :
- Bouton backspace `⌫` au-dessus de `↩` retour, dans une colonne droite

## 2026-06-29 — Session « Clavier — disposition & curseur »

### `2d23feb` — fix: clavier — ponctuation injectText + retour 3 rangées + curseur markdown absolu

**Disposition du clavier** (`MiroirIME.kt`) :
- Retour ↩ : bouton vertical couvrant les 3 rangées (MATCH_PARENT en hauteur), plus étroit, à droite
- Structure : `keyboardWrapper` horizontal → `keyRows` vertical (rangées 1-2-3) + `returnBtn`

**Ponctuation** (`MiroirIME.kt`) :
- `makePunctBtn` : utilise `injectText` au lieu d'`injectMarkdown` → 1 seul caractère inséré, curseur reste après
- Avant : `injectMarkdown(".")` insérait `..` et déplaçait le curseur au début du champ

**Curseur markdown** (`MiroirIME.kt`) :
- `injectMarkdown` : positions absolues via `getTextBeforeCursor()` au lieu de `setSelection(-n)`
- Les positions négatives étaient clampées à 0 → curseur renvoyé au début du champ
- Sans sélection : curseur placé entre les balises (`**|**`)
- Avec sélection : le texte entre les balises est sélectionné

**.gitignore** : ajout des exclusions Gradle/Android (`.gradle/`, `app/build/`, `local.properties`, `*.iml`, `.idea/`)

## 2026-06-29 — Session « UxK — Cinématique »

### `932ed3c` — fix: UxK — cinématique de sélection, curseur d'insertion, labels grisés

**Curseur & insertion** (`MiroirIME.kt`) :
- `finishInsertionMode` : force la position sauvegardée avant `commitText` + sélection du texte injecté
- `injectMarkdown` : sélectionne le texte enveloppé après insertion (avec sélection préalable)

**Cinématique du blob** (`MiroirIME.kt` + `GroupManager.kt`) :
- `onDraw` : supprimé l'affichage du blob `activeBlobGroupId` (survol) — seul le `SELECTED` est visible
- `ACTION_DOWN` : détection du blob restreinte à l'interligne (`±lineSnapMarginPx`)
- `getOrCreateActiveGroup` : désélection auto du `SELECTED` quand nouveau groupe créé hors zone
- `requestTranscription` : désélectionne les autres `SELECTED` avant transition (double-SELECTED corrigé)

**Labels** (`MiroirIME.kt`) :
- `dimLabelPaint` (`Color.GRAY`, normal) pour les groupes non sélectionnés
- `labelPaint` (`Color.BLACK`, bold) pour le groupe `SELECTED`

**Paramètre `lineSnapMarginPx`** (`BlobParams.kt` + `CaptureView.kt`) :
- Nouveau paramètre persisté (défaut `30f`)
- Appliqué au hover (`updateHover`) et à la détection blob (`ACTION_DOWN`)
- N'intervient PAS dans l'absorption (le blob reste seul maître)

**Documentation** : `docs/UxK-cinematique-2026-06-29.md`

## 2026-06-28 — Session « Source unique »

### `916b266` — refactor: source unique groupLabels
**Suppression du TranscriptionWriter et du fichier `.transcription` compagnon.**

- `CaptureView.groupLabels` devient la **source unique** de vérité (firstIdx → texte)
- Suppression : `TranscriptionWriter`, `syncTranscriptionFromGroups()`, `scheduleCompanionSync()`, `reloadFromTranscription()`
- `CaptureView.groupTranscriptions` renommé → `groupLabels`, `getGroupTranscription()` → `getGroupLabel()`
- `StrokeProcessor` : suppression de la propriété `transcriptionWriter`
- `CaptureActivity` : `onValidate()`, `onWordRecognized()`, `goToPrevPage()`, `loadPage()`, `refreshAllTranscriptions()` simplifiés
- Logs ajoutés : `LABEL set`, `📋 getOrderedTranscriptions`, `✓ Validation — source unique groupLabels`

### `90f8757` — perf: finishComposingText()
**Réduction de la latence des boutons du clavier.**

- `injectText()` : ajout de `ic.finishComposingText()` avant `commitText()`
- `injectMarkdown()` : idem avant tous les appels `commitText()`
- Correctif layout : `WRAP_CONTENT` pour les boutons sans weight (Shift, TAB, Retour)

### `086d28c` — fix: injectMarkdown + nouvelle rangée
**Correction du bug d'injection markdown + repensée du clavier.**

- `injectMarkdown()` utilise `getSelectedText(0)` au lieu de `getTextBeforeCursor(1000)` + `getTextAfterCursor(1000)`
- Sans sélection : insère `****` et place le curseur au milieu via `setSelection(-len, -len)`
- Suppression du bouton « ✎ Retour écriture » (redondant avec le toggle 📝)
- Nouvelle rangée : `⇧` Shift · `⇥ TAB` · `␣ ESPACE` (étendu, weight=1) · `↩`
- `injectText()` respecte `isShiftLocked` → uppercase des caractères

---

## Architecture post-simplification

```
stroke → GroupManager → InkGroup
              │
              ▼
         firstIdx = groupe.strokeIds.first()
              │
              ▼
         MLKit → label
              │
              ▼
    ┌── groupLabels[firstIdx] = label  ← SOURCE UNIQUE
    │
    ├── buildReadingOrderText()  → commitText (app hôte)
    ├── getOrderedTranscriptions() → clic long témoin
    ├── savePage()               → .note JSON
    └── onDraw()                 → labels sur canvas
```

### Ce qui a disparu
- ❌ `TranscriptionWriter` + fichier `.transcription` compagnon
- ❌ `syncTranscriptionFromGroups()` + debounce 1s
- ❌ `groupTranscriptions` (doublon)
- ❌ `orderIndex` (compteur instable de doublons)
