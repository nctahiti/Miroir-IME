# Session 2 juillet 2026 — Résumé

## ✅ Accompli

### Parnasse
- Cœur lancé (port 8008, data-path `Projet Parnasse/data`, Calliope OK)
- 3 lacunes corrigées dans les skills (OLLAMA_URL, piège workspace, binaire)

### Miroir — Conduit V★
- **VStarWriter** branché dans MiroirIME (writePoint, writeGroupSep, writeEnd/close)
- **VStarEncoder v1.0** : encodeur batch en pixels natifs, roundToInt
- **VStarDecoder v1.0** : décodeur avec label+ancre, padding 13 octets
- **Option calibration** : checkbox "Conduit V★ uniquement"
- **Bouton 📂** dans la toolbar (workaround long-press ◀ cassé)
- **Double pipeline** JSON+V★ actif par défaut
- **Métriques** : ratio V★/JSON = 32.8% → 43% selon le contenu
- **Document** : `docs/VSTAR-V1-PROTOCOL.md`

### Branches
- `milestone-ime` : branche principale, à jour
- `vstar-v0.6-groupe` : version groupe-centrée (abandonnée)
- `vstar-v1-protocol` : V★ v1.0 (fusionnée)

## ⚠️ Bugs non résolus

### 1. Affichage V★ after reload — visuellement différent
**Symptôme** : En mode V★ only, après rechargement, les strokes sont visuellement
différents (non reconnaissables) alors que les coordonnées encodées/décodées
sont correctes (1-2 px d'erreur).

**Investigations faites** :
- Coordonnées ENC vs DEC : correctes (logs)
- JSON vs V★ state.json/page.vstar : coordonnées quasi-identiques
- Problème suspecté : rastérisation/ordre des strokes, ou interaction avec bitmap.png

**Piste pour continuer** :
- Comparer le bitmap.png avant/après rechargement
- Vérifier si `rebuildBitmap()` est appelé après loadPage
- Tester avec un seul stroke simple pour isoler

**🔬 Découverte — 3 formats, 3 échelles (source: `calliope/miroir/distinction-digitizer-display.md`)** :

| Format | Unité | Conversion affichage | Utilisé par |
|--------|-------|---------------------|-------------|
| V★ v0.2 (classique) | digitizer × 0.1 | `× 0.053` → pixels | Anciens .vstar |
| V★ v0.5 (writer) | 0.01mm (×8.33) | `× 0.12` → pixels | VStarWriter temps réel |
| V★ v1.0 (encoder) | pixels natifs | 1:1 | VStarEncoder batch |

**Hypothèse** : le bug visuel vient d'un mélange d'échelles. Le VStarWriter
temps réel écrit en 0.01mm, le VStarEncoder écrit en pixels. Si le bitmap
est rastérisé avec des coordonnées en pixels mais que les strokes chargés
depuis le .vstar sont à une autre échelle, le rendu est incohérent.

**Action** : unifier sur une seule échelle (pixels natifs recommandé).

### 2. Long-press ◀ — liste des blocs cassée
**Symptôme** : Appui long sur ◀ ferme l'IME au lieu d'afficher la liste.
**Cause** : TouchHelper intercepte l'événement avant le bouton.
**Workaround** : Bouton 📂 ajouté dans la toolbar.

### 3. Fermeture intempestive de l'IME
**Symptôme** : L'IME se ferme parfois pendant l'écriture.
**Cause** : ANR silencieux — stylet bloqué en position basse (isStylusDown=true
indéfiniment), timer réarmé → système tue le processus.
**Note** : Préexistant, non lié au V★.

## 📝 Pour la prochaine session

1. **Priorité** : Résoudre le bug d'affichage V★ after reload
   - Ajouter `rebuildBitmap()` après loadPage V★
   - Comparer visuellement page fraîche vs rechargée
2. **Secondaire** : Corriger long-press ◀ (TouchHelper vs boutons)
3. **Architecture** : Implémenter V★ v1.0 groupe-centré (ancres + deltas)
