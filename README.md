# Miroir IME v0.4.0

**Handwriting Input Method for e-Ink Android Tablets (Boox Onyx)**

Miroir is a custom Android IME (Input Method Editor) that captures handwritten stylus input at 284 Hz on e-ink devices. It replaces the standard keyboard with a full-screen writing surface — optimized for the Boox Note Air series with partial refresh, group-based stroke management, and ML Kit Digital Ink recognition.

## Architecture

An Android `InputMethodService` running in-process with the host application. Stylus events are captured natively via Onyx Pen SDK, grouped into semantic units with `GroupManager`, and persisted locally. Recognition uses Google ML Kit Digital Ink for online handwriting.

Key components:
- **`MiroirIME.kt`** — IME lifecycle, input handling, persistence
- **`CaptureView.kt`** — full-screen writing surface with EPD-aware rendering
- **`GroupManager.kt`** — inking group state machine (active → selected → stored)
- **`GroupPersistence.kt`** — JSON-based page serialization
- **`StrokeRenderer.kt`** / **`StrokeProcessor.kt`** — ink rendering pipeline
- **`DisplayController.kt`** — EPD refresh orchestration (DU/GU/REGAL modes)
- **`VStarWriter.kt`** — Conduit V★ binary delta encoding (see companion project)

For detailed architecture, see [`docs/IME-ARCHITECTURE.md`](docs/IME-ARCHITECTURE.md).

## Companion Project

The **Conduit V★** protocol — a fixed-width 13-byte binary delta encoding used by the VStarWriter — is published separately as prior art (CC0) on Zenodo. See the Conduit VStar repository for the specification and generalized protocol documentation.

## Requirements

- **Device:** Onyx Boox tablet (Note Air 5C or similar, API 29+)
- **SDK:** Android SDK 35, JDK 17
- **Dependencies:** Onyx SDK (pen, device, base), Google ML Kit Digital Ink, RxJava, Hidden API Bypass
- The Onyx SDK requires `targetSdk ≤ 29` for hidden API access

## Build & Deploy

```bash
# Set JDK (Microsoft JDK 17 recommended — JetBrains Runtime lacks jmods/)
export JAVA_HOME="/path/to/jdk-17"

# Build
./gradlew assembleDebug

# Deploy to tablet via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.parnasse.miroir.v4

# Activate the IME on the device:
# Settings → Languages & Input → Virtual Keyboard → Miroir IME
```

**APK size:** ~39 MB (includes ML Kit model)

## Project Structure

```
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── res/xml/method.xml         # IME method declaration
│       │   └── java/com/parnasse/miroir/
│       │       ├── MiroirIME.kt           # IME service (~2300 loc)
│       │       ├── CaptureView.kt          # Writing surface
│       │       ├── GroupManager.kt         # Stroke grouping
│       │       ├── GroupPersistence.kt     # Page serialization
│       │       ├── GroupStateMachine.kt    # Group lifecycle
│       │       ├── InkGroup.kt             # Group model
│       │       ├── InkStroke.kt            # Stroke model
│       │       ├── InkPoint.kt             # Point model
│       │       ├── BlobAbsorber.kt         # Hit-test absorption
│       │       ├── BlobParams.kt           # Blob geometry
│       │       ├── StrokeRenderer.kt       # Ink rendering
│       │       ├── StrokeProcessor.kt      # Stroke processing
│       │       ├── StrokeData.kt           # Stroke data
│       │       ├── StrokeRecord.kt         # Stroke records
│       │       ├── VStarWriter.kt          # Conduit V★ encoder
│       │       ├── VStarDocument.kt        # V★ document model
│       │       ├── WordRecognizer.kt       # ML Kit recognition
│       │       ├── TranscriptionWriter.kt  # Text output
│       │       ├── DigitalInkWrapper.kt    # ML Kit wrapper
│       │       ├── Template.kt             # Line template
│       │       ├── DisplayController.kt    # EPD refresh ctrl
│       │       ├── DisplayMode.kt          # EPD mode enum
│       │       ├── EpdPort.kt              # EPD abstraction
│       │       ├── OnyxEpdPort.kt          # Onyx EPD adapter
│       │       ├── MiroirService.kt        # Foreground service
│       │       ├── MiroirApp.kt            # Application class
│       │       ├── CaptureActivity.kt      # Debug activity
│       │       ├── CalibrationActivity.kt  # Calibration
│       │       └── OverlayPermissionActivity.kt
│       └── test/java/com/parnasse/miroir/
│           └── DisplayControllerTest.kt    # EPD controller tests
├── docs/
│   ├── IME-ARCHITECTURE.md     # Architecture reference
│   └── LA_MATRIOCHKA.md        # Poetry collection
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── gradle/wrapper/
```

## License

Apache License 2.0 — see [LICENSE](LICENSE).

Copyright 2026 Nicolas
