# Homogénéisation Miroir — Architecture cible

## Métaphore du livre ouvert

```
┌──────────────────────────────────────────────────┐
│ Couverture (gauche/haut)                         │
│ ┌────────────┬──────────────────────────────────┐│
│ │ 📚 Biblio   │                                  ││
│ │ 📝 Bloc     │     SURFACE DE CAPTURE           ││
│ │ ✚ Page      │     (Miroir overlay)             ││
│ │ 👁 Toggle   │     ── interligne ──────────     ││
│ │             │     ── interligne ──────────     ││
│ │             │                                  ││
│ │             │                                  ││
│ ├─────────────┤                                  ││
│ │ ⚙ (long)    │                                  ││
│ └─────────────┴──────────────────────────────────┘│
│                     Bord droit (coupé)            │
│                     Bord bas   (coupé)            │
└──────────────────────────────────────────────────┘
```

## Deux vues

| Vue | Mode | Rôle |
|-----|------|------|
| **bloc** | Capture (Miroir) | Écriture manuscrite, template, groupes |
| **note** | Formatage (Markdown) | Texte transcrit, mise en forme, édition |

## Objectifs

1. Dupliquer les fonctions IME → standalone CaptureActivity
2. Template, multi-pages, save/load V★, MDM
3. Boutons synchronisés avec Flutter (📚 📝 ✚ 👁 ⚙)
4. Screenshot Flutter comme fond de capture
5. Navigation propagée Miroir → Flutter
