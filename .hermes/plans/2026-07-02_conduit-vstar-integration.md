# Conduit V★ — Plan d'intégration

> **Pour Hermes:** Utiliser subagent-driven-development pour implémenter ce plan tâche par tâche.

**Goal:** Brancher le Conduit V★ (VStarWriter) dans le flux de capture Miroir IME pour remplacer le stockage JSON intermédiaire par un flux binaire delta en temps réel. Mesurer l'impact sur les performances et la fluidité avant fusion.

**Architecture:** Le VStarWriter existe déjà (247 lignes, prêt). Il encode chaque point en 13 octets (delta x, y, t, pression, flags) et écrit en append-only sur disque. On le branche EN PARALLÈLE du stockage JSON existant — le .vstar est un compagnon, pas un remplacement. Cela permet de comparer les deux formats sans risque de régression.

**Tech Stack:** Kotlin, Android IME, TouchHelper Onyx, ML Kit Digital Ink, format binaire V★ (VStarToken, VStarWriter, VStarDocument)

**Branche:** `conduit-vstar-integration` (créée depuis `milestone-ime`)

---

### Task 1: Créer la branche parallèle

**Objective:** Isoler le travail du Conduit V★ dans une branche dédiée pour permettre les tests comparatifs.

**Files:**
- Git: branche `conduit-vstar-integration` depuis `milestone-ime`

**Step 1: Créer la branche**

```bash
cd /c/Users/nicol/.openclaw/workspace/miroir-fusion
git checkout milestone-ime
git checkout -b conduit-vstar-integration
```

**Step 2: Vérifier**

```bash
git branch --show-current
# Expected: conduit-vstar-integration
```

---

### Task 2: Instancier VStarWriter dans MiroirIME

**Objective:** Ajouter un champ `vstarWriter` et l'initialiser à l'ouverture du bloc.

**Files:**
- Modify: `app/src/main/java/com/parnasse/miroir/MiroirIME.kt`

**Step 1: Ajouter le champ**

Dans la classe `MiroirIME`, ajouter après les autres champs :

```kotlin
// Conduit V★ — flux binaire parallèle
private var vstarWriter: VStarWriter? = null
```

**Step 2: Initialiser dans `ensureBlockDir()`**

Après la création du `blockDir`, ouvrir une session V★ :

```kotlin
// Dans ensureBlockDir(), après blockDir = File(...)
vstarWriter?.close()
vstarWriter = VStarWriter(this).also {
    val label = appName.take(16)
    it.openNewSession(label)
}
Log.d(TAG, "Conduit V★ ouvert: ${vstarWriter?.getCurrentFile()?.name}")
```

**Step 3: Vérifier la compilation**

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot"
cd /c/Users/nicol/.openclaw/workspace/miroir-fusion
./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL
```

---

### Task 3: Brancher writePoint dans le flux de capture

**Objective:** Chaque point capturé par le TouchHelper est aussi écrit dans le flux V★.

**Files:**
- Modify: `app/src/main/java/com/parnasse/miroir/MiroirIME.kt`

**Step 1: Dans `onStylusDown`**

Après la création du stroke, écrire le premier point :

```kotlin
// Après currentPath.moveTo(x, y)
vstarWriter?.writePoint(x, y, eventTime, pressure, isPenDown = true)
```

**Step 2: Dans `onStylusPoint`**

Pour chaque point intermédiaire :

```kotlin
// Après currentPath.lineTo(x, y)
vstarWriter?.writePoint(x, y, eventTime, pressure, isPenDown = false)
```

**Step 3: Dans `onStylusUp`**

Pour le dernier point (lever de stylet) :

```kotlin
// Après currentPath.lineTo(x, y), AVANT le traitement du groupe
vstarWriter?.writePoint(x, y, eventTime, pressure, isPenUp = true)
```

**Step 4: Garde — ne pas écrire en mode formatting/correction**

```kotlin
// Dans chaque site d'appel :
if (!isFormattingMode && !isCorrecting()) {
    vstarWriter?.writePoint(...)
}
```

**Step 5: Vérifier la compilation et déployer**

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot"
cd /c/Users/nicol/.openclaw/workspace/miroir-fusion
./gradlew assembleDebug
# Expected: BUILD SUCCESSFUL
```

---

### Task 4: Brancher writeGroupSep dans le flux d'inférence

**Objective:** Quand un groupe reçoit son label (inférence ML Kit), marquer la séparation dans le flux V★.

**Files:**
- Modify: `app/src/main/java/com/parnasse/miroir/MiroirIME.kt`

**Step 1: Dans le callback d'inférence (`recognizeGroup` ou `onGroupRecognized`)**

Après qu'un label est assigné au groupe :

```kotlin
// Après group.label = recognizedText
vstarWriter?.writeGroupSep()
Log.d(TAG, "Conduit V★ GROUP_SEP: groupe ${group.id.take(8)}... → '$recognizedText'")
```

**Step 2: Vérifier la compilation**

---

### Task 5: Brancher writeEnd/close dans closeBlock et ✕

**Objective:** Fermer proprement le flux V★ quand le bloc est fermé.

**Files:**
- Modify: `app/src/main/java/com/parnasse/miroir/MiroirIME.kt`

**Step 1: Dans `closeBlock()`**

Avant la suppression éventuelle du bloc :

```kotlin
vstarWriter?.let {
    it.writeEnd()
    it.close()
    Log.i(TAG, "Conduit V★ fermé: ${it.getCurrentFile()?.absolutePath}")
}
vstarWriter = null
```

**Step 2: Dans le handler du bouton ✕**

Même logique de fermeture avant `clearPage()`.

**Step 3: Dans `onDestroy()` (filet de sécurité)**

```kotlin
override fun onDestroy() {
    vstarWriter?.writeEnd()
    vstarWriter?.close()
    vstarWriter = null
    // ... reste du onDestroy
    super.onDestroy()
}
```

---

### Task 6: Ajouter les logs de mesure

**Objective:** Instrumenter le code pour collecter des métriques comparatives JSON vs V★.

**Files:**
- Modify: `app/src/main/java/com/parnasse/miroir/MiroirIME.kt`

**Step 1: Métriques dans `closeBlock()`**

Avant de fermer le VStarWriter, logger les tailles :

```kotlin
vstarWriter?.let { vsw ->
    val vstarFile = vsw.getCurrentFile()
    if (vstarFile != null && vstarFile.exists()) {
        val vstarSize = vstarFile.length()
        // Taille cumulée des fichiers JSON (state.json + groups.json)
        val jsonDir = File(blockDir, "page_$currentPageIndex")
        val stateFile = File(jsonDir, "state.json")
        val groupsFile = File(jsonDir, "groups.json")
        val jsonSize = (if (stateFile.exists()) stateFile.length() else 0) +
                       (if (groupsFile.exists()) groupsFile.length() else 0)
        val ratio = if (jsonSize > 0) vstarSize.toFloat() / jsonSize * 100 else 0f
        Log.i(TAG, "Conduit V★ METRIQUES: vstar=${vstarSize}B json=${jsonSize}B ratio=${String.format("%.1f", ratio)}% strokes=${strokeRegistry.size}")
    }
}
```

**Step 2: Compteur de points**

Ajouter un champ `private var vstarPointCount = 0` et l'incrémenter à chaque `writePoint`. Logger dans `closeBlock()`.

---

### Task 7: Build, déployer, test fonctionnel

**Objective:** Vérifier que le Conduit V★ n'introduit pas de régression.

**Step 1: Build**

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.18.8-hotspot"
cd /c/Users/nicol/.openclaw/workspace/miroir-fusion
rm -rf .gradle/8.9/transforms
./gradlew assembleDebug
```

**Step 2: Déployer**

```bash
MSYS_NO_PATHCONV=1 adb install -r app/build/outputs/apk/debug/app-debug.apk
MSYS_NO_PATHCONV=1 adb shell am force-stop com.parnasse.miroir.v4
```

**Step 3: Test fonctionnel — checklist manuelle**

- [ ] Ouvrir le Miroir dans une app → le Conduit V★ démarre (logcat: "Conduit V★ ouvert")
- [ ] Écrire quelques mots → les points sont écrits (pas d'erreur dans logcat)
- [ ] Vérifier que la capture fonctionne normalement (labels, groupes, navigation)
- [ ] Fermer l'IME → "Conduit V★ fermé" dans logcat
- [ ] Vérifier le fichier .vstar sur la tablette :
  ```bash
  adb shell "run-as com.parnasse.miroir.v4 ls -la files/vstar/"
  ```
- [ ] Vérifier les métriques : "Conduit V★ METRIQUES: vstar=...B json=...B ratio=...%"

**Step 4: Logcat de vérification**

```bash
adb logcat -s "Miroir/IME" "Miroir/VStarWriter" | grep -E "Conduit|VStar|METRIQUES|Erreur"
```

---

### Task 8: Collecter les métriques comparatives

**Objective:** Produire des chiffres : taille V★ vs JSON, impact sur la latence.

**Step 1: Test standardisé**

Écrire une page complète (même texte) deux fois :
1. Une fois SANS le Conduit V★ (branche milestone-ime) → noter tailles JSON
2. Une fois AVEC le Conduit V★ (branche conduit-vstar-integration) → noter tailles V★ + JSON

**Step 2: Métriques à collecter**

| Métrique | Sans V★ | Avec V★ | Unité |
|----------|---------|---------|-------|
| Taille state.json | | | octets |
| Taille groups.json | | | octets |
| Taille totale JSON | | | octets |
| Taille .vstar | — | | octets |
| Ratio V★/JSON | — | | % |
| Nombre de strokes | | | # |
| Nombre de points | | | # |
| Latence perçue (qualitatif) | | | — |
| ANR / crash | | | # |

**Step 3: Extraire les métriques depuis logcat**

```bash
adb logcat -d -s "Miroir/IME" | grep "METRIQUES"
```

---

### Task 9: Rapport de comparaison et décision de fusion

**Objective:** Documenter les résultats et décider de la fusion.

**Step 1: Écrire le rapport**

Fichier `docs/conduit-vstar-benchmark-YYYY-MM-DD.md` :

```markdown
# Conduit V★ — Benchmark d'intégration

## Méthodologie
- Même texte écrit sur une page complète
- Branche milestone-ime (sans V★) vs conduit-vstar-integration (avec V★)
- Tablette Boox Note Air 5C

## Résultats

| Métrique | JSON seul | JSON + V★ |
|----------|-----------|------------|
| Taille JSON | ... | ... |
| Taille .vstar | — | ... |
| Ratio compression | — | ... |
| Latence perçue | ... | ... |

## Conclusion
...
```

**Step 2: Si les résultats sont satisfaisants → fusion**

```bash
git checkout milestone-ime
git merge conduit-vstar-integration --no-ff -m "feat: intégration Conduit V★ — flux binaire parallèle"
```

**Step 3: Si problèmes → corriger dans la branche, re-tester**

---

## Pièges

- **Thread UI** : `writePoint()` est appelé depuis le thread UI (TouchHelper). `VStarWriter` écrit dans un `BufferedOutputStream` — c'est rapide et non-bloquant. Ne pas déplacer dans un thread séparé (complexité inutile).
- **Mode formatting/correction** : ne pas écrire de points V★ quand l'utilisateur est en mode clavier ou correction (les points ne sont pas des strokes de capture).
- **Premier point après PenDown** : `writePoint(isPenDown=true)` réinitialise les deltas (dx=0, dy=0, dt=0). C'est le comportement attendu — ne pas essayer de calculer un delta depuis le dernier point du stroke précédent.
- **Fichier .vstar non nettoyé** : si le bloc est supprimé (closeBlock avec pageCount==0), penser à supprimer aussi le .vstar associé.
- **Ne pas casser le flux JSON existant** : le .vstar est un COMPAGNON. Les .note/.groups/.json continuent de fonctionner normalement. Aucune modification de savePage/loadPage.

## Vérification finale

```bash
# 1. Le build compile
./gradlew assembleDebug

# 2. Le fichier .vstar est créé sur la tablette
adb shell "run-as com.parnasse.miroir.v4 ls -la files/vstar/"

# 3. Les métriques apparaissent dans logcat
adb logcat -d -s "Miroir/IME" | grep "METRIQUES"

# 4. La capture fonctionne normalement (test manuel)
# → écrire une page, vérifier les labels, naviguer ◀▶
```
