# Témoin de boîte aux lettres — principe réutilisable

## Patron

```
┌─ PRODUCTEUR ─┐                    ┌─ CONSOMMATEUR ─┐
│              │                    │                 │
│  Écrit data   │                   │  Lit .temoin    │
│  Écrit .temoin│─── médium ───────→│  Compare releve │
│  (timestamp)  │   (SD card,       │  Si temoin >    │
│               │    fichier,        │    releve →     │
│               │    queue...)       │    process      │
│               │                   │  Écrit .releve   │
└───────────────┘                   └─────────────────┘
```

## Règle unique

```
SI .temoin > releve  →  data a changé → process + mettre à jour releve
SI .temoin ≤ releve  →  déjà traité   → skip
SI releve ABSENT     →  première fois → process + inscrire releve
```

## Propriétés

| Propriété | Garantie |
|-----------|----------|
| **Idempotence** | Rejouer N fois → même résultat (timestamps inchangés) |
| **Pas de faux positif** | Seul le producteur écrit `.temoin` |
| **Pas de lecture contenu** | Une comparaison de timestamps, pas de hash/parsing |
| **Traçabilité** | `releve` dans les métadonnées → on sait quand chaque item a été vu |
| **Débogage** | `SELECT * WHERE temoin > releve` → tous les items en retard |
| **Résilience** | Si `.temoin` absent → fallback (lire le contenu, ou créer le témoin) |

## Déclinaisons dans le Parnasse

| Flux | Producteur | Consommateur | Médium | Fichier témoin |
|------|-----------|-------------|--------|---------------|
| **Miroir standalone → Parnasse** | Miroir (`mirrorToSdcard`) | Cœur (`miroirSync`) | SD card | `.miroir_temoin` |
| **Jardinier → labour** | Création/update note | Jardinier | Metadata note | `metadata["jardinier_releve"]` |
| **Indexeur RAG** | Update note | Scanner/Indexeur | Metadata note | `metadata["indexeur_releve"]` |
| **Miroir IME → Cœur** | Miroir IME | Cœur (`miroirPush`) | Queue JSON | `.miroir_queue_temoin` |
| **Fourmis → Parnasse** | Fourmis (observation) | Cœur (greffe) | Journal JSONL | `metadata["fourmis_releve"]` |

## Implémentation minimale

### Producteur (Kotlin)
```kotlin
File(dir, ".miroir_temoin").writeText(Instant.now().toString())
```

### Consommateur (Go)
```go
temoinBytes, _ := os.ReadFile(temoinPath)
temoinTime, _ := time.Parse(time.RFC3339, string(temoinBytes))

releveStr, _ := note.Metadata["miroir_releve"].(string)
releveTime, _ := time.Parse(time.RFC3339, releveStr)

if temoinTime.After(releveTime) {
    // process + update metadata
    note.Metadata["miroir_releve"] = temoinTime.Format(time.RFC3339)
}
```

## Ce que ça remplace

| Avant (sans témoin) | Après (avec témoin) |
|---------------------|---------------------|
| Comparer le contenu entier | Comparer un timestamp |
| Hash du fichier | Lecture 20 octets |
| Faux positifs (formatting) | Zéro faux positif |
| Skip silencieux ou erreur | Log clair : `updated / skipped (unchanged) / created` |
| Déduplication par titre = perte de mises à jour | Upsert par témoin = toujours à jour |
