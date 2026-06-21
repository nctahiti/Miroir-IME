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

## 🎯 Principes

1. **Le blob n'est pas l'inférence.** Le blob = zone d'absorption. Le label = témoin d'inférence. Désynchronisés.
2. **La sélection est une vue, pas un état.** `activeBlobGroupId` est visuel. Jamais `selectGroup()`.
3. **Chaque groupe a son timer.** Indépendant. Écrire B ne réarme pas A.
4. **L'ordre de lecture émerge des coordonnées.** Tri spatial (interligne, x). Poésie binaire.
5. **Pas d'éviction.** `transcriptionTimeoutMs = Long.MAX_VALUE`. Les groupes survivent.
6. **EPD : rafraîchir au pixel près.** `refreshRect`, pas `invalidate()` global.
