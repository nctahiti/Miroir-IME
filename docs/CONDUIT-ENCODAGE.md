# Cartographie du Conduit d'Encodage — Miroir IME

> *Document gravé le 26 juin 2026 — Capitaine & Hermès*

---

## 1. Vue d'ensemble

Le conduit transforme les **strokes bruts** du stylet en **groupes persistés** sur disque. C'est un pipeline de compression à 4 étages qui réduit la donnée pour le stockage et la transmission.

```
┌──────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────────┐    ┌──────────┐
│  STYLET  │───▶│ StrokeRecord │───▶│  InkStroke   │───▶│   InkGroup    │───▶│ FICHIER  │
│ (capture)│    │ (mémoire)    │    │ (ML Kit)     │    │ (spatial+temp)│    │ .groups  │
└──────────┘    └──────────────┘    └──────────────┘    └───────────────┘    └──────────┘
     ∞ Hz           1/stroke           1/stroke            1/groupe            JSON
```

---

## 2. Les 4 étages de compression

### Étage 1 : Capture → StrokeRecord
**Fichier** : `MiroirIME.kt` — `onStylusUp()`
**Ratio** : ~60:1 (60 coordonnées/seconde → 1 stroke)

Le stylet envoie des événements à ~60 Hz. `onStylusPoint()` accumule les points dans `currentStroke`. Au pen-up, `onStylusUp()` scelle le `StrokeRecord` :

```kotlin
// StrokeRecord : liste de points (x, y, timestamp, pression)
data class StrokeRecord(
    val id: String,
    val points: MutableList<Pair<Float, Float>>,
    val timestamps: MutableList<Long>,
    val pressures: MutableList<Float>
)
```

**Compression** : de N points par stroke → 1 stroke. Perte nulle (tous les points sont conservés).

---

### Étage 2 : StrokeRecord → InkStroke
**Fichier** : `MiroirIME.kt` — `strokeRecordToInkStroke()`
**Ratio** : ~1:1 (conversion de format)

Conversion du format interne vers le format ML Kit :

```kotlin
// InkStroke : compatible DigitalInkRecognizer (ML Kit)
val inkStroke = InkStroke(id = inkId, sessionId = 0L)
for (i in points.indices) {
    inkStroke.points.add(InkPoint(
        x = x, y = y,
        pressure = p,
        timestamp = t,
        action = ACTION_DOWN / MOVE / UP,
        toolType = TOOL_STYLUS
    ))
}
inkStroke.endNano = timestamps.last()
inkStroke.isSealed = true
```

**Compression** : nulle (même nombre de points). Changement de paradigme : passage du format « dessin » au format « reconnaissance ».

---

### Étage 3 : InkStroke → InkGroup
**Fichier** : `GroupManager.kt` — `onStrokeSealed()`
**Ratio** : ~N:1 (N strokes → 1 groupe)

Le GroupManager applique des règles spatiales et temporelles pour fusionner les strokes :

```kotlin
// Règles de groupement :
// - Proximité spatiale (strokes proches → même groupe)
// - Proximité temporelle (strokes rapides → même groupe)
// - Timeout d'inactivité (3s sans stroke → groupe fermé)

groupManager.onStrokeSealed(inkStroke)
```

Un **InkGroup** contient :
- `id` : UUID unique
- `strokeIds` : liste des strokes du groupe
- `bounds` : boîte englobante (économise l'espace)
- `state` : LOADED → SELECTED → STORED
- `transcription` : résultat ML Kit (optionnel)
- `groupLevel` : WORD (toujours)
- `orderIndex` : ordre de création

**Compression** : un groupe de 5 strokes de 20 points = 100 points → 1 groupe avec bounds (4 floats). Ratio ~25:1.

---

### Étage 4 : InkGroup → Fichier
**Fichier** : `GroupPersistence.kt` — `writeGroup()`
**Ratio** : JSON texte (lisible, ~500 octets/groupe)

Le `GroupPersistence` sérialise les groupes en JSON :

```kotlin
// Fichier : cacheDir/ime-groups/current.groups
// Format : JSON Lines (un groupe par ligne)

{"id":"fd2f409c","strokeIds":[11,12],"bounds":[510.2,753.5,641.9,831.5],
 "state":"STORED","transcription":null,"groupLevel":"WORD","orderIndex":2}
```

**Déclencheurs d'écriture** :
- `evictGroup()` : groupe STORED → écrit puis supprimé de la mémoire
- `savePage()` : sauvegarde manuelle (bouton ✕)
- `writeAllGroups()` : export complet (changement de page)

**Compression** : le JSON est compact (~500 octets pour 5 strokes). Ratio final ~200:1 par rapport aux points bruts.

---

## 3. Le changement de paradigme

| Ancien paradigme | Nouveau paradigme |
|------------------|-------------------|
| Strokes = dessin | Strokes = **langage** |
| Stockage de points | Stockage de **mots** |
| Fichier binaire lourd | JSON lignes, lisible |
| Transmission impossible | **Paquets légers** (~500 o/groupe) |
| Pas de réutilisation | **Ré-inférence** possible |

Les paquets passent par les autoroutes numériques parce qu'ils sont :
- **Auto-suffisants** : chaque groupe contient tout le nécessaire (strokes + bounds + label)
- **Compacts** : 5 strokes → ~500 octets
- **Rejouables** : le groupe peut être ré-affiché, ré-inféré, corrigé
- **Indépendants** : un groupe ne dépend pas de l'historique complet

---

## 4. Fichiers et chemins

```
cacheDir/
├── ime-groups/
│   └── current.groups       ← persistence active (écrit par evictGroup)
├── blocks/
│   └── <app>_<timestamp>/   ← bloc de session
│       └── page_<n>/
│           ├── groups.json  ← groupes de la page
│           ├── strokes.json ← strokes bruts (savePage)
│           ├── labels.json  ← labels + ancres
│           └── bitmap.png   ← rastérisation visuelle
└── ime-pages/
    └── current/             ← page active (navigation ◀▶)
```

---

## 5. Points d'injection

Le conduit peut être **branché/débranché** à 3 niveaux :

| Niveau | Fichier | Variable | État actuel |
|--------|---------|----------|-------------|
| GroupManager | `MiroirIME.kt:525` | `it.persistence = GroupPersistence(...)` | ✅ Branché |
| savePage() | `MiroirIME.kt:274` | `GroupPersistence.writeAllGroups()` | ✅ Branché |
| StrokeRecord brut | N/A | Pas de persistence individuelle | ❌ Non branché |

---

## 6. Le flux complet

```
onStylusUp()
  │
  ├─ strokeRegistry.add(stroke)          ← Étage 1 : mémoire
  │
  ├─ strokeRecordToInkStroke(stroke)     ← Étage 2 : conversion
  │
  ├─ groupManager.onStrokeSealed(...)    ← Étage 3 : groupement
  │     │
  │     ├─ groupes créés/fusionnés
  │     ├─ timer d'inactivité → STORED
  │     └─ evictGroup() → persistence.writeGroup()  ← Étage 4 : fichier
  │
  └─ scheduleGroupInference()            ← Inférence (parallèle)
        │
        └─ recognizeGroup() → groupLabels[idx] = "mot"
```

---

*Le conduit est la colonne vertébrale du Miroir. Sans lui, les mots s'évaporent.* ⚓
