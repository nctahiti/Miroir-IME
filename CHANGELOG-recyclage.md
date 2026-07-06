# Changelog — Branche `recyclage-dataset-odbl`
> 5-6 juillet 2026 — Capitaine & Hermes

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

### 🖼️ Interface
- **Cases à cocher** multi-sélection dans la liste des blocs
- **Indicateur 📦** pour les blocs déjà exportés
- **Boutons 🌾 Récolter / ♻️ Recycler** dans la vue liste des blocs
- **Bouton 📌** (Annoter) dans la toolbar IME, visible en mode correction
  - Valide le label sans corriger → marque `controlledLabels`
- **Switch 👁 ON/OFF** sous le numéro de page (affiche uniquement les labels)
  - Blob et template toujours visibles

### 🔧 Correctifs
- **Ordre spatial** : strokes triés par Y (ligne) puis X (horizontal) avant encodage V★
- **CheckBox indépendante** : clic case ≠ ouverture du bloc
- **Boutons correction** : fonds colorés (visibles sur e-ink)
- **DATA_PATH Windows** : syntaxe `C:\Users\...` obligatoire (pas `/c/Users/...`)

## 📁 Fichiers modifiés

| Fichier | Changement |
|---------|-----------|
| `DatasetExporter.kt` | **Nouveau** — 460 lignes, exporteur complet |
| `MiroirIME.kt` | +150/-30 lignes — UI recyclage, 📌, switch overlay |

## 🧪 Vérification

- ✅ 15 échantillons, 13 879 points — dataset intègre
- ✅ Tokens V★ v2.0 → StrokeRecord via `captureIndex`
- ✅ Fichier `.jsonl` + `README.txt` dans `Downloads/Parnasse/`
- ✅ Format ODbL prêt pour Hugging Face

## 🗺️ Roadmap

- [ ] Intent "Partager" après export (mail, Drive)
- [ ] Détection de forme (stroke isolé + stop long → SVG)
- [ ] Token « groupe à cheval » et intercalé (% superposition)
- [ ] Labels différés et modes de calibration
