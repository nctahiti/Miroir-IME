# Miroir IME — Architecture

> « Le Miroir est un portail d'écriture manuscrite universel. »  
> 78 commits. 3 fichiers clés. Une poésie binaire.

## 📦 Taille

| Métrique | Valeur |
|----------|--------|
| APK (debug, ML Kit inclus) | 39 Mo |
| MiroirIME.kt | 1 086 lignes |
| CalibrationActivity.kt | 271 lignes |
| GroupManager.kt | 350 lignes |
| Total Kotlin (projet) | 8 731 lignes |

## 🧬 Anatomie du MiroirIME.kt

```
┌─────────────────────────────────────────────────┐
│                 MiroirIME                        │
│  InputMethodService                              │
├─────────────────────────────────────────────────┤
│  🖊️ CAPTURE                                     │
│  ├─ TouchHelper (Onyx SDK) → onTouchEvent       │
│  ├─ onStylusDown / onStylusPoint / onStylusUp   │
│  ├─ strokeRegistry + bitmap (double buffer)     │
│  └─ isStylusDown (flag anti-inférence)          │
│                                                  │
│  👥 GROUPEMENT                                  │
│  ├─ GroupManager (spatial par blob elliptique)  │
│  ├─ BlobParams (rx, ry, timeout=∞)              │
│  └─ getOrCreateActiveGroup → absorption         │
│                                                  │
│  ⏱️ INFÉRENCE                                   │
│  ├─ scheduleGroupInference (timers/groupe)      │
│  ├─ armGroupInference (garde-fous)              │
│  ├─ inferenceQueue → processNextInference       │
│  └─ ML Kit Digital Ink → recognizeGroup         │
│                                                  │
│  🫧 BLOB (zone d'absorption)                    │
│  ├─ computeBlobPath (ray casting 90 rayons)     │
│  ├─ groupBlobs: Map<groupId, BlobData>          │
│  └─ activeBlobGroupId (sélection visuelle)      │
│                                                  │
│  🏷️ LABELS                                      │
│  ├─ groupLabels: Map<firstIdx, String>          │
│  ├─ groupAnchor: Map<firstIdx, (x,y)>           │
│  └─ drawGroupLabels (tri spatial, snapToLine)   │
│                                                  │
│  📝 INJECTION                                   │
│  ├─ injectReadingOrder (tri par interligne+x)   │
│  ├─ syncedNoteText (anti-redondance)            │
│  └─ commitText / setComposingText               │
│                                                  │
│  📏 TEMPLATE (partition)                        │
│  ├─ HorizontalStaff (lignes paramétrables)      │
│  ├─ snapToLine (70% au-dessus, 30% en dessous) │
│  └─ drawTemplate                                │
│                                                  │
│  📄 PAGES                                       │
│  ├─ savePage / loadPage / newPage               │
│  └─ persistence GroupManager                    │
│                                                  │
│  ⚙️ CALIBRATION                                 │
│  ├─ X, Y, délai, survol, rafraîchissement      │
│  ├─ Densité blob (rayons)                       │
│  └─ Interligne, épaisseur template              │
│                                                  │
│  🖥️ EPD (e-ink)                                 │
│  ├─ refreshRect (l,t,r,b) + handwritingRepaint  │
│  ├─ refreshAll (global)                         │
│  └─ DU mode (écriture) / GU mode (repos)        │
└─────────────────────────────────────────────────┘
```

## 🔄 Flux principal

```
STYLET DOWN
  │
  ├─ activeBlobGroupId = null ou détecté (touch dans blob)
  ├─ isStylusDown = true
  │
STYLET MOVE
  │
  ├─ onStylusPoint → Path + refreshRect throttlé
  │
STYLET UP
  │
  ├─ isStylusDown = false
  ├─ Rastérisation → bitmap
  ├─ GroupManager.onStrokeSealed → absorption
  ├─ scheduleGroupInference → timer armé
  ├─ refreshRect (zone stroke)
  │
TIMER (après inferDelay d'inactivité)
  │
  ├─ isStylusDown? → DEFER
  ├─ lastMod > armedAt? → SKIP
  ├─ strokeCount changé? → SKIP
  ├─ → inferenceQueue.add → startInferencePipeline
  │
RECONNAISSANCE (ML Kit)
  │
  ├─ recognizeGroup → result
  ├─ uiHandler.post:
  │   ├─ groupLabels[firstIdx] = result
  │   ├─ computeBlobPath → groupBlobs[id] = BlobData
  │   ├─ injectReadingOrder → tri spatial → commitText
  │   └─ refreshAll
```

## 🔀 Circuits d'inférence

Le Miroir utilise **deux circuits de reconnaissance parallèles**, reflet de son architecture à deux vitesses :

### ① Circuit Online — ML Kit Digital Ink (tablette, temps réel)

```
Stylet → strokes vectoriels (x,y,t) → DigitalInkWrapper → ML Kit Digital Ink → texte
                                              ↑
                              com.google.mlkit:digital-ink-recognition:18.1.0
```

- **Où :** `MiroirIME.kt` / `DigitalInkWrapper.kt` — sur la tablette Android
- **Input :** strokes natifs `(x, y, timestamp)` — pas de rasterisation
- **Modèle :** Google ML Kit Digital Ink (propriétaire, on-device, français)
- **Usage :** reconnaissance temps réel pendant l'écriture, injection IME
- **Latence :** < 100 ms après le dernier stroke

### ② Circuit Raster — EasyOCR (serveur, différé)

```
Stylet → strokes → MiroirRasterizer → JPEG 1600×2000 → La Singularité (EasyOCR) → texte
                         ↑                                         ↑
                    rasterisation                          Python/HTTP :7701
```

- **Où :** `singularite_transcriber.go` (Cœur Go) → service Python « La Singularité »
- **Input :** JPEG rasterisé (fond blanc, traits noirs, 1600×2000 px)
- **Modèle :** EasyOCR (CRAFT + CRNN, Apache 2.0, 80+ langues)
- **Usage :** transcription différée des notes synchronisées, traitement par lots
- **Latence :** 2-10 secondes selon la charge

### 🧭 Pourquoi deux circuits ?

| | Circuit ① Online | Circuit ② Raster |
|---|---|---|
| **Rôle** | Reconnaissance immédiate | Transcription server-side |
| **Fidélité** | Préserve les vecteurs natifs | Perte d'information (pression, vitesse) |
| **Indépendance** | 100% on-device, pas de réseau | Dépend du serveur La Singularité |
| **Évolutivité** | Modèle Google figé | Modèle interchangeable (Fourier, TrOCR…) |

Les deux circuits convergent vers **Panoptis** — un modèle HTR propriétaire entraîné directement sur les vecteurs V★, sans rasterisation, alimenté par le dataset ODbL.

## 🎯 Principes

1. **Le blob n'est pas l'inférence.** Le blob = zone d'absorption. Le label = témoin d'inférence. Désynchronisés.
2. **La sélection est une vue, pas un état.** `activeBlobGroupId` est visuel. Jamais `selectGroup()`.
3. **Chaque groupe a son timer.** Indépendant. Écrire B ne réarme pas A.
4. **L'ordre de lecture émerge des coordonnées.** Tri spatial (interligne, x). Poésie binaire.
5. **Pas d'éviction.** `transcriptionTimeoutMs = Long.MAX_VALUE`. Les groupes survivent.
6. **EPD : rafraîchir au pixel près.** `refreshRect`, pas `invalidate()` global.
