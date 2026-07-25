# 🌊 Base de données libre — Dataset HTR communautaire

> La boucle symbiotique : écrire → corriger → donner → le modèle s'améliore pour tous.

---

## Vision

Le dataset Miroir est une **base de données de paires d'écriture manuscrite** (strokes → transcription), collectée passivement pendant l'utilisation normale du clavier Miroir, et **ouverte à tous** sous licence ODbL.

```
┌──────────────────────────────────────────────────────┐
│                   LA BOUCLE SYMBIOTIQUE               │
│                                                      │
│   ÉCRIRE ──→ Miroir transcrit ──→ CORRIGER           │
│      ↑                                  │            │
│      │                                  ↓            │
│      └─── Modèle amélioré ←── PAIRES ANNOTÉES        │
│                                      │               │
│                                      ↓               │
│                              DATASET ODbL             │
│                           (commons perpétuelle)       │
└──────────────────────────────────────────────────────┘
```

---

## Pourquoi un dataset libre ?

### Le problème
Les datasets d'écriture manuscrite existants sont :
- **Fermés** : propriétaires, chers, difficiles d'accès
- **Artificiels** : collectés en laboratoire, pas en conditions réelles
- **Petits** : quelques centaines de scripteurs maximum
- **Peu divers** : souvent un seul type de stylet, un seul device

### La solution Miroir
- **Ouvert** : ODbL, tout le monde peut l'utiliser et le réutiliser
- **Naturel** : collecté en conditions réelles d'écriture (pas de consigne « écrivez ce mot »)
- **Massif** : chaque correction enrichit le dataset, chaque nouveau scripteur apporte sa topologie
- **Divers** : styles d'écriture, devices, langues, contextes d'usage variés

---

## Ce que contient une paire

```
┌─────────────────────────────────────────┐
│  ENTRÉE                                 │
│  ┌───────────────────────────────────┐  │
│  │ Strokes bruts                     │  │
│  │ • Positions (x, y)                │  │
│  │ • Pression (0..1)                 │  │
│  │ • Timestamps (ms)                 │  │
│  │ • Ordre des points                │  │
│  └───────────────────────────────────┘  │
│                                         │
│  SORTIE                                 │
│  ┌───────────────────────────────────┐  │
│  │ Transcription corrigée            │  │
│  │ • Le mot tel que validé par       │  │
│  │   l'utilisateur                   │  │
│  └───────────────────────────────────┘  │
│                                         │
│  MÉTADONNÉES                            │
│  ┌───────────────────────────────────┐  │
│  │ • Device, résolution              │  │
│  │ • Timestamp (pour split train/test)│  │
│  │ • Origine spatiale du mot         │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
```

---

## Anonymisation

**Le dataset ne contient AUCUNE information privée :**

| Information | Dans le dataset ? | Justification |
|-------------|-------------------|---------------|
| Strokes (x,y,p,t) | ✅ Oui | C'est le geste pur — aucune information identifiable |
| Transcription (mot) | ✅ Oui | Un mot isolé, pas une phrase |
| Phrase complète | ❌ Non | Le contexte pourrait révéler du contenu privé |
| Document source | ❌ Non | Reste local |
| Nom du scripteur | ❌ Non | Aucun identifiant personnel |
| Application utilisée | ❌ Non | WhatsApp, Notes, etc. — non collecté |
| Horodatage précis | ❌ Non | Seule la date (pas l'heure exacte) |

**Principe** : on sépare le geste (donnée publique, aucun sens privé) du contexte (donnée privée, reste locale).

---

## Validation des paires

Pour garantir la qualité du dataset, seules les paires **corrigées manuellement** sont intégrées :

1. Miroir transcrit un mot → l'utilisateur voit la transcription
2. Si elle est incorrecte → l'utilisateur corrige via le geste de correction (swipe ↑)
3. La paire (strokes bruts, transcription corrigée) est marquée comme **validée**
4. Les paires non corrigées (confiance ML Kit seule) sont stockées séparément avec un flag de confiance

**Mécanisme de correction intégré à l'interface de capture** :
- Clic long + glissé vers le haut sur un mot
- Affichage du mot en grand, lettre par lettre
- Écriture de la correction par-dessus
- Validation par geste

---

## Architecture fédérée

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Scripteur│    │ Scripteur│    │ Scripteur│
│    A     │    │    B     │    │    C     │
│  (local) │    │  (local) │    │  (local) │
└────┬─────┘    └────┬─────┘    └────┬─────┘
     │               │               │
     │  paires       │  paires       │  paires
     │  anonymisées  │  anonymisées  │  anonymisées
     │               │               │
     ▼               ▼               ▼
┌─────────────────────────────────────────────┐
│           DATASET COMMUN (ODbL)              │
│                                             │
│  Stockage : Zenodo / Kaggle / HuggingFace   │
│  Format  : parnasse.note.v1                 │
│  Licence : ODbL                             │
└─────────────────────────────────────────────┘
     │
     │  téléchargement public
     ▼
┌─────────────────────────────────────────────┐
│  Chercheurs, développeurs, communauté        │
│  → Entraînent leurs propres modèles HTR     │
│  → Publient leurs résultats, benchmarks     │
│  → Le meilleur modèle profite à tous        │
└─────────────────────────────────────────────┘
```

---

## Utilisations du dataset

- **Entraînement HTR** (Handwritten Text Recognition) : modèles CTC, Seq2Seq, Transformer
- **Fine-tuning** : adapter un modèle générique à un style ou une langue
- **Recherche** : études sur la variabilité de l'écriture, la fatigue, l'ergonomie
- **Benchmarks** : compétitions ouvertes de reconnaissance d'écriture
- **Accessibilité** : améliorer la reconnaissance pour les personnes dysgraphiques

---

## Comment contribuer

1. **Utiliser Miroir** comme clavier quotidien
2. **Corriger** les transcriptions incorrectes (le geste de correction)
3. **Partager** ses paires anonymisées vers le dataset commun
4. **Optionnel** : taguer la langue, le style (cursif/script), la main (droite/gauche)

---

## Licence du dataset

**[ODbL v1.0](https://opendatacommons.org/licenses/odbl/)** — Open Database License.

- ✅ Vous êtes libre de partager, créer, adapter la base de données
- ✅ Usage commercial autorisé
- ⚠️ Toute base de données dérivée doit rester sous ODbL (Share-Alike)
- ⚠️ Attribution obligatoire
- ✅ Pas de contagion sur le code qui utilise la base (contrairement à la GPL)

**Précédent** : OpenStreetMap — la plus grande base de données cartographique au monde, même licence, même philosophie.

---

*« Un seul scripteur — même avec des milliers de paires — a une coupure physique profonde. Son cursif a une topologie propre. Le dataset commun est le seul chemin vers un modèle qui comprend l'écriture, pas un scripteur. »* — Nicolas
