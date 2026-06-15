# Manifeste d'archivage — 15 juin 2026

## Code déprécié dans le WIP (flutter/dev-code/miroir)

Ces fichiers du refactor WIP font doublon avec le V3 ou sont des stubs :

### Doublons de capture
| Fichier WIP | Remplacé par (V3) | Raison |
|-------------|-------------------|--------|
| `MiroirPipeline.kt` | `CaptureView.kt` (onTouch pipeline) | Même responsabilité : orchestrer capture → rendu → stockage |
| `MiroirStrokeCapture.kt` | `StrokeProcessor.kt` + `CaptureView` | Capture vectorielle delta — le V3 le fait déjà dans `onTouchEvent` |

### Doublons de rendu
| Fichier WIP | Remplacé par (V3) | Raison |
|-------------|-------------------|--------|
| `InkStrokeRenderer.kt` (446 lignes) | `StrokeRenderer.kt` (287 lignes) | Rendu de strokes — le V3 a une version plus légère et testée |

### Stubs / Non branchés
| Fichier WIP | Statut | Notes |
|-------------|--------|-------|
| `RasterizedTranscriber.kt` | Stub OCR | `DigitalInkWrapper.kt` (V3) est la solution réelle |
| `StrokeTranscriber.kt` | Interface vide | Jamais implémentée — `TranscriptionWriter.kt` (V3) fait le travail |
| `CoeurHttpClient.kt` | Non testé | Communication Cœur — à réactiver quand le pipeline est stable |

### Divergences
| Fichier | V3 | WIP | Action |
|---------|-----|-----|--------|
| `CalibrationActivity.kt` | 263 lignes | 333 lignes | Fusionner les ajouts WIP (BlobParams, TouchHelper) dans la V3 |

## Code à CONSERVER du WIP (migrer vers V3)

Ces fichiers du WIP n'ont PAS d'équivalent dans le V3 et apportent une meilleure architecture :

| Fichier WIP | Valeur | Priorité |
|-------------|--------|----------|
| `GroupStateMachine.kt` | États explicites ACTIVE/PENDING/CLOSED/EXPORTED | 🔴 |
| `GroupManager.kt` | Orchestrateur de groupes avec timeout | 🔴 |
| `BlobAbsorber.kt` | Algorithme d'absorption paramétré | 🔴 |
| `BlobParams.kt` | Paramètres de calibration persistés | 🟡 |
| `InkGroup.kt` | Modèle de données groupe (vs implicite dans V3) | 🔴 |
| `MiroirParamRegistry.kt` | Registre de paramètres observable | 🟡 |
| `ParnasseInkService.kt` | Service Android avec overlay système | 🟡 |
| `MiroirSessionManager.kt` | Gestionnaire de sessions | 🟡 |
| `InkCaptureEngine.kt` | Moteur de capture MotionEvent → InkStroke | 🟡 |
| `MiroirView.kt` | Rendu Canvas double-couche | 🟡 |

## Fichiers V3 (intouchables — source de vérité)

| Fichier V3 | Rôle |
|------------|------|
| `CaptureView.kt` (2810 lignes) | Cœur monolithique — à désépaissir progressivement |
| `CaptureActivity.kt` | UI, navigation, boutons |
| `DigitalInkWrapper.kt` | Reconnaissance ML Kit Digital Ink |
| `VStarWriter.kt` | Persistance VStar bufferisée |
| `TranscriptionWriter.kt` | Déduplication + écriture transcriptions |
| `StrokeRenderer.kt` | Rendu vectoriel optimisé |
| `StrokeProcessor.kt` | Post-processing strokes |
| `StrokeData.kt` / `StrokeRecord.kt` | Modèles de données |
| `VStarDocument.kt` | Format .note/.vstar |
