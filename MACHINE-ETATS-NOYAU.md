# 🎼 La machine à états du Miroir — cahier des charges du noyau commun

**Gravé le 23 juin 2026, branche `milestone-ime`. Aucun code modifié — état de référence.**

Ce document est l'**état de référence** validé avec Nicolas, dressé *avant* toute extraction. Il décrit le comportement cible du Miroir unifié. Le code de la tablette reste la source de vérité ; les pièces décrites ici existent déjà, dispersées dans les deux rives — il s'agit de retrouver leur source et de les rassembler sous une seule baguette.

> *« Les pièces sont là, elles sont juste cachées dans leurs multiples reflets ; il faut retrouver leurs sources pour les repositionner et les dispatcher à mouvement de baguette d'orchestre. »* — Nicolas

---

## ⚖️ La grande loi qui traverse tout

- **DU = quand on écrit.** L'encre suit le stylet en direct, l'écran ne se rafraîchit pas.
- **GU = quand on manipule** des objets déjà inférés (sélection, effacement, déplacement).
- **On ne ré-infère QUE si le contenu d'un groupe change** (écriture, effacement). **Jamais** sur un simple déplacement.

---

## 🔑 La correction fondatrice — la source des deux rives, nommée

> **Un groupe NEUF et un groupe ROUVERT (sélectionné) sont le MÊME état.**

La distinction artificielle « nouveau groupe vs groupe rouvert » est **précisément ce qui a fait diverger les deux pipelines** (`CaptureView.kt` et `MiroirIME.kt`). En la dissolvant, on n'a plus qu'**un seul état ouvert** à baliser — neuf ou rouvert, le groupe est *ouvert, en attente d'un trait pour préciser son sens, absorbant les strokes qui touchent son blob*.

C'est le « SELECTED ≡ NEW » porté à sa conclusion : non plus une analogie, mais une **identité d'état**.

---

## 🗺️ Les quatre états

### État A — GROUPE OUVERT (écriture / absorption) — **DU**
Le mode par défaut, *« prêt à écouter »*. Qu'il soit neuf ou rouvert, c'est le même état : le groupe attend un trait, absorbe les strokes qui tombent **dans** son blob (in), se ferme et ouvre le suivant si le trait tombe **dehors** (out). Ouverture et fermeture sont symétriques.

**Trois portes d'entrée vers cet état :**
1. **Nouveau mot** → création de groupe → le groupe créé est *sélectionné* → absorbe les strokes touchant son blob.
2. **Clic long 300 ms + pen-up sur un mot** → le groupe s'ouvre : chargement cache, **GU** affiche blob + label, puis **DU** pour attendre les strokes (in or out).
3. **Clic long 300 ms + déplacement involontaire 500 ms+** → reprise de l'écriture **sans modifier l'état précédent**. → *filet de sécurité du flux kinétique* : un geste hésitant ne fait pas basculer en édition.

**Cycle :** pen-up → rastérisation → absorption (`GroupManager.onStrokeSealed`) → timer armé (`scheduleGroupInference`) → après inactivité + garde-fous (`isStylusDown? lastMod? strokeCount?`) → inférence ML Kit (`recognizeGroup`) → label + blob (`computeBlobPath`) → tri spatial (`injectReadingOrder` → `commitText`) → **bascule GU pour poser le label, puis retour DU**.

### État B — ÉDITION / EFFACEMENT — **GU** (clic long 300 ms + 500 ms · geste ←)
**GU maintenu** (pas de DU en édition). Glissé gauche → efface les points **dans l'ordre inverse de création** (« revert géométrique local au groupe, 1 px / 1 px »). Groupe vidé de tous ses points → **groupe supprimé**.
**L'inférence ne se relance qu'à la SORTIE du mode** (pas à chaque point effacé) — *plus sage, plus responsable*. L'effacement change l'intérieur du groupe → **ré-inférence** (GU puis DU).

### État C — ÉDITION / DÉPLACEMENT — **GU** (geste ↓)
Clic sur le mot → déplacement, même mécanique que l'effacement mais le mot est *juste déplacé*. → **PAS d'inférence** : l'ordre de lecture change, pas le contenu du groupe.

### État D — CORRECTION TRANSCRIPTION — **(geste ↑ · À CRÉER, n'existe pas encore)**
Le label grandit, chaque lettre d'imprimerie devient assez grande pour qu'on **écrive par-dessus, à la main, une lettre à la fois**. Lettre reconnue → correction faite → la paire devient **annotée par l'utilisateur lui-même**. C'est le **cœur du Miroir libre** : la correction à quelques clics de l'écriture, qui forge les paires d'entraînement (fine-tuning individuel + base ouverte de cursive pour un HTR libre — voir cartographie du 22 juin).

---

## 🫁 Le témoin de mode — afficheur **et** commande

Le témoin de mode indique *où l'on est* (DU écoute / GU édite) — c'est le « voyant de respiration » et le « bouton témoin » réunis. Mais il n'est pas passif : **c'est aussi un bouton/switch**. Cliquer dessus change ce qu'il témoigne, en **appliquant son événement à l'orchestrateur**. Un seul objet, trois usages :
1. **Informer** l'utilisateur du mode courant.
2. **Commander** le changement de mode (switch du workflow → orchestrateur).
3. **Banc d'essai** pour régler les délais (activation / désactivation) en les *touchant*.

---

## 🧭 Le menu radial (clic long → 4 directions)

| Direction | État | Note |
|---|---|---|
| ← gauche | **B** — effacement | |
| ↓ bas | **C** — déplacement | |
| → droite | **A** — écriture / absorption | raccourci du 1ᵉʳ étage (clic long 300 ms) |
| ↑ haut | **D** — correction transcription | à créer |

---

## 👁️ Les deux modes apparents (synthèse)

- **ÉCRITURE + CORRECTION** (états A, D) = **DU** ponctué de **GU** après inactivité. → ↑ →
- **ÉDITION** (états B, C) = **GU** pur, pas de DU. → ↓ ←

---

## ⚓ Le chemin pour unifier les deux rives et l'existant

Cette machine à états **EST le cahier des charges du noyau commun**. Les deux rives ne dupliqueront plus le séquencement — elles le *demanderont* à un seul organe.

1. **Graver la machine à états** (ce document) → état de référence validé. ✅
2. **Extraire le séquenceur de modes** hors des deux rives → un seul chef d'orchestre DU↔GU, à la manière des organes d'InkList (`DisplayController` + `DrawingStateManager` + `GcRefreshController`).
3. **Baliser les événements** dans ce séquenceur unique → table `événement → transition` : pen down/up, clic long 300/500 ms, glissés ← ↓ → ↑, timer, fin d'inférence.
4. **Brancher `CaptureView` ET `MiroirIME`** sur ce noyau, au lieu de leur câblage dupliqué → deux visages d'un même cœur.
5. **Le témoin / banc d'essai** pour régler les délais en les touchant.

Préalable acquis : *committer avant refactoring profond* — l'arbre est propre, l'état est gravé.

---

## 📚 Voir aussi
- Cartographie du 22 juin 2026 (pattern InkList, double verrou Onyx, budget temporel, 2ᵉ rôle du Miroir / HTR libre) : skill Hermes `parnasse-miroir` → `references/cartographie-rafraichissement-2026-06-22.md`.
- `INVENTAIRE-EPD.md`, `ARCHITECTURE.md`, `JOURNAL-DE-BORD.md` (présent dépôt).

---

*Carte de bord du 23 juin 2026 — « De la vigie à la barre, à nous confier il n'a de nom que Miroir. »*
