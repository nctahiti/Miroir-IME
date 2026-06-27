# 📄 Format parnasse.note.v1

> Spécification du format de fichier `.note` pour les sessions d'écriture Miroir.

---

## Objectif

Le format `.note` est un fichier JSON auto-suffisant contenant une session d'écriture complète : strokes bruts, groupes de mots, transcriptions, et métadonnées. Il est conçu pour :

- **Sauvegarde/chargement** : persistance complète d'une page d'écriture
- **Dataset HTR** : paires (strokes, transcription) prêtes pour l'entraînement
- **Interopérabilité** : format ouvert, lisible, documenté
- **Anonymisation** : le contexte privé (phrase, document) n'est pas inclus — seuls les gestes et leurs transcriptions

---

## Structure

```json
{
  "format": "parnasse.note.v1",
  "device": "Boox Note Air 5C",
  "created": "2026-06-26T14:30:00+0200",
  "mode": "blocnote",
  "label": "Ma note",
  "sessionOrigin": [1234.5, 567.8],
  "words": [
    {
      "origin": [100.0, 200.0],
      "transcription": "bonjour",
      "strokes": [
        {
          "points": [
            [x1, y1, pression1, timestamp1],
            [x2, y2, pression2, timestamp2]
          ]
        }
      ]
    }
  ]
}
```

---

## Champs racine

| Champ | Type | Obligatoire | Description |
|-------|------|-------------|-------------|
| `format` | string | ✅ | Toujours `"parnasse.note.v1"` |
| `device` | string | ✅ | Modèle de la tablette (ex: `"Boox Note Air 5C"`) |
| `created` | string | ✅ | Horodatage ISO 8601 de création |
| `mode` | string | ✅ | Mode d'écriture : `"blocnote"` ou `"formulaire"` |
| `label` | string | Non | Titre ou nom de la note |
| `sessionOrigin` | [float, float] | ✅ | Position absolue du premier point de la session (ancrage spatial) |
| `words` | array | ✅ | Liste des groupes de mots (voir ci-dessous) |

---

## Structure d'un mot (`words[i]`)

| Champ | Type | Obligatoire | Description |
|-------|------|-------------|-------------|
| `origin` | [float, float] | ✅ | Position absolue de l'origine du groupe (ancrage spatial du mot) |
| `transcription` | string | Non | Transcription ML Kit du mot (peut être vide si non inféré) |
| `strokes` | array | ✅ | Liste des strokes composant ce mot |

### Stroke (`words[i].strokes[j]`)

| Champ | Type | Description |
|-------|------|-------------|
| `points` | array de tableaux | Chaque point = `[x, y, pression, timestamp]` |

**Coordonnées** : les `x` et `y` sont des coordonnées **relatives au `sessionOrigin` + `origin` du mot**. Pour obtenir les coordonnées absolues :

```
abs_x = sessionOrigin.x + word.origin.x + point.x
abs_y = sessionOrigin.y + word.origin.y + point.y
```

**Pression** : `float` entre 0.0 et 1.0.

**Timestamp** : `long` en millisecondes (epoch).

---

## Exemple complet

```json
{
  "format": "parnasse.note.v1",
  "device": "Boox Note Air 5C",
  "created": "2026-06-26T15:00:00+0200",
  "mode": "blocnote",
  "label": "Poème du soir",
  "sessionOrigin": [850.0, 1200.0],
  "words": [
    {
      "origin": [50.0, 80.0],
      "transcription": "la",
      "strokes": [
        {
          "points": [
            [10.5, 20.3, 0.45, 1782400000000],
            [15.2, 18.7, 0.52, 1782400000020],
            [22.8, 16.1, 0.48, 1782400000040]
          ]
        },
        {
          "points": [
            [30.1, 14.5, 0.55, 1782400000200],
            [35.4, 15.2, 0.50, 1782400000220]
          ]
        }
      ]
    },
    {
      "origin": [120.0, 75.0],
      "transcription": "mer",
      "strokes": [
        {
          "points": [
            [5.0, 10.0, 0.40, 1782400001000],
            [12.3, 8.5, 0.47, 1782400001020],
            [20.1, 9.2, 0.43, 1782400001040],
            [28.7, 11.0, 0.38, 1782400001060]
          ]
        }
      ]
    }
  ]
}
```

---

## Utilisation pour le dataset HTR

Chaque entrée `words[i]` constitue une **paire d'entraînement** :

```
ENTRÉE  : strokes (points x,y,p,t) — le geste brut
SORTIE  : transcription — le mot corrigé
```

### Ce qui est inclus
- ✅ Les strokes bruts (positions, pression, timestamps)
- ✅ La transcription (corrigée manuellement ou inférée)
- ✅ L'origine spatiale du mot

### Ce qui est exclu (anonymisation)
- ❌ Le contexte : phrase complète, document source
- ❌ L'identité du scripteur (pas de nom, pas d'ID utilisateur)
- ❌ Le contenu des autres applications

---

## Compatibilité

Le format `.note` est conçu pour être :

- **Lisible** : JSON standard, tout langage peut le parser
- **Versionné** : `"parnasse.note.v1"` permet des évolutions futures
- **Compact** : un mot de 5 strokes de 20 points ≈ 2-3 Ko
- **Concaténable** : plusieurs fichiers `.note` peuvent être fusionnés en un dataset d'entraînement

---

*Format ouvert. Spécification parnasse.note.v1.*
