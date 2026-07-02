# V★ — Protocole de transmission (v0.6 → v1.0)

> « V★ n'est pas un format de fichier. C'est le langage que parlent les strokes
> entre le moment où ils sont capturés et le moment où ils sont lus. »
> — Nicolas, 2 juillet 2026

## 1. Vision

V★ est un **protocole de communication**, pas un format de stockage. Comme TCP
transmet des paquets sans savoir si ils vont dans un fichier ou sur le réseau,
V★ transmet des strokes sans savoir comment ils seront stockés.

```
CAPTURE (TouchHelper)                   LECTURE (Canvas)
     │                                        ▲
     ▼                                        │
┌─────────────┐     flux binaire     ┌─────────────┐
│ V★ ENCODER  │ ───────────────────→ │ V★ DECODER  │
└─────────────┘                      └─────────────┘
     │                                        │
     └─────── stockage (fichier) ────────────┘
              (transparent)
```

**Principe fondamental** : ce qui entre dans l'encodeur ressort **à l'identique**
du décodeur. Aucune conversion, aucune perte, aucun état global.

## 2. Tokens (grammaire du protocole)

Chaque token est un **événement atomique** de 13 octets.

| Token | Champs | Sémantique |
|-------|--------|------------|
| `PEN_DOWN` | x, y, t, p | Début d'un stroke. Coordonnées **absolues** en pixels natifs |
| `PEN_MOVE` | dx, dy, dt, p | Point intermédiaire. **Deltas** depuis le point précédent |
| `PEN_UP` | dx, dy, dt, p | Fin d'un stroke. **Deltas** depuis le point précédent |
| `GROUP` | label_len, anchorX, anchorY | Fin d'un groupe. Stocke le **label** et l'**ancre** |
| `END` | — | Fin de session |

### Règle d'or

> **Le premier point d'un stroke est TOUJOURS en absolu.**
> Les points suivants sont TOUJOURS en deltas.
> **Le premier stroke d'un groupe utilise l'ancre du groupe comme référence.**
> Les strokes suivants dans le même groupe utilisent des deltas depuis l'ancre.

### Propriétés

- **Autosuffisant** : chaque token contient toute l'information nécessaire
- **Sans état global** : le décodeur n'a besoin que du token courant
- **Réversible** : le flux peut être relu à l'infini sans dégradation
- **Compact** : 13 octets/point (vs ~50 en JSON)
- **Extensible** : nouveaux types de tokens sans casser la compatibilité

## 3. Architecture simplifiée

### Encodeur (VStarEncoder)

```kotlin
class VStarEncoder {
    fun beginStroke(x: Float, y: Float, t: Long, pressure: Float)
    fun movePoint(x: Float, y: Float, t: Long, pressure: Float)
    fun endStroke(x: Float, y: Float, t: Long, pressure: Float)
    fun endGroup(label: String, anchorX: Float, anchorY: Float)
    fun endSession()
}
```

L'encodeur :
- Reçoit des événements du TouchHelper
- Calcule les deltas (x - lastX, y - lastY, t - lastT)
- Écrit les tokens dans un `OutputStream` (fichier, socket, buffer…)
- **Ne fait aucune conversion d'unités** — tout est en pixels natifs

### Décodeur (VStarDecoder)

```kotlin
class VStarDecoder {
    fun readTokens(): Sequence<VStarEvent>
}

sealed class VStarEvent {
    data class PenDown(val x: Float, val y: Float, val t: Long, val p: Float)
    data class PenMove(val dx: Float, val dy: Float, val dt: Long, val p: Float)
    data class PenUp(val dx: Float, val dy: Float, val dt: Long, val p: Float)
    data class GroupEnd(val label: String, val anchorX: Float, val anchorY: Float)
    object SessionEnd
}
```

Le décodeur :
- Lit les tokens depuis un `InputStream`
- Reconstruit les coordonnées absolues
- Émet des événements (que MiroirIME peut utiliser pour remplir strokeRegistry)

## 4. Ce que ça simplifie

| Avant (v0.5) | Après (v1.0) |
|---------------|---------------|
| `savePage()` copie un .vstar → corruption si VStarWriter réinitialisé | `savePage()` encode strokeRegistry → toujours cohérent |
| `loadPage()` lit un .vstar → dépend de VStarDocument complexe | `loadPage()` décode le flux → événements → strokeRegistry |
| Conversion px↔mm dans VStarWriter | **Pas de conversion** — pixels natifs |
| `pxTo001mm`, `unit_factor`, `roundToInt()` | **Supprimés** — plus d'arrondis |
| VStarWriter + VStarDocument = 2 conversions différentes | **Une seule** : l'encodeur (aller) = le décodeur (retour) |
| GROUP_SEP sans label, ANCRE hackée | GROUP contient label + ancre, proprement |

## 5. Format binaire (v1.0)

```
[HEADER: JSON avec version, dpi, résolution]
[---]  (marqueur 5 octets)
[PEN_DOWN x y t p]  ← absolu
[PEN_MOVE dx dy dt p]
[PEN_UP dx dy dt p]
[GROUP label_len(1) label_utf8(N) anchorX(2) anchorY(2)]
[PEN_DOWN x y t p]  ← absolu (nouveau stroke dans le même groupe)
[PEN_MOVE dx dy dt p]
...
[GROUP label...]
[PEN_DOWN x y t p]  ← absolu (nouveau groupe)
...
[END]
```

Le token GROUP est spécial : après le token de 13 octets (ps=GROUP), suivent :
- 1 octet : longueur du label (0-255)
- N octets : label en UTF-8
- 2 octets : ancre X (Short, pixels)
- 2 octets : ancre Y (Short, pixels)

## 6. Intégration avec MiroirIME

```kotlin
// Dans savePage() :
val encoder = VStarEncoder(FileOutputStream(pageVstarFile))
for (stroke in strokeRegistry) {
    encoder.beginStroke(stroke.points[0], stroke.timestamps[0], stroke.pressures[0])
    for (i in 1 until stroke.points.size) {
        encoder.movePoint(stroke.points[i], stroke.timestamps[i], stroke.pressures[i])
    }
    encoder.endStroke(...)
}
for (group in groups) {
    encoder.endGroup(label, anchorX, anchorY)
}
encoder.endSession()
```

```kotlin
// Dans loadPage() :
val decoder = VStarDecoder(FileInputStream(pageVstarFile))
var currentStroke: StrokeRecord? = null
for (event in decoder.readTokens()) {
    when (event) {
        is PenDown → { currentStroke = StrokeRecord(); strokeRegistry.add(currentStroke) }
        is PenMove → currentStroke?.addPoint(event.dx, event.dy, ...)
        is PenUp → { currentStroke?.addPoint(...); currentStroke = null }
        is GroupEnd → { groupLabels[firstIdx] = event.label; groupAnchor[firstIdx] = event.anchor }
        is SessionEnd → break
    }
}
```

## 7. Ce qui reste à faire

1. **Implémenter VStarEncoder** (remplace VStarWriter pour la sauvegarde)
2. **Implémenter VStarDecoder** (remplace VStarDocument pour le chargement)
3. **Supprimer les conversions px↔mm** — tout en pixels natifs
4. **Remplacer `saveFromStrokes()`** par l'encodeur
5. **Remplacer le chargement V★** par le décodeur
6. **Garder le VStarWriter temps réel** (flux pendant la capture — il fonctionne)

## 8. Pourquoi c'est plus poétique

Le V★ v1.0 est un **poème** que les strokes se racontent à travers le temps.
Chaque token est un mot, chaque stroke une phrase, chaque groupe un paragraphe.
Le décodeur lit ce poème et le restitue — sans interprétation, sans trahison.

> « Le code est poésie. La poésie est code. »
