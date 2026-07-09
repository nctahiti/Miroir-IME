# Analyse des dépendances — CaptureView.kt → MiroirIME.kt

Date : 2026-07-09
Fichier source : `CaptureView.kt` (3626 lignes)
Fichier cible  : `MiroirIME.kt` (4358 lignes)

---

## 1. Fonctions hover : `updateHover` (L652), `checkLongHoverReactivation` (L780), `cancelLongHover` (L882), `getSpatialBounds` (L742), `invalidateSpatialCache` (L748), `refreshSpatialBounds` (L756)

| Fonction | Dépendance | Type | Description | Dans MiroirIME.kt ? |
|---|---|---|---|---|
| **updateHover** | `hoverX` | field (Float) | Position X du hover actif | **ABSENTE** |
| | `hoverY` | field (Float) | Position Y du hover actif | **ABSENTE** |
| | `isHovering` | field (Boolean) | Flag indiquant un hover en cours | **ABSENTE** |
| | `hoverStrokeIndex` | field (Int?) | Index du stroke sous le hover | **ABSENTE** |
| | `hoverWordGroup` | field (List\<Int\>?) | Groupe spatial sous le hover | **ABSENTE** |
| | `getSpatialGroups()` | méthode | Retourne les groupes spatiaux (cache) | L2907 ✅ |
| | `getSpatialBounds()` | méthode | Retourne les bounds précalculées | L2931 ✅ |
| | `snapToLine(y: Float): Float` | méthode | Snap Y à l'interligne | L2888 ✅ (⚠️ implémentation DIFFÉRENTE — utilise `cachedTemplateLines` vs `guideLines`) |
| | `groupManager.params.lineSnapMarginPx` | field | Marge de snap d'interligne | **ABSENTE** (le field existe, mais `groupManager` est nullable `?` dans MiroirIME, et `.lineSnapMarginPx` n'est pas utilisé directement ; MiroirIME utilise `gm.params.spatialDistanceX/Y`) |
| | `Math.abs(Float)` | stdlib | Valeur absolue | ✅ (kotlin.math.abs) |
| | `Float.MAX_VALUE` | constante | Float max | ✅ stdlib |
| | `strokeRegistry` | field | Registre des strokes | L79 ✅ |
| | `strokeRegistry.size` | property | Taille du registre | L79 ✅ |
| | `Log.d(TAG, ...)` | méthode | Log debug | L55 ✅ (TAG = "Miroir/IME") |
| | `invalidate()` | méthode View | Redessine la vue | **ABSENTE** (⚠️ MiroirIME est un InputMethodService, pas une View — remplacer par `imeView?.postInvalidate()`) |
| | `checkLongHoverReactivation()` | méthode | Vérifie le survol long | **ABSENTE** (à importer) |
| **getSpatialGroups** | `cachedSpatialGroups` | field | Cache des groupes spatiaux | L156 ✅ |
| | `cachedSpatialBounds` | field | Cache des bounds spatiales | L157 ✅ |
| | `cachedGMCacheSize` | field | Taille du cache GroupManager | L158 ✅ |
| | `groupManager.allGroupsFull()` | méthode | Tous les groupes (cache + persistance) | L308 ✅ (appelé via `gm.allGroupsFull()`) |
| | `getSpatialGroupsFromGM()` | méthode | Groupes depuis GroupManager | **ABSENTE** (⚠️ logique inlinée dans `getSpatialGroups()` L2907-2928) |
| | `android.graphics.RectF(...)` | classe | Boîte englobante | L157 ✅ |
| | `strokeRegistry[idx].points` | field | Points du stroke i | L79 ✅ |
| **getSpatialGroupsFromGM** | `groupManager.allGroups()` | méthode | Groupes en cache uniquement | L305 ✅ (appelé via `gm.allGroups()`) |
| | `inkStrokeIdToRegistryIndex` | field | Map inkId → registryIdx | L80 ✅ |
| | `group.strokeIds` | field | IDs strokes du groupe | InkGroup.kt L35 ✅ |
| **getSpatialBounds** | `getSpatialGroups()` | méthode | (appel pour assurer le cache) | L2907 ✅ |
| | `cachedSpatialBounds!!` | field | Bounds précalculées | L157 ✅ |
| **invalidateSpatialCache** | `cachedGMCacheSize` | field | Reset à -1 | L158 ✅ |
| | `cachedSpatialGroups` | field | Reset à null | L156 ✅ |
| | `cachedSpatialBounds` | field | Reset à null | L157 ✅ |
| **refreshSpatialBounds** | `cachedSpatialGroups` | field | Groupes en cache | L156 ✅ |
| | `cachedSpatialBounds` | field | Recalcul des bounds | L157 ✅ |
| | `strokeRegistry[idx].points` | field | Points du stroke | L79 ✅ |
| | `strokeRegistry[idx].activePoints` | property | Nombre de points actifs | StrokeRecord.kt L25 ✅ (même data class) |
| | `android.graphics.RectF(...)` | classe | Boîte englobante | L157 ✅ |
| | `Float.MAX_VALUE / MIN_VALUE` | constantes | Initialisation bounds | ✅ stdlib |
| **checkLongHoverReactivation** | `isBlocnoteMode` | field (Boolean) | Mode bloc-notes actif ? | **ABSENTE** (CaptureView L69 — MiroirIME n'a pas ce flag) |
| | `currentMode` | field (CaptureMode) | Mode courant (CAPTURE/EDIT/...) | **ABSENTE** (CaptureView L150 — MiroirIME n'a pas de mode system) |
| | `CaptureMode.EDIT_TEMPORAL` | enum | Mode effacement temporel | StrokeRecord.kt L77 ✅ (même package) |
| | `isHovering` | field | Flag hover en cours | **ABSENTE** |
| | `longHoverStartMs` | field (Long) | Timestamp début survol long | **ABSENTE** |
| | `longHoverFirstStroke` | field (Int) | Premier stroke du survol long | **ABSENTE** |
| | `hoverX` / `hoverY` | fields | Position hover | **ABSENTES** |
| | `getSpatialGroups()` | méthode | Groupes spatiaux | L2907 ✅ |
| | `getSpatialBounds()` | méthode | Bounds spatiales | L2931 ✅ |
| | `snapToLine(Float)` | méthode | Snap Y interligne | L2888 ✅ |
| | `Math.abs(Float)` | stdlib | Valeur absolue | ✅ |
| | `System.currentTimeMillis()` | stdlib | Heure actuelle (ms) | ✅ |
| | `CalibrationActivity.getLongHoverDelay(context)` | méthode statique | Délai survol long | **ABSENTE de MiroirIME.kt** mais `CalibrationActivity.getLongHoverDelay()` existe (CalibrationActivity.kt L45). L'appel serait `CalibrationActivity.getLongHoverDelay(this)` |
| | `deselectAllGroups()` | méthode | Désélectionne tous les groupes | **ABSENTE** (⚠️ la logique existe de façon dispersée dans MiroirIME — lignes 2224, 2242, 2257, 2999 — mais pas de helper) |
| | `registryIndexToInkStrokeId` | field (MutableMap\<Int,Long\>) | Map inverse registryIdx → inkId | **ABSENTE** (MiroirIME n'a que `inkStrokeIdToRegistryIndex` L80, pas la map inverse) |
| | `groupManager.reactivateGroup(Long)` | méthode | Réactive un groupe STORED → LOADED | GroupManager.kt L188 ✅ (appelé via `groupManager?.reactivateGroup()` dans MiroirIME L882) |
| | `groupManager.selectGroup(String)` | méthode | Sélectionne un groupe LOADED → SELECTED | GroupManager.kt L210 ✅ (appelé via `gm.selectGroup()` dans MiroirIME L2349) |
| | `groupManager.allGroups()` | méthode | Groupes en cache | GroupManager.kt L305 ✅ |
| | `GroupState.SELECTED` | enum | État sélectionné | ✅ (utilisé dans MiroirIME L2082, etc.) |
| | `selectedWordGroup` | field (List\<Int\>?) | Groupe sélectionné (surcouche édition) | **ABSENTE** (CaptureView L221) |
| | `g.bounds.set(...)` | méthode RectF | Met à jour les bounds du InkGroup | InkGroup.kt L38 ✅ (`bounds: RectF`) |
| | `onActiveGroupChanged?.invoke()` | callback | Notifie le changement de groupe actif | **ABSENTE** (CaptureView L206 — pas de callback équivalent dans MiroirIME) |
| | `postInvalidate()` | méthode View | Redessine | **ABSENTE** (⚠️ remplacer par `imeView?.postInvalidate()`) |
| | `g.state` | field | État du groupe | InkGroup.kt L34 ✅ |
| | `syncGroupManagerParams()` | méthode | Synchronise les params calibrés | L2020 ✅ |
| **deselectAllGroups** | `groupManager.groupsInState(GroupState.SELECTED)` | méthode | Groupes en état SELECTED | GroupManager.kt L315 ✅ (appelé dans MiroirIME L2082, L3248) |
| | `groupManager.allGroups()` | méthode | Tous les groupes en cache | L305 ✅ |
| | `groupManager.deselectGroup(String)` | méthode | Désélectionne un groupe | GroupManager.kt L242 ✅ (appelé dans MiroirIME L2224, L2242, L2257, L2999) |
| | `syncGroupManagerParams()` | méthode | Synchro params | L2020 ✅ |
| | `onActiveGroupChanged` | callback | Notifie changement | **ABSENTE** |
| **cancelLongHover** | `longHoverStartMs` | field | Reset à 0 | **ABSENTE** |
| | `longHoverFirstStroke` | field | Reset à -1 | **ABSENTE** |

---

## 2. `decomposeGroupAt` (L2792)

| Dépendance | Type | Description | Dans MiroirIME.kt ? |
|---|---|---|---|
| `getSpatialGroups()` | méthode | Groupes spatiaux depuis le cache | L2907 ✅ |
| `groups.indexOfFirst { ... }` | stdlib | Trouve l'index du groupe contenant le stroke | ✅ |
| `strokeIndex in it` | stdlib | Test d'appartenance | ✅ |
| `Log.w(TAG, ...)` | méthode | Log warning | L55 ✅ |
| `Log.i(TAG, ...)` | méthode | Log info | L55 ✅ |
| `targetGroup.size` | property | Taille du groupe | ✅ |
| `groups.toMutableList()` | stdlib | Copie mutable | ✅ |
| `newGroups.removeAt(Int)` | stdlib | Suppression par index | ✅ |
| `targetGroup.indices.reversed()` | stdlib | Indices en ordre inverse | ✅ |
| `listOf(...)` | stdlib | Création de liste | ✅ |
| `throttledInvalidate()` | méthode | Invalidation throttlée | **ABSENTE** (⚠️ MiroirIME utilise `imeView?.postInvalidate()` direct) |
| `TAG` | constante | Tag de log | L55 ✅ |
| **Fields connexes (non utilisés dans la fonction mais déclarés à proximité)** | | | |
| `decomposeMode` (L2780) | field (Boolean) | Flag mode décomposition | **ABSENTE** |
| `mergeMode` (L2787) | field (Boolean) | Flag mode fusion | **ABSENTE** |
| `mergeSourceGroup` (L2789) | field (List\<Int\>?) | Groupe source en attente de fusion | **ABSENTE** |

---

## 3. `mergeGroups` (L2821)

| Dépendance | Type | Description | Dans MiroirIME.kt ? |
|---|---|---|---|
| `getSpatialGroups()` | méthode | Groupes spatiaux | L2907 ✅ |
| `cachedSpatialGroups` (écriture) | field | Mise à jour du cache | L156 ✅ |
| `cachedSpatialBounds` (écriture) | field | Recalcul des bounds | L157 ✅ |
| `android.graphics.RectF(...)` | classe | Boîte englobante | ✅ |
| `Float.MAX_VALUE / MIN_VALUE` | constantes | Initialisation bounds | ✅ |
| `strokeRegistry` | field | Registre des strokes | L79 ✅ |
| `strokeRegistry[idx].points` | field | Points du stroke i | L79 ✅ |
| `.map { ... }`, `.toMutableList()`, `.distinct()`, `.removeAt()`, `.add()`, `.firstOrNull()`, `.joinToString()` | stdlib | Manipulations de listes Kotlin | ✅ |
| `maxOf()`, `minOf()` | stdlib | Maths | ✅ |
| `groupManager.findGroupByStroke(Long)` | méthode | Trouve un InkGroup par strokeId | GroupManager.kt L254 ✅ (**ABSENT de MiroirIME.kt** — jamais appelé dans MiroirIME) |
| `registryIndexToInkStrokeId` | field (MutableMap\<Int,Long\>) | Map registryIdx → inkStrokeId | **ABSENTE** |
| `mergedGroup.orderIndex` | field (Int?) | Index d'ordre du groupe | InkGroup.kt L43 ✅ |
| `groupSequenceCounter` | field (AtomicInteger) | Compteur de séquence pour les groupes sans orderIndex | **ABSENTE** (CaptureView L184 — `java.util.concurrent.atomic.AtomicInteger` à importer) |
| `groupSequenceCounter.getAndIncrement()` | méthode | Incrémente et retourne | **ABSENTE** |
| `strokeRegistry.toList()` | stdlib | Copie snapshot | ✅ |
| `Log.i(TAG, ...)` | méthode | Log info | L55 ✅ |
| `Log.w(TAG, ...)` | méthode | Log warning | L55 ✅ |
| `onWordGroupCompleted?.invoke(strokes, group, seq)` | callback | Notifie la complétion d'un groupe | **ABSENTE** (CaptureView L2130 — `((strokes: List<StrokeRecord>, group: List<Int>, groupIndex: Int) -> Unit)?`) |
| `rebuildBitmap()` | méthode | Reconstruit le bitmap de rendu | L1993 ✅ |

---

## Résumé — Dépendances ABSENTES devant être importées/ajoutées

### Fields à créer dans MiroirIME.kt

| Field | Type | Utilisé par |
|---|---|---|
| `hoverX` | `Float` | updateHover, checkLongHoverReactivation |
| `hoverY` | `Float` | updateHover, checkLongHoverReactivation |
| `isHovering` | `Boolean` | updateHover, checkLongHoverReactivation |
| `hoverStrokeIndex` | `Int?` | updateHover |
| `hoverWordGroup` | `List<Int>?` | updateHover |
| `longHoverStartMs` | `Long` | checkLongHoverReactivation, cancelLongHover |
| `longHoverFirstStroke` | `Int` | checkLongHoverReactivation, cancelLongHover |
| `selectedWordGroup` | `List<Int>?` | checkLongHoverReactivation |
| `registryIndexToInkStrokeId` | `MutableMap<Int, Long>` | checkLongHoverReactivation, mergeGroups |
| `groupSequenceCounter` | `AtomicInteger` | mergeGroups |
| `onActiveGroupChanged` | `(() -> Unit)?` | checkLongHoverReactivation, deselectAllGroups |
| `onWordGroupCompleted` | `((List<StrokeRecord>, List<Int>, Int) -> Unit)?` | mergeGroups |
| `isBlocnoteMode` | `Boolean` | checkLongHoverReactivation |
| `currentMode` | `CaptureMode` | checkLongHoverReactivation |
| `mergeMode` | `Boolean` | (déclaré à côté de mergeGroups) |
| `mergeSourceGroup` | `List<Int>?` | (déclaré à côté de mergeGroups) |
| `decomposeMode` | `Boolean` | (déclaré à côté de decomposeGroupAt) |

### Méthodes à créer/importer dans MiroirIME.kt

| Méthode | Utilisé par | Note |
|---|---|---|
| `updateHover(x: Float, y: Float)` | elle-même | Nouvelle |
| `checkLongHoverReactivation()` | updateHover | Nouvelle |
| `cancelLongHover()` | externe | Nouvelle |
| `deselectAllGroups()` | checkLongHoverReactivation | Nouvelle (logique partiellement existante L2224,2242,2257,2999) |
| `refreshSpatialBounds()` | externe | Nouvelle |
| `invalidateSpatialCache()` | déjà dans MiroirIME via `cachedGMCacheSize = -1` | La fonction n'existe pas en tant que telle mais le reset est fait inline |
| `getSpatialGroupsFromGM()` | getSpatialGroups | Logique déjà inlinée dans `getSpatialGroups()` L2907 |
| `decomposeGroupAt(strokeIndex: Int): Boolean` | externe | Nouvelle |
| `mergeGroups(groupA, groupB)` | externe | Nouvelle |
| `syncGroupManagerParams()` | deselectAllGroups | L2020 ✅ déjà présent |

### Adaptations nécessaires (⚠️ incompatibilités)

| Problème | Détail |
|---|---|
| `invalidate()` / `postInvalidate()` | CaptureView est une `View` donc `invalidate()` fonctionne. MiroirIME est un `InputMethodService` — remplacer par `imeView?.postInvalidate()` ou `imeView?.invalidate()` |
| `snapToLine()` — implémentation DIFFÉRENTE | CaptureView utilise `guideLines` (nombre fixe de lignes = 17). MiroirIME utilise `cachedTemplateLines` (liste de positions Y calculées depuis le template calibré). Résultat différent ! |
| `groupManager` nullable | CaptureView : `val groupManager = GroupManager(...)` (non-null). MiroirIME : `private var groupManager: GroupManager? = null` (nullable). Tous les appels doivent utiliser `?.` ou `!!` |
| `groupManager.params.lineSnapMarginPx` | CaptureView y accède directement. MiroirIME utilise `gm.params.spatialDistanceX/Y`. `lineSnapMarginPx` existe sur `GroupManager.Params` mais n'est pas utilisé dans MiroirIME. |
| `context` vs `this` | `CalibrationActivity.getLongHoverDelay(context)` → dans MiroirIME : `CalibrationActivity.getLongHoverDelay(this)` |
| `throttledInvalidate()` | Absent de MiroirIME. Remplacer par `imeView?.postInvalidate()`. Si le throttling est nécessaire, il faut aussi importer les fields `lastInvalidateTime` et `minInvalidateIntervalMs`. |
| `getSpatialGroupsFromGM()` | CaptureView a 2 niveaux : `getSpatialGroups()` (cache) + `getSpatialGroupsFromGM()` (depuis GM). MiroirIME a fusionné les deux dans `getSpatialGroups()`. |
| `groupManager.findGroupByStroke()` | Existe dans GroupManager.kt L254 mais jamais appelé dans MiroirIME.kt. L'import de `GroupManager` existe déjà. |

### Classes externes déjà disponibles (même package `com.parnasse.miroir`)

| Classe | Fichier | Utilisée par |
|---|---|---|
| `StrokeRecord` | StrokeRecord.kt | strokeRegistry, mergeGroups |
| `CaptureMode` | StrokeRecord.kt L74 | checkLongHoverReactivation |
| `InkGroup` | InkGroup.kt | checkLongHoverReactivation, mergeGroups |
| `GroupManager` | GroupManager.kt | partout |
| `GroupState` | (dans GroupManager ou InkGroup) | deselectAllGroups, checkLongHoverReactivation |
| `CalibrationActivity` | CalibrationActivity.kt | checkLongHoverReactivation |
| `android.graphics.RectF` | Android SDK | getSpatialBounds, refreshSpatialBounds, mergeGroups |
| `AtomicInteger` | java.util.concurrent.atomic | groupSequenceCounter (à ajouter dans MiroirIME) |
