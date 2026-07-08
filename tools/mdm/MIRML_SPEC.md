# MirML — Miroir Markup Language

Spécification v0.1 — Un langage de composition spatiale simple,
lisible à l'œil nu, enrichi par strates au-dessus du Markdown.

---

## RÈGLE FONDAMENTALE

Toute syntaxe MirML valide est aussi du **texte lisible**.
Un fichier .mirml ouvert dans le Bloc-Notes doit avoir du sens.
Le rendu enrichit, il ne remplace pas.

---

## TYPES DE BALISES

Il y a trois catégories, distinguées par leur portée :

| Catégorie | Syntaxe | Portée | Fermeture |
|-----------|---------|--------|-----------|
| **Ligne** | `##`, `#`, `---` | La ligne courante | Automatique (fin de ligne) |
| **Bloc** | `**`, `__`, `~~`, `{vec}` | Du début à la fermeture | Obligatoire `**/`, `{vec/}` |
| **Flux** | `{<}`, `{>}`, `{|}` | Tous les objets suivants | Fermé par le prochain flux |

---

## 1. BALISES DE LIGNE

Portée : la ligne uniquement. Auto-fermantes au `\n`.

```
# Titre niveau 1
## Titre niveau 2
### Titre niveau 3

---   ← ligne horizontale

* item liste
- item liste
1. liste numérotée
```

Aucune fermeture nécessaire. La ligne suivante est hors de portée.

---

## 2. BALISES DE BLOC

Portée : de l'ouverture `{tag}` à la fermeture `{tag/}`.
**Peuvent s'imbriquer** — l'ordre de fermeture doit être inverse de l'ouverture.

### 2.1 Style inline (héritées du Markdown)

```
**gras**        → portée : texte, peut contenir *italic*
__italique__    → portée : texte, peut contenir **gras**
~~barré~~
`code`
```

### 2.2 Vecteur — `{vec}`

Un objet vectoriel avec position, dimensions et contenu.

```
{vec AlDroite H:haut V:centre w=120 h=80}
  contenu...
{vec/}
```

Propriétés :
| Propriété | Valeurs | Défaut | Description |
|-----------|---------|--------|-------------|
| `Al` | Gauche, Droite, Centre, Libre | Libre | Alignement horizontal dans le parent |
| `H` | gauche, centre, droite | gauche | Ancre horizontale de la bbox |
| `V` | haut, centre, bas | haut | Ancre verticale de la bbox |
| `w` | nombre (px) | auto | Largeur de la boîte |
| `h` | nombre (px) | auto | Hauteur de la boîte |
| `x`, `y` | nombre (px) | — | Position absolue (si Al=Libre) |

### 2.3 Image — `{img}`

```
{img src="photo.jpg" AlGauche w=200}
{img/}
```

### 2.4 Formes — `{rect}`, `{circle}`

```
{rect w=200 h=100 fill="#f0f0f0" stroke="#ccc" round=8}
  {|}
    **Titre** dans la boîte
    texte centré
  {|/}
{rect/}

{circle r=80 H:centre V:centre}
  @bonjour
{circle/}
```

### 2.5 Mot manuscrit — `@`

Raccourci pour insérer un mot généré par le modèle d'écriture.

```
@bonjour
@capitaine style=7 bias=0.75
```

Propriétés : `style` (1-9), `bias` (0.15-2.5), `speed` (0.6-9.5)

---

## 3. BALISES DE FLUX

Portée : **tous les objets suivants** jusqu'au prochain flux.
**Non cumulatives** — poser un nouveau flux ferme le précédent.

```
{<}   → Aligner à gauche (propagé)
{|}   → Centrer (propagé)
{>}   → Aligner à droite (propagé)
```

Exemple :
```markdown
{<}
Lorem ipsum dolor sit amet, consectetur adipiscing elit.
Cette ligne est à gauche.
{|}
Ce texte est centré.
Il continue centré.
{>}
Et celui-ci à droite.
```

Pas besoin de `{</}` — le `{|}` ferme implicitement le `{<}`.

### 3.1 Flux + objet flottant

Quand un `{vec}` a un alignement (`AlGauche` ou `AlDroite`), il **flotte**
dans le flux de texte :

```markdown
{<}
Lorem ipsum dolor sit amet, consectetur adipiscing elit.
Sed do eiusmod tempor incididunt ut labore et dolore magna.

{vec AlDroite w=150}
  @bonjour
  @capitaine
{vec/}

Ut enim ad minim veniam, quis nostrud exercitation ullamco
laboris nisi ut aliquip ex ea commodo consequat.
```

Le texte coule à gauche de l'objet. L'objet occupe 150px à droite.
Le texte reprend toute la largeur après l'objet.

---

## 4. RÈGLES D'IMBRICATION

```
OUI  : {vec...} **gras *italique* fin** {vec/}
OUI  : {rect...} {circle...} {circle/} {rect/}
OUI  : {<} Lorem **gras** ipsum {vec...} @mot {vec/} dolor

NON  : **gras {vec...} pas fin** {vec/}  ← déséquilibré
NON  : {<} {>}                            ← pas deux flux simultanés
```

---

## 5. EXEMPLE COMPLET

```markdown
{|}
# Exercice d'écriture
{=/}

{<}
## Consigne

Recopier les mots suivants dans les cadres.
Faire attention à la liaison du **j** et du **b**.

{=/}

{>}
*Capitaine — 8 juillet 2026*
{>/}

---

{<}
@bonjour style=1
@capitaine style=7
@comment
@allez
@vous

{vec AlDroite w=200 H:centre V:centre}
  {rect w=180 h=100 fill="#fff8f0" stroke="#daa" round=8}
    {|}
    **Mot du jour**
    @bonjour style=4
    {|/}
  {rect/}
{vec/}
```

### Ce que ça produit

```
                Exercice d'écriture

Consigne
Recopier les mots suivants dans les cadres.
Faire attention à la liaison du j et du b.

                         Capitaine — 8 juillet 2026

─────────────────────────────────────────────

bonjour  capitaine  comment  allez  vous      ┌──────────────────┐
                                               │   Mot du jour    │
                                               │    bonjour       │
                                               └──────────────────┘
```

---

## 6. GRAMMAIRE FORMELLE (EBNF)

```ebnf
document    = { block | line } ;

line        = line_tag text "\n" ;
line_tag    = "#" | "##" | "###" | "---" | "* " | "- " | "1. " ;

block       = style_inline | vec_block | img_block | form_block | word_token ;
style_inline = "**" content "**/" | "__" content "__/" | "~~" content "~~/" ;

vec_block   = "{vec" properties "}" content "{vec/}" ;
img_block   = "{img" properties "}" "{img/}" ;
form_block  = "{rect" properties "}" content "{rect/}" 
            | "{circle" properties "}" content "{circle/}" ;
word_token  = "@" word [properties] ;

flow        = "{<}" | "{|}" | "{>}" ;
properties  = { key "=" value } ;
key         = identifier ;
value       = number | string | "Gauche" | "Droite" | "Centre" | "haut" | "bas" ;
```

---

## 7. PROPRIÉTÉS DU LANGAGE

| Propriété | Valeur |
|-----------|--------|
| **Lisible sans rendu** | ✓ — tout fichier .mirml est du texte |
| **Parsable sans ambiguïté** | ✓ — grammaire régulière |
| **LLM-générable** | ✓ — balises explicites, vocabulaire limité |
| **Éditable au clavier** | ✓ — pas de guillemets échappés, pas de chevrons |
| **Extensions futures** | ✓ — nouvelles balises sans casser l'existant |
| **Indépendant du device** | ✓ — mêmes coordonnées logiques partout |
