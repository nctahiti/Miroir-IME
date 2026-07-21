# Changelog — Miroir IME

## 2026-07-21 — Session « Fontaine & UxK standalone » (38 commits)

### Cache & Performance
- **Archivage des strokes** (`StrokeRecord.isArchived`) : strokes des groupes STORED ignorés au redraw
- **`evictInactive()`** appelé dans `refreshDisplay()`, `savePageFull()`, `savePage()` IME
- **`redrawBitmapInternal(fullRedraw)`** : mode incrémental (préserve bitmap, efface supprimés) vs full (chargement)
- **`isAntiAlias=false`** sur tous les paints de redraw (EPD)
- **Garde-fou `modeInteraction`** : callbacks fontaine skippent `keepRawDrawingActive()` pendant le drag

### Blobs & Rendu
- Blobs STROKE noir sans alpha (aligné IME) — FILL alpha très coûteux sur EPD
- Seul le blob SELECTED est affiché (`groupsInState(SELECTED)`, source de vérité)
- `hitTestBlob()` utilise le path elliptique (`Region.setPath()`) — pas la bounding box
- `loadPageFull()` reconstruit le bitmap depuis les strokes (plus de PNG corrompu)

### Groupes & Absorption
- **Nettoyage `strokeToGroup`** : `onStrokeSealed()`, `registerLoadedGroup()`, `syncStrokeIds()` retirent le stroke de l'ancien groupe avant réassignation
- **Nouveau groupe automatiquement SELECTED** → blob visible immédiatement
- **Désélection automatique** quand un stroke est écrit hors du blob SELECTED
- **`returnToWriting()`** ne désélectionne plus — le SELECTED persiste au relâchement
- **Ré-inférence** après effacement (`scrubGroup`) et absorption (`onStrokeSealed`)

### Interface standalone
- Refonte toolbar : `[✕] [◄ 1/5 ►] [+]` avec menus contextuels (clic long)
- `✕` → fermer bloc + ouvrir nouveau | menu : Vider, Paramètres, Fermer
- `+` → nouvelle page | menu : Début, Après, Fin
- `newPage()`, `newPageAtBeginning()`, `newPageAtEnd()` dans MiroirEngine
- Zone de capture sous la toolbar (LinearLayout vertical)
- Œil calibration 👁/⌣ (32f) — toggle labels
- Labels sous l'interligne, 40f, alignés à gauche (`anchor.first`)

### Scrub (effacement)
- Position relative du stylet dans la largeur du groupe → ratio de coupe
- Preview : surbrillance des points conservés (trait 5f)
- Application au PEN_UP (`applyScrubCut()`)

### Déplacement (moveGroup)
- Aligné IME : `drawColor(CLEAR)` + `fullRedraw` + `Matrix.translate` blob
- Effacement ciblé remplacé par redraw complet (plus rapide sur EPD)

### Calibration
- Menu épuré : Blob X/Y, Délai inférence, Délai affichage, Appui long, Template
- `applyCalibrationParams()` dans `onResume()` — paramètres propagés en temps réel
- `scheduleInference()` utilise les délais de calibration
- `FontaineOverlay` long-press utilise `getLongPressDelay()`

### MDM
- Regex accepte l'apostrophe et le tiret : `@([\w'\-]+)`
- Métadonnées strokes/points : `@mot{5s/120p}` — rétrocompatible

---

### `0073f99` — fix: réécriture V★ propre + captureIndices + flux texte MDM

**Stockage V★** (`MiroirIME.kt` + `VStarConduit.kt`) :
- `savePage()` réécrit `page.vstar` avec strokes vivants seulement (CI préservés, plus d'accumulation)
- `captureIndex` traité en non signé (`and 0xFFFF`) — plage 0-65535, plus de wraparound à 32767

**Groupes** (`MiroirIME.kt`) :
- `groups.json` utilise `captureIndices` (immuables) au lieu de `registryIndices` (volatils après effacement)
- Rétrocompatibilité V1 (`registryIndices`) + V0 (`strokeIds`)

**Inférence ML Kit** (`MiroirIME.kt`) :
- `recognizeGroup()` : ordre chronologique (tri spatial supprimé — ML Kit lisait à l'envers)

**Texte & UI** (`MiroirIME.kt`) :
- `buildReadingOrderText()` : nettoie les labels avec `cleanLabelForMdm()` (plus de balises `@`)
- `toggleFormattingMode()` : injecte texte propre au lieu de MDM brut
- Bouton ✓ : `pushTextToParnasse()` + toggle seulement si `!isFormattingMode`
- `openConduit()` : crée le bloc si `blockDir` null (survie navigation)


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
