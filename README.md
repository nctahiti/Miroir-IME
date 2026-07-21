# 🪞 Miroir — IME & Surface de capture manuscrite

Miroir est l'IME (Input Method Editor) et la surface de capture manuscrite du Parnasse Numérique. Il capture les strokes du stylet, les groupe spatialement par blobs d'absorption, les reconnaît via ML Kit Digital Ink, et les transmet vers le Cœur Parnasse.

## 📦 Composants

| Composant | Fichier | Rôle |
|-----------|---------|------|
| **MiroirIME** | `MiroirIME.kt` | IME système — clavier manuscrit contextuel |
| **CaptureActivity** | `CaptureActivity.kt` | Surface standalone — écriture libre |
| **FontaineOverlay** | `FontaineOverlay.kt` | Rendu plume temps réel (SurfaceView + TouchHelper) |
| **MiroirEngine** | `MiroirEngine.kt` | Moteur partagé — strokes, groupes, V★, bitmap |
| **GroupManager** | `GroupManager.kt` | Groupement spatial par blobs elliptiques |
| **VStarDataRegion** | `VStarDataRegion.kt` | Stockage V★ v2.0 (16B/token) |
| **MdmParser** | `MdmParser.kt` | MarkDownMiroir — ancres spatiales |
| **CalibrationActivity** | `CalibrationActivity.kt` | Réglages blob, timers, template |

## 🙏 Remerciements

Ce projet utilise le **SDK Onyx OpenBridge** (`com.onyx.android.sdk.pen.TouchHelper`) pour la capture stylet en mode `STROKE_STYLE_FOUNTAIN` sur les tablettes Boox.

Le TouchHelper et son API RawInputCallback nous ont permis d'atteindre une latence de capture inférieure à 16ms, avec un rendu plume direct sur le hardware EPD — une prouesse sans laquelle l'expérience d'écriture du Miroir ne serait pas ce qu'elle est.

Merci à l'équipe Onyx pour cet outil remarquable.

- Site Onyx : [https://www.boox.com](https://www.boox.com)
- SDK OpenBridge : [https://github.com/onyx-intl/OnyxAndroidDemo](https://github.com/onyx-intl/OnyxAndroidDemo)

## 🏗️ Build

```bash
./gradlew assembleDebug
```

APK de sortie : `app/build/outputs/apk/debug/app-debug.apk`

## 📄 Licence

Projet Parnasse Numérique — usage interne.
