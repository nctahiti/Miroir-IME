# Changelog — Miroir IME

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
