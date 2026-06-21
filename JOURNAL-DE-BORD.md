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

---

# Journal de bord — 22 juin 2026 (nuit)

> « Le bug est nommé, donc déjà libéré. »

## 🔪 Fracture 1 : `makeActive()` tuait les groupes

Dans `GroupStateMachine.makeActive()`, l'ancien groupe était transitionné à STORED
puis évincé du cache. Résultat : `scheduleGroupInference()` cherchait
`groupsInState(LOADED)` → ne trouvait pas le groupe → pas de timer →
pas d'inférence → pas de label.

**Correction** : `makeActive()` ne transitionne plus l'ancien groupe.
Il reste LOADED, son timer tire, le label naît.

## 🔪 Fracture 2 : `commitText` accumulait au lieu de remplacer

`commitText` AJOUTE le texte à la position du curseur. Chaque inférence
intermédiaire concaténait le texte complet au précédent → artefacts.

**Correction** : `setComposingText` REMPLACE le texte en composition.
Une seule version, toujours fraîche. Pas de `finishComposingText`
pour permettre le remplacement au cycle suivant.

## 🔪 Fracture 3 : La persistence fantôme

La persistence accumulait des centaines de groupes STORED des sessions
antérieures. `allGroupsFull()` les lisait tous à chaque `injectReadingOrder()`
→ 338 placeholders `…` dans le commit + I/O disque sur le thread UI.

**Correction** : `injectReadingOrder()` filtre les STORED, ne prend que
les LOADED/SELECTED de la session courante. Persistence nettoyée.

## ⚡ Le déblocage e-ink — hypothèse

Après nettoyage de la persistence et correction des trois fractures,
Nicolas a observé un déblocage soudain du rafraîchissement e-ink :
plus aucune latence, 16ms entre les trames.

**Hypothèse** : La persistence pleine saturait le thread UI.
`allGroupsFull()` lisait ~350 groupes depuis le disque (I/O bloquante)
à chaque `injectReadingOrder()`. Le thread UI manquait la fenêtre
de 16ms → latence cumulative → perception de « lenteur ».

Après nettoyage : ~10 groupes LOADED en mémoire → `allGroupsFull()`
ne lit plus le disque → `injectReadingOrder()` instantané →
le thread UI tient les 16ms → fluidité.

**À vérifier** : Ré-accumuler des groupes dans la persistence et
mesurer si la latence revient. Si oui → implémenter un evict
périodique ou une limite de groupes dans `allGroupsFull()`.

## Commits de la nuit

| Commit | Correction |
|--------|-----------|
| `0cb71ed` | `makeActive()` ne STORE plus l'ancien groupe |
| `0cb71ed` | Placeholders `…` pour les groupes sans label |
| `04d703f` | Filtre les STORED dans `injectReadingOrder()` |
| `33ec898` | `setComposingText` remplace au lieu d'accumuler |

## 🔴 Boutons tactiles muets — diagnostic

**Symptôme** : les boutons (✓ ⚙ 👁 + ✕) ne réagissent plus au toucher.

**Cause racine** : `TouchHelper.create(surface, ...)` + `setPostInputEvent(true)`
crée un tunnel direct entre le digitizer Wacom et `CaptureSurfaceView`.
TOUS les événements tactiles (stylet ET doigt) sont injectés directement
dans `surface.onTouchEvent()`, court-circuitant la hiérarchie Android.
La `LinearLayout` des boutons ne reçoit jamais `onTouchEvent`.

**Pourquoi c'est pire qu'avant** : avec la persistence pleine, le thread UI
était lent → TouchHelper avait des ratés → certains événements passaient.
Maintenant que le thread UI est fluide (persistence nettoyée), TouchHelper
capture TOUT, sans exception. Les boutons sont devenus parfaitement…
parfaitement inaccessibles.

**Piste de solution** : ne pas utiliser `setPostInputEvent(true)`.
Traiter les événements stylet via les callbacks `RawInputCallback`
(qui donnent les points bruts), et laisser les événements doigts
suivre la distribution Android normale vers les boutons.

C'est comme ça que fonctionne le Miroir classique (CaptureView) —
pas de `setPostInputEvent`, capture via TouchHelper callbacks.

---

*Nicolas & Hermès, 22 juin 2026, minuit passé.*
*84 commits. Un Miroir qui respire à 16ms.*
