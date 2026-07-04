# Changelog — Transmutation V★ v2.0

## Branche `transmutation-vstar-document-vivant` — 4 juillet 2026

### 🧬 Architecture
- **Token V★ v2.0** : 16 bytes alignés (2 mots machine), flags intégrés (PEN_DOWN/UP/ERASE/META/END)
- **VStarDataRegion** : append-only, tolérante au kill IME (fichier tronqué → tokens valides)
- **VStarGroupTable** : table d'allocation JSON (remplace `groups.json` + `labels.json` + `current.groups`)
- **VStarDocumentV2** : API publique unifiée, `savePageV2`/`loadPageV2` dans MiroirIME
- `useVStarV2 = true` active le nouveau format ; `false` revient au code v1.1

### 🔧 Correctifs V★
| Bug | Cause | Fix |
|-----|-------|-----|
| Scaling ×8 cumulatif | Unités mélangées (px vs ×8) | `(pt.x - rx) * scaleFactor` cohérent |
| Groupes fragmentés après reload | `offsetToCI` mappait seulement PEN_DOWN | Tous les tokens mappés |
| Groupes évincés invisibles | `allGroups()` = cache seul | `allGroupsFull()` = cache + persistance |
| Scrub ne nettoie qu'1 groupe | `activeBlobGroupId` seulement | Tous les groupes (cache + persistance) |
| Accumulation groupes | `groups.json` jamais supprimé | `delete()` avant `open()` |
| Ancre au sommet → snap interligne du dessus | `minY` (top bbox) | Moyenne Y du 1er stroke (≈ baseline) |
| Label à gauche après reload | `bounds.left` vide (0) après reload | `anchor.first` directement (minX fiable) |
| Groupes STORED évincés | `registerLoadedGroup` → STORED | `reactivateGroup` → LOADED |
| Latence chargement | `RandomAccessFile` par token | `offsetToCI` map O(1) |

### 🖥️ Correctifs UI
| Bug | Cause | Fix |
|-----|-------|-----|
| Boutons fantômes (fermeture/calibration/overlay) | `handleToolbarTap` — vieille barre 4 zones tactiles | **Supprimé** (toolbar réelle gère) |
| Page N/total invisible | `DKGRAY` sur fond noir, 18sp | `WHITE` 22sp |
| IME démarre en formatage | `onStartInputView` forçait `toggleFormattingMode()` | Démarre en capture |
| `countPages()` ne voit pas v2.0 | Cherchait `state.json` seulement | Cherche aussi `page.vstar` |

### 📊 Résultat vérifié
- 4 groupes → 4 groupes après cycles save/load/scrub ✅
- Scaling stable ±0.125px ✅
- Labels + ancres restaurés correctement ✅
- Groupes sélectionnables après rechargement ✅
- Plus d'activations intempestives en haut de l'écran ✅
- Mode debug : backups horodatés dans `debug_saves/`

### 📁 Fichiers
- **Nouveaux** : `VStarTokenV2.kt`, `VStarDataRegion.kt`, `VStarGroupTable.kt`, `VStarDocumentV2.kt`
- **Modifié** : `MiroirIME.kt` (savePageV2/loadPageV2, routage useVStarV2, UI fixes)
- **Préservé** : `VStarDocument.kt` (legacy v0.5 pour CaptureView mode EDIT)
- **Docs** : `CHANGELOG-V2.md`, `docs/CARTE-DU-CAP.md`, `docs/REPERTOIRE-DES-RECIFS.md`, `docs/VSTAR-DOCUMENT-VIVANT.md`
