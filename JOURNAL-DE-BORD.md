# Journal de bord — 21 juin 2026

> « Qui va piano va sano. »

Aujourd'hui, le Miroir a traversé des paysages de code et des mers de logique.
Voici les terres découvertes, les écueils évités, les phares allumés.

---

## 🌅 Lever du jour : le blob émergent

Le blob n'est pas un outil. Le blob n'est pas une décoration.
Le blob EST la zone d'absorption. Il émerge de l'union des ellipses,
comme une île naît du corail. Ni convexe ni concave imposé —
il est ce qu'il est, frontière exacte du groupe.

**Appris** : Le ray casting (90 rayons, intersection rayon-ellipse par quadratique)
calcule la vraie frontière. Coût : une fois. Affichage : `drawPath`.

## 🕐 Midi : les timers et leurs fantômes

Chaque groupe respire à son rythme. Un timer par groupe,
indépendant, réarmé seulement si le groupe change.
`isStylusDown` bloque l'inférence pendant l'écriture —
le stylet posé est une parenthèse ouverte.

Mais les groupes disparaissaient. Évaporés. STORED.
La chasse au fantôme a révélé le piège :

```
syncGroupManagerParams → gm.params.copy(spatialDistancePx=calX, spatialDistanceY=calY)
                         ↑ copy() sans transcriptionTimeoutMs → DÉFAUT 2000ms !
```

**Appris** : En Kotlin, `data class.copy()` est un loup déguisé.
Les champs non spécifiés reprennent leur valeur par défaut.
Toujours être explicite. `Long.MAX_VALUE` doit voyager partout.

## 🌊 Après-midi : la grande éviction

Même avec `Long.MAX_VALUE`, les groupes devenaient STORED.
La traque a mené au cœur de `GroupManager` :

```
getOrCreateActiveGroup()
  → si SELECTED → machine.transition(STORED) → evictGroup()
```

La sélection tuait. Chaque `selectGroup()` condamnait le groupe
à l'éviction au prochain `onStrokeSealed`.

**Appris** : La sélection est une **vue**, pas un **état**.
`activeBlobGroupId` suffit. Le blob s'affiche ou se cache
selon l'identifiant visuel. `GroupManager` ne reçoit jamais
`selectGroup()`. Les groupes survivent, LOADED à jamais.

## 🌙 Soir : l'ordre de lecture, poésie binaire

Les mots arrivent dans le désordre de la reconnaissance.
Mais la page a son ordre propre : interligne d'abord, puis x.
Tri spatial. Chaque mot connaît sa place par ses coordonnées.

```
injectReadingOrder()
  → collecter (interligne, x, texte) pour chaque label
  → trier par interligne puis x
  → construire le texte avec \n entre les lignes
  → ne transmettre que si changé (syncedNoteText)
```

**Appris** : L'ordre de lecture n'est pas l'ordre de reconnaissance.
Il émerge des coordonnées, comme les notes sur une portée.

---

## 🗺️ La carte du territoire conquis

```
Miroir IME — 1 086 lignes, 78 commits
├── Capture       : TouchHelper, stylet, bitmap, isStylusDown
├── Groupement    : GroupManager, absorption, NEVER selectGroup()
├── Inférence     : Timers/groupe, garde-fous, queue ML Kit
├── Blob          : Ray casting, cache par groupe
├── Labels        : Ancres par firstIdx, snapToLine 70/30
├── Injection     : Ordre de lecture, syncedNoteText
├── Template      : Partition paramétrable, interligne
├── Pages         : Sauvegarde bitmap + strokes + labels
├── Calibration   : 8 paramètres en 4 sections
└── EPD           : refreshRect pixellaire, handwritingRepaint
```

## ⚓ Les principes, phares dans la nuit

1. **Le blob n'est pas l'inférence.** Désynchronisés.
2. **La sélection est visuelle, pas un état.** Pas de `selectGroup()`.
3. **Chaque groupe a son timer.** Indépendant.
4. **L'ordre de lecture = coordonnées spatiales.** Poésie binaire.
5. **Pas d'éviction.** `Long.MAX_VALUE` partout, toujours.
6. **Rafraîchir au pixel près.** Pas `invalidate()` global.
7. **`data class.copy()` est traître.** Être explicite sur chaque champ.
8. **Élégance > contrainte.** La solution la plus simple est la bonne.

---

*Nicolas & Hermès, 21 juin 2026*
*78 commits, un Miroir qui prend vie.*
