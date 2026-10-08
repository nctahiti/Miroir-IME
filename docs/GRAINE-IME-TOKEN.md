# GRAINE-IME-TOKEN — la reconstruction de la coquille IME

## La fonction

La coquille IME ne fait qu'une chose : faire **naître** le token d'intention.

- `onStartInput` → le token naît **CHAUD** (le champ a le focus)
- `onFinishInput` → le sceau tombe, le token est **FIGÉ**
- Charge utile : `{texte, contexte}` — rien d'autre. Pas de rattachement, pas
  d'inférence pendant le geste (la machine se tait) ; les arêtes se tissent
  après coup, par les trois axes (temps, contexte, intention).
- Le contexte = la clé de champ que l'ancienne coquille savait déjà construire
  (`buildFieldKey` : packageName, fieldId, fieldName) — l'identité du lieu de
  naissance.

## La contrainte de nature

- L'IME ne contrôle pas le DU — `setDisplayScheme`/`enterScribbleMode` sont
  ignorés en contexte `InputMethodService` ; c'est l'app hôte qui possède le
  mode d'affichage.
- La coquille est **EPD-agnostique** : la qualité d'affichage n'est pas
  essentielle. La fontaine ne se règle pas — elle **s'active et se désactive**,
  c'est tout (`FontaineCommande`).

## La transmutation (une seule source de vérité)

- **Ce qui revient au moteur** (un seul exemplaire) : `rebuildBitmap`
  (CaptureView), `computeBlobPath` (MiroirEngine), la fonction diff LCS
  nominal/courant.
- **Ce qui reste à la coquille** (ré-exprimé sur le moteur, jamais copié) :
  les états gestuels B/C (scrub, move), l'absorption dans la fenêtre IME,
  les timers de sélection.
- **Ce qui meurt** : les copies portées par l'ancien `MiroirIME.kt`
  (5 403 lignes). La coquille finale = quelques centaines de lignes.

## L'épreuve

La coquille nouvelle reproduit les gestes B/C sur le moteur partagé, sans
`rebuildBitmap` ni `computeBlobPath` à elle. Zéro duplication : le grep des
fonctions dupliquées doit être vide.

## Le bouton

Quand le token existe, le bouton de l'IME ouvre le graphe contextuel du token
(parents, enfants, transitif) — comme la cerise dans le standalone.
Symétrie des organes : le standalone fait naître des tokens-pages sur l'e-ink,
l'IME fait naître des tokens-champs partout dans Android.
