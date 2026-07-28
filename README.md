# 🪞 Miroir — IME & Surface de capture manuscrite

> *« On ne répare pas. On refaçonne. »*

**IME (clavier manuscrit) et surface de capture pour le Parnasse Numérique.**
Capture les strokes du stylet, les groupe spatialement par blobs d'absorption, les reconnaît via ML Kit Digital Ink, et les transmute en notes vers le Cœur Parnasse.

Optimisé pour les tablettes **Onyx Boox** (E-Ink, stylet EMR) avec fallback standard sur appareils non-Boox.

---

## 🏛️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                   MiroirIME                          │
│  InputMethodService — clavier manuscrit contextuel   │
│  ┌───────────────────────────────────────────────┐  │
│  │ 🖊️ Capture (TouchHelper / fallback Android)   │  │
│  │ 👥 Groupement (GroupManager — blobs elliptiques)│  │
│  │ ⏱️ Inférence (ML Kit Digital Ink)              │  │
│  │ 🫧 Blobs (absorption spatiale)                  │  │
│  │ 🏷️ Labels (tri spatial → injection IME)        │  │
│  │ 📄 Pages (navigation, sauvegarde)              │  │
│  │ 🖥️ EPD (e-ink refresh)                         │  │
│  │ ⚙️ Calibration (blob, timers, template)        │  │
│  └───────────────────────────────────────────────┘  │
│  + FontaineOverlay — rendu plume temps réel (Boox)  │
├─────────────────────────────────────────────────────┤
│                 CaptureActivity                      │
│  Surface standalone — écriture libre hors IME        │
│  ┌───────────────────────────────────────────────┐  │
│  │ Même moteur (MiroirEngine partagé)            │  │
│  │ Navigation −/N/+, nouvelle page, fermer       │  │
│  │ Export SD card → /sdcard/Documents/parnasse/   │  │
│  │ Indépendant du contexte Parnasse               │  │
│  └───────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**51 fichiers Kotlin — ~8 700 lignes — APK debug 39 Mo (ML Kit inclus)**

---

## 🎯 Principes de conception

| Principe | Description |
|----------|-------------|
| **Le blob est la source unique de vérité** | Deux strokes dont les blobs se touchent = même groupe. Pas de gap, pas de timer, pas de réconciliation. |
| **Blob ≠ Inférence** | Le blob = zone d'absorption. Le label = témoin d'inférence. Désynchronisés. |
| **Chaque groupe a son timer** | Indépendant. Écrire le groupe B ne réarme pas le timer du groupe A. |
| **L'ordre de lecture émerge des coordonnées** | Tri spatial (interligne, x). Pas de numérotation explicite. |
| **La sélection est une vue** | `activeBlobGroupId` est visuel. Jamais `selectGroup()`. |
| **Pas d'éviction** | Les groupes survivent indéfiniment. `transcriptionTimeoutMs = Long.MAX_VALUE`. |
| **EPD au pixel près** | `refreshRect`, pas `invalidate()` global. |

---

## ⚖️ La machine à états

```
DU = quand on écrit   → encre temps réel, pas de rafraîchissement EPD
GU = quand on manipule → sélection, effacement, déplacement

┌─────────────────────────────────────────────┐
│  État A — GROUPE OUVERT (écriture) — DU     │
│  Le groupe absorbe les strokes dans son blob │
│  Entrée : nouveau mot / clic long / reprise  │
│  Cycle : stroke → absorption → timer → ML Kit│
│          → label → blob → commitText         │
├─────────────────────────────────────────────┤
│  État B — EFFACEMENT — GU                   │
│  Clic long 300ms + geste ←                  │
│  Effacement point par point, ordre inverse   │
│  Ré-inférence à la sortie uniquement         │
├─────────────────────────────────────────────┤
│  État C — DÉPLACEMENT — GU                  │
│  Clic sur le mot → glisser                  │
│  PAS d'inférence (ordre change, pas contenu) │
├─────────────────────────────────────────────┤
│  État D — CORRECTION TRANSCRIPTION           │
│  Geste ↑ → label agrandi → écrire par-dessus │
│  Validation → paire strokes ↔ mot corrigé    │
└─────────────────────────────────────────────┘
```

> **Un groupe NEUF et un groupe ROUVERT sont le MÊME état** — « SELECTED ≡ NEW »

---

## 🔀 Deux circuits de reconnaissance

### ① Circuit Online — ML Kit Digital Ink (tablette, temps réel)

```
Stylet → strokes vectoriels (x,y,t) → DigitalInkWrapper
  → ML Kit Digital Ink (Google, on-device, français)
  → texte → injection IME
```

- **Modèle** : Google ML Kit Digital Ink 18.1.0
- **Latence** : < 100 ms après le dernier stroke
- **Input** : strokes natifs, pas de rasterisation
- **Usage** : reconnaissance temps réel, injection IME

### ② Circuit Raster — EasyOCR (serveur, différé, préparé)

```
Stylet → strokes → rasterisation JPEG 1600×2000
  ⇢ route Singularité préparée (Python/HTTP :7701, EasyOCR)
  ⇢ connexion au flux courant encore à finaliser
  → texte → Cœur → note
```

- **Modèle envisagé** : EasyOCR (CRAFT + CRNN, Apache 2.0, 80+ langues)
- **Statut** : transcripteur et route préparés ; circuit non encore connecté au pipeline principal
- **Usage visé** : transcription différée et traitement par lots

### ③ Horizon — Panoptis

**Panoptis n'est pas encore un composant disponible.** C'est le futur modèle HTR du projet, destiné à apprendre à partir des groupes de strokes V★ associés aux labels validés ou corrigés.

L'objectif est de publier librement le modèle, les poids et les données d'entraînement constituées par le Miroir, avec une gouvernance et des licences explicites.

---

## 🫧 Le blob — absorption spatiale

Le blob est une ellipse autour d'un groupe de strokes. Paramètres calibrés :

```
BlobParams(rx, ry, timeout = ∞)
  ├─ spatialDistanceX/Y → rayons de l'ellipse
  ├─ computeBlobPath() → ray casting configurable (16 rayons par défaut)
  └─ Region.contains(x,y) → test d'appartenance
```

Deux strokes dont les blobs se chevauchent → même groupe. C'est la **seule** règle de groupement. Pas de gap horizontal, pas de marge verticale, pas de critère temporel.

---

## 📝 V★ — Format de stockage vectoriel

Format binaire optimisé pour les strokes manuscrits :

| Composant | Rôle |
|-----------|------|
| `VStarDataRegion` | Lecture et reprise V★ v2.0 (16 octets/token) |
| `VStarConduit` | Écriture append-only dans le fichier définitif |
| `VStarEncoder` / `VStarDecoder` | Sérialisation des strokes |
| `VStarDocument` / `VStarDocumentV2` | Structures historiques et documents multi-pages |
| `VStarWriter` v1.1 | Ancien conduit 14 octets/token, conservé pour transition |

### Dossier canonique d'une page

```text
page.vstar   → flux vectoriel V★ v2.0, brut, append-only, 16 octets/token
groups.json  → groupes de strokes, géométrie et ordre
page.mdm     → composition spatiale lisible et ancres reconnues
page.txt     → texte nettoyé destiné au Cœur
bitmap.png   → rendu visuel de la page
```

`VStarConduit` remplace l'ancien double système fondé sur `VStarWriter` v1.1.

---

## 🔄 Flux de Transmutation

```
Miroir IME (écriture manuscrite)
  ↓ commitText → injectReadingOrder
  ↓ POST /api/miroir/command {action:"push_text", text, context}
  ↓
Cœur Parnasse (:8008)
  ↓ Crée/modifie la note dans la Serre
  ↓ Jardinier → enrichissement automatique
  ↓ RAG → indexation vectorielle
  ↓
Flutter Parnasse
  → Note affichée avec texte + PNG
```

**Miroir standalone** : export SD card → `mirrorToSdcard()` → `/sdcard/Documents/parnasse/miroir/` → MiroirWatcher (toutes les 15s) → import automatique dans la Serre.

---

## 🖥️ EPD & Fontaine (Boox)

Le rendu E-Ink utilise le **SDK Onyx OpenBridge** (`TouchHelper` + `EpdController`) :

| Mode | Usage |
|------|-------|
| **DU** (Direct Update) | Écriture — encre temps réel, pas de rafraîchissement |
| **GU** (Gray Update) | Manipulation — rafraîchissement après sélection/effacement |

**FontaineOverlay** : SurfaceView superposée avec `setZOrderOnTop(true)` pour le rendu plume direct sur le hardware EPD. Détection conditionnelle par fabricant (`ONYX`) et disponibilité du SDK.

Sur appareils non-Boox : **fallback `onDraw` standard** — `CaptureView` gère le rendu via `onTouchEvent` + `Canvas`.

Ce fallback d'affichage ne doit pas être confondu avec la capture aux doigts. Le rendu non-Boox existe, mais le rafraîchissement continu pendant l'écriture et la capture tactile complète restent un chantier séparé à stabiliser.

---

## 📦 Composants

| Composant | Fichier | Rôle |
|-----------|---------|------|
| **MiroirIME** | `MiroirIME.kt` (1 086 lignes) | IME système — clavier manuscrit contextuel |
| **CaptureActivity** | `CaptureActivity.kt` | Surface standalone — écriture libre |
| **MiroirEngine** | `MiroirEngine.kt` | Moteur partagé — strokes, groupes, V★, bitmap, MDM |
| **GroupManager** | `GroupManager.kt` (350 lignes) | Groupement spatial par blobs elliptiques |
| **FontaineOverlay** | `FontaineOverlay.kt` | Rendu plume temps réel (SurfaceView + TouchHelper) |
| **CaptureView** | `CaptureView.kt` | Vue de capture partagée |
| **DigitalInkWrapper** | `DigitalInkWrapper.kt` | Interface ML Kit Digital Ink |
| **VStarDataRegion** | `VStarDataRegion.kt` | Stockage V★ v2.0 (16B/token) |
| **MdmParser** | `MdmParser.kt` | MarkDownMiroir — ancres spatiales `@mot{5s/120p}` |
| **CalibrationActivity** | `CalibrationActivity.kt` (271 lignes) | Réglages blob, timers, template |
| **DisplayController** | `DisplayController.kt` | Contrôleur de mode EPD (DU/GU) |
| **Template** | `Template.kt` | Lignes d'écriture paramétrables (interligne, snap) |
| **SyntheticStrokeGenerator** | `SyntheticStrokeGenerator.kt` | Génération strokes synthétiques (fallback doigts) |
| **BlobAbsorber** | `BlobAbsorber.kt` | Logique d'absorption spatiale |
| **GroupStateMachine** | `GroupStateMachine.kt` | États des groupes (LOADED, SELECTED, STORED) |
| **OnyxBridge** | `bridge/OnyxBridge.kt` | Pont vers le SDK Onyx OpenBridge |

---

## 🚀 Build & Déploiement

```bash
# Build APK debug
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk (39 Mo)

# Installer sur tablette
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Activer l'IME
adb shell ime enable com.parnasse.miroir.v4/com.parnasse.miroir.MiroirIME
adb shell ime set com.parnasse.miroir.v4/com.parnasse.miroir.MiroirIME

# Lancer la surface standalone
adb shell am start -n com.parnasse.miroir.v4/com.parnasse.miroir.CaptureActivity

# Vérifier
adb shell ime list -s   # doit montrer com.parnasse.miroir.v4
```

---

## 📂 Documents internes

| Fichier | Rôle |
|---------|------|
| `ARCHITECTURE.md` | Anatomie du MiroirIME, flux principal, circuits d'inférence |
| `MACHINE-ETATS-NOYAU.md` | Machine à états DU/GU, 4 états, noyau commun cible |
| `BOUTEILLE.md` | Philosophie : le blob, l'argile, le geste du potier |
| `INVENTAIRE-EPD.md` | Inventaire des mécanismes EPD (e-ink) |
| `CHANGELOG.md` | Historique des versions |
| `JOURNAL-DE-BORD.md` | Journal de développement |
| `analyse-dependances-captureview.md` | Analyse des dépendances de CaptureView |

---

## ⚙️ Calibration

Les paramètres sont ajustables via `CalibrationActivity` :

| Paramètre | Description |
|-----------|-------------|
| `spatialDistanceX` / `spatialDistanceY` | Rayons du blob d'absorption |
| `inferDelay` | Délai avant déclenchement de l'inférence |
| `displayDelay` | Délai avant affichage du label |
| `longPressDelay` | Délai de clic long (sélection) |
| `lineSpacing` | Interligne du template |
| `lineThickness` | Épaisseur des lignes du template |

---

## 🙏 Remerciements

Ce projet utilise le **SDK Onyx OpenBridge** (`com.onyx.android.sdk.pen.TouchHelper`) pour la capture stylet sur les tablettes Boox. Le rendu plume direct sur le hardware EPD offre un confort d'écriture remarquable — une expérience fluide et naturelle.

- Site Onyx : [https://www.boox.com](https://www.boox.com)
- SDK OpenBridge : [https://github.com/onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo)

---

## 📄 Licence et ouverture

Le code du Miroir est publié sous **licence Apache 2.0**. Toute réutilisation ou redistribution doit respecter cette licence, conserver les mentions requises et attribuer clairement l'origine du code au projet **Miroir**.

Les dépendances tierces, notamment Google ML Kit et le SDK Onyx OpenBridge, conservent leurs propres licences.

La publication future de Panoptis, de ses poids et des données d'entraînement fera l'objet de licences libres explicites et séparées.
