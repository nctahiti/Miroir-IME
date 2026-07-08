# Journal de bord — 8 juillet 2026

## Cap MDM — MarkDownMiroir

- Langage de composition spatiale : 7 symboles, zéro mot anglais
- `<* *>` `|* *|` `>* *>` `(* *)` `[* *]` `;*` `@mot`
- Moteur Python (250 lignes) + parseur Kotlin (MdmParser.kt)
- Dépôt : https://github.com/nctahiti/mdm (Unlicense)

## Template Miroir IME

- Fix rafraîchissement immédiat (onStartInputView)
- Re-snap ancres, strokes, blobs au changement d'interligne
- Offset relatif préservé (le mot garde sa distance à la ligne)

## MDM dans le Miroir

- savePageMdm() : export naturel par interligne avec en-tête dimensions
- loadPageMdm() : lecture non-destructive
- applyMdmLayout() : repositionne Y (préserve X) + génère strokes manquants
- StrokeRecord.source = "llm" — protège de l'inférence et du recyclage
- BroadcastReceiver ACTION_MDM_APPLY

## API Cœur MDM

- GET /api/miroir/mdm/:blockId/page/:n → lit page.mdm
- PUT /api/miroir/mdm/:blockId/page/:n → écrit page.mdm
- POST .../apply → broadcast → applyMdmLayout()
- ADB réel (adbShell, adbExec)
- Hello World testé bout en bout ✅

## Homogénéisation Miroir

- MiroirEngine.kt : moteur partagé IME/standalone
- CaptureActivity branchée sur le moteur (blocs, groupes, pages)
- Ancien système blocNoteFiles/noleDir supprimé
- Branche homogeneisation-miroir créée

## Dépôts

| Dépôt | Branche | Commits |
|-------|---------|---------|
| nctahiti/Miroir-IME | recyclage-dataset-odbl | template + MDM |
| nctahiti/Miroir-IME | mdm-integration | MDM (mergée) |
| nctahiti/Miroir-IME | homogeneisation-miroir | moteur partagé |
| nctahiti/Le-parnasse | dev-test | API MDM Cœur |
| nctahiti/mdm | master | spec + moteur Python |
