# Changelog — Branche `recyclage-dataset-odbl`
> 5-6 juillet 2026 — Capitaine & Hermes
> 25 commits — 3 fichiers — +800/-80 lignes

## ✨ Nouveautés

### 📦 DatasetExporter — Recyclage et récolte ODbL
- **Format permissif** : capture toutes les dimensions (x, y, t, p)
- **Format anonymisé** : deltas reset, pas d'absolus (prêt pour publication)
- **Double mode** : 🌾 Récolter (exporte sans détruire) / ♻️ Recycler (exporte puis supprime)
- **Hash SHA-256** par échantillon pour intégrité
- **JSONL** (un échantillon par ligne) + **README ODbL**
- **Anti-doublon** : marqueur `.exported` dans le dossier du bloc
- **Dossier public** : `Downloads/Parnasse/` (accessible utilisateur)
- **Lecture V★ v2.0** : utilise `VStarDocumentV2` pour décoder les tokens
- **Groupement par `captureIndex`** : reconstruction fidèle des strokes multi-points
- **Filtrage 🔒** : `excludedLabels` — les labels marqués personnels sont exclus du dataset

### 🖼️ Interface — Toolbar
- **✕ à gauche, ✓ à droite** — réorganisation ergonomique
- **✓ clic court** : commit → bascule vers vue mise en forme (IME reste ouverte)
- **✓ appui long** : commit → exécute/ferme l'IME
- **Switch ⌣ (ON) / 👁 (OFF)** sous le numéro de page — 44f, labels seulement
- **Cases à cocher** multi-sélection dans la liste des blocs
- **Indicateur 📦** pour les blocs déjà exportés
- **Boutons 🌾 Récolter / ♻️ Recycler** dans la vue liste des blocs

### 🔒📌 Pastilles de correction (dans le cadre bleu)
- **🔒 à gauche** : toggle d'exclusion dataset (données personnelles)
- **📌 à droite** : toggle de validation sans corriger (controlledLabels)
- **Feedback visuel** : couleur change (rouge/vert), bordure épaisse
- **Restent en mode correction** — sortie par clic dans le vide
- **Dessinées dans `onDraw()`**, détection de hit dans `onTouchEvent`

### 🧹 Nettoyage des strokes de correction
- **`correctionPaths.clear()`** dans `exitEditMode()` + après chaque correction de caractère
- **`correctionStartRegistrySize`** : snapshot du registre à l'entrée en mode correction
- **`isDeleted = true`** + nettoyage `inkStrokeIdToRegistryIndex` à la sortie
- **`redrawBitmapOnly()`** filtre `isDeleted` — les strokes fantômes ne survivent plus
- **`postInvalidate()`** dans `exitEditMode()` — redessin immédiat

### 🔧 Correctifs
- **Ordre spatial** : strokes triés par Y (ligne) puis X (horizontal) avant encodage V★
- **CheckBox indépendante** : clic case ≠ ouverture du bloc
- **Boutons correction** : fonds colorés (visibles sur e-ink)
- **DATA_PATH Windows** : syntaxe `C:\Users\...` obligatoire (pas `/c/Users/...`)
- **`showCorrectionOverlay()`** neutralisée (l'overlay plein écran n'est plus d'actualité)

## 📁 Fichiers

| Fichier | Changement |
|---------|-----------|
| `DatasetExporter.kt` | **Nouveau** — 470 lignes |
| `MiroirIME.kt` | +330/-50 lignes |
| `CHANGELOG-recyclage.md` | Ce document |

## 🧪 Dataset produit

| Session | Échantillons | Points | Taille |
|---------|-------------|--------|--------|
| Test initial | 15 | 13 879 | 518 KB |
| Onyx Note (8 pages) | 369 | 404 219 | 15 MB |
| Test 🔒 | 5 | — | 242 KB |
| **Total** | **393+** | **418 000+** | **~16 MB** |

## 🗺️ Roadmap

- [x] 🔒 Filtrage données personnelles
- [x] 📌 Annotation sans correction
- [x] ⌣ Toggle labels debug
- [x] ✓ Validation sans fermeture IME
- [x] 🧹 Nettoyage strokes de correction
- [ ] Intent "Partager" après export (mail, Drive)
- [ ] Détection de forme (stroke isolé + stop long → SVG)
- [ ] Token « groupe à cheval » et intercalé (% superposition)
- [ ] Labels différés et modes de calibration
- [ ] Upload automatique vers Hugging Face
