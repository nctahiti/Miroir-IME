# 🖥️ Inventaire EPD — Miroir IME

> « Pour permettre les déplacements et les modifications. »
> 22 juin 2026 — dressé avec Nicolas

## La dalle et ses modes

| Mode | Latence | Rendu | Usage |
|------|---------|-------|-------|
| **DU** (Direct Update) | ~16ms | Noir/blanc pur, pas d'anti-aliasing | Tracé stylet |
| **GU** (Global Update) | ~300ms | Tous niveaux de gris, anti-aliasing, texte | Overlays, lecture |
| **REGAL** | ~120ms | Texte optimisé, compromis | Labels, modifications |
| **AUTO** | Variable | Le système choisit | — |

## Les outils EPD (Onyx SDK)

| Outil | Effet | Persistant ? |
|-------|-------|-------------|
| `setViewDefaultUpdateMode(v, mode)` | Définit le mode par défaut de la vue | Oui |
| `handwritingRepaint(v, l, t, r, b)` | Rafraîchit une zone pour le tracé stylet | Ponctuel |
| `refreshScreen(v, mode)` | Rafraîchit tout l'écran avec un mode | Ponctuel |
| `setScreenHandWritingPenState(v, 1/0)` | Active/désactive le mode handwriting | Oui |
| `enablePost(v, 1/0)` | Active/désactive le post-traitement | Oui |
| `postInvalidate()` | Android — planifie un onDraw | Ponctuel |

## Ce qu'on a aujourd'hui

```
┌─────────────────────────────────────────────────────┐
│  INIT (initTouchHelper)                              │
│  ├─ setViewDefaultUpdateMode(DU)  ← mode par défaut │
│  ├─ setScreenHandWritingPenState(1)                 │
│  └─ enablePost(0)                                   │
│                                                      │
│  ÉCRITURE (isStroke=true)                            │
│  └─ handwritingRepaint(v, l, t, r, b)               │
│                                                      │
│  LABEL / TEMPLATE (isStroke=false)                   │
│  └─ refreshScreen(GU)  ← ~300ms, ponctuel           │
│                                                      │
│  FERMETURE (releaseTouchHelper)                      │
│  ├─ setViewDefaultUpdateMode(GU)                     │
│  ├─ enablePost(1)                                   │
│  └─ setScreenHandWritingPenState(0)                 │
└─────────────────────────────────────────────────────┘
```

## Ce qu'il manque

### 1. Mode ÉDITION (déplacements, modifications)

Quand on veut déplacer un mot ou modifier, il faut **voir** les overlays
(labels, blob de sélection). Le mode DU ne les rend pas → il faut basculer
temporairement en GU ou REGAL.

```kotlin
fun enterEditMode() {
    EpdController.setViewDefaultUpdateMode(view, UpdateMode.GU)  // ou REGAL
    EpdController.enablePost(view, 1)
    refreshAll()  // force le redraw avec tous les overlays visibles
}

fun exitEditMode() {
    EpdController.setViewDefaultUpdateMode(view, UpdateMode.DU)
    EpdController.enablePost(view, 0)
    // Le tracé redevient fluide à 16ms
}
```

### 2. Activation/Désactivation du mode DU

Actuellement, le mode DU est activé au lancement et désactivé à la fermeture.
Il faut pouvoir le basculer pendant la session :
- **DU ON** → écriture fluide, overlays invisibles
- **DU OFF (GU/REGAL)** → overlays visibles, écriture plus lente

Le basculement pourrait être lié au mode Miroir :
- `CAPTURE` → DU (écriture)
- `EDIT` → GU/REGAL (modification)

### 3. Interactions tactiles (boutons)

`setPostInputEvent(true)` bloque tout → à migrer vers `RawInputCallback`
pour libérer les événements tactiles et permettre l'accès aux boutons.

## Plan d'action

1. ✅ Template visible au premier chargement (refreshAll dans root.post)
2. ⬜ Implémenter enterEditMode / exitEditMode (bascule DU ↔ REGAL)
3. ⬜ Lier le mode EPD au mode Miroir (CAPTURE=DU, EDIT=REGAL)
4. ⬜ Migrer TouchHelper : RawInputCallback au lieu de setPostInputEvent
5. ⬜ Optimiser les labels : refreshScreen(REGAL) au lieu de GU (2x plus rapide)
