# MDM — MarkDownMiroir

Moteur de composition spatiale pour le clavier Miroir IME.
Langage de balisage léger pour placer texte, écriture cursive, cadres et tableaux dans une page.

## Usage

```bash
python mdm.py
# → mdm_tableau.svg, mdm_texte_cadre.svg
```

```python
from mdm import compile
svg = compile(source, ["strokes_mer.json", "strokes_bonjour.json"])
```

## Syntaxe

| Symbole | Concept |
|---------|---------|
| `<* *>` | Justifier |
| `|* *|` | Centrer |
| `>* *>` | Aligner droite |
| `(* *)` | Grouper |
| `[* *]` | Cadre |
| `;*` | Colonne |
| `@mot` | Dessin cursif |

## Exemple

```mdm
|* Produit *| ;* |* Quantité *| ;* |* Prix *|
<* @bonjour *> ;* |* 3 *| ;* >* 12.5€ *>
```

## Fichiers

- `mdm.py` — Moteur de rendu (parseur + layout + SVG)
- `MIRML_SPEC.md` — Spécification du langage
- `strokes_mer.json` — Données d'écriture « mer »
- `strokes_bonjour.json` — Données d'écriture « bonjour »
