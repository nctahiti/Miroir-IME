# 🪞 Miroir IME

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20API%2029%2B-green.svg)](https://developer.android.com)
[![Device](https://img.shields.io/badge/device-Boox%20Note%20Air%205C-lightgrey.svg)](https://www.boox.com)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-purple.svg)](https://kotlinlang.org)

**Handwriting Input Method for e-Ink Android Tablets**

> *"The intention to write: words are pushed onto a baseline that gives them a position in the reading direction."* — Nicolas

Miroir is an Android `InputMethodService` that replaces the standard keyboard with a full-screen handwriting surface. It captures stylus strokes at 284 Hz, groups them into words by spatial and temporal proximity, transcribes them via ML Kit Digital Ink Recognition, and lets you correct, move, or erase by natural gesture — without ever leaving the text field.

---

## ✨ Features

- **Native stylus capture** — automatic stylus detection on any Android text field
- **Intelligent grouping** — strokes fused into words by spatial + temporal proximity
- **Real-time transcription** — ML Kit Digital Ink Recognition, re-inference after correction
- **Visual feedback** — absorption blob around words, transcription labels, baselines
- **Optimized for e-ink** — DU/GU/REGAL modes, partial refresh, double buffer
- **Standalone notepad** — CaptureActivity for decoupled use without a host application
- **Open dataset** — validated correction pairs contributed anonymously to the ODbL HTR dataset

---

## 🤸 UxK — Kinetic UX

UxK is the gesture vocabulary that makes Miroir an annotation tool, not just a keyboard.
All editing happens on the writing surface — no dialogs, no mode switches.

### State machine

```
WRITING MODE (default)
  stylus drag          →  draw stroke
  blob contact         →  include/exclude stroke in open group
  pen up               →  seal stroke, return to writing

SELECTION
  long press (300 ms)  →  select group

KINETIC EDIT (from selected group, hold + drag direction)
  → Right              →  write on selection (absorb new strokes into selected group)
  ← Left               →  temporal erasure (point-by-point, reverse order)
  ↓ Down               →  spatial displacement (translate group)
  ↑ Up                 →  label correction (fix transcription)
```

### Termination protocol

| Gesture | Exits on |
|---------|----------|
| Right (write-on-selection) | Pen up → writing mode |
| Left (temporal erasure) | Pen up → writing mode, erasure persists |
| Down (displacement) | Pen up → writing mode, position persists |
| Up (label correction) | Tap in void → validates label, exits mode |

> The label correction gesture (↑) is the only one that does not auto-exit on pen-up,
> because a correction may require multiple strokes before validation.

### The symbiotic loop

```
WRITE ──→ Miroir transcribes ──→ CORRECT (swipe ↑, natural gesture)
  ↑                                        │
  │                                        ↓
  └─── Model improves ←── VALIDATED PAIR (ODbL dataset)
```

Each correction you make fixes your text immediately **and** enriches the shared dataset.
You correct because it is useful — the community benefits as a side effect.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                   MiroirIME.kt                       │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ CaptureView │  │ GroupManager │  │ Display    │ │
│  │ (surface)   │──▶│ (state mach) │  │ Controller │ │
│  └─────────────┘  └──────────────┘  └────────────┘ │
│         │                 │                  │       │
│         ▼                 ▼                  ▼       │
│  StrokeRegistry    InkGroup+MLKit     EpdController  │
│  (memory)          (transcription)    (e-ink)        │
└─────────────────────────────────────────────────────┘
         │
         ▼
  CaptureActivity (standalone notepad, local .note storage)
```

### Key components

| File | Role |
|------|------|
| `MiroirIME.kt` | IME lifecycle, input handling, UxK state machine |
| `CaptureView.kt` | Full-screen writing surface, EPD-aware rendering |
| `GroupManager.kt` | Stroke grouping, blob absorption, group state machine |
| `GroupPersistence.kt` | JSON serialization of pages (.note format) |
| `WordRecognizer.kt` | ML Kit Digital Ink wrapper |
| `DisplayController.kt` | EPD refresh orchestration (DU/GU/REGAL) |
| `VStarWriter.kt` | Conduit V★ binary delta encoder (see below) |

---

## 📐 Formats

### `.note` — parnasse.note.v1

JSON session format. Contains strokes, transcriptions, spatial metadata.
Each `words[i]` entry is a training pair: `(strokes, transcription)`.

```json
{
  "format": "parnasse.note.v1",
  "device": "Boox Note Air 5C",
  "sessionOrigin": [1234.5, 567.8],
  "words": [
    {
      "origin": [100.0, 200.0],
      "transcription": "bonjour",
      "strokes": [{ "points": [[x,y,p,t], ...] }]
    }
  ]
}
```

### `.vstar` — Conduit V★

Binary delta format. 13 bytes per point. For streaming capture to backend.
See companion prior art disclosure on Zenodo (CC0).

---

## ⚖️ Governance

| Layer | Licence | Philosophy |
|-------|---------|------------|
| **Dataset** (stroke→word pairs) | ODbL | Commons — anyone can train, no one can enclose |
| **Code Miroir IME** | Apache 2.0 | Free use, attribution required in NOTICE |
| **Conduit V★** (protocol) | CC0 prior art | Open specification, proprietary implementation |
| **Panoptis** (HTR encoder) | Proprietary | The singular *how* |
| **Parnasse** (ecosystem) | Commercial | The complete integration |

---

## 🔧 Requirements

- **Device:** Onyx Boox tablet (Note Air 5C or similar, API 29+)
- **SDK:** Android SDK 35, JDK 17
- **Dependencies:** Onyx SDK (pen, device, base), Google ML Kit Digital Ink, RxJava, Hidden API Bypass

> The Onyx SDK requires `targetSdk ≤ 29` for hidden API access.

---

## 🚀 Build & Deploy

```bash
# Set JDK
export JAVA_HOME="/path/to/jdk-17"

# Build
./gradlew assembleDebug

# Deploy
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop com.parnasse.miroir.v4

# Activate on device:
# Settings → Languages & Input → Virtual Keyboard → Miroir IME
```

**APK size:** ~39 MB (includes ML Kit model)

---

## 🗺️ Roadmap

| Phase | Content | Status |
|-------|---------|--------|
| Phase 1 | IME skeleton (capture, ML Kit, text commit) | ✅ Done |
| Phase 2 | UxK gestures, blob, baselines, e-ink optimization | 🔵 Active |
| Phase 3 | Parnasse integration (Geppetto, semantic search) | 🌊 Planned |
| Conduit | VStar streaming in IME (replace JSON capture) | 🌊 Planned |
| Dataset | Community collection portal, ODbL publication | 🌊 Horizon |

---

## 📚 Documentation

- [`docs/IME-ARCHITECTURE.md`](docs/IME-ARCHITECTURE.md) — detailed architecture
- [`docs/MIROIR-ARCHITECTURE-PRIOR-ART.md`](docs/MIROIR-ARCHITECTURE-PRIOR-ART.md) — complete system architecture disclosure (Apache 2.0)
- [`docs/format-note.md`](docs/format-note.md) — `.note` format specification (parnasse.note.v1)
- [`docs/dataset-libre.md`](docs/dataset-libre.md) — open HTR dataset vision, anonymization, ODbL
- [Conduit V★ — Zenodo prior art (CC0)](https://doi.org/10.5281/zenodo.20964421) — binary delta protocol
- [UxK — Zenodo prior art (CC0)](https://doi.org/10.5281/zenodo.20965531) — gesture vocabulary
- [Miroir IME — Zenodo prior art (CC0)](https://zenodo.org/records/20969687) — system architecture disclosure

---

## 📄 License

Apache License 2.0 — see [LICENSE](LICENSE).

Copyright 2026 Nicolas

Miroir IME is free to use, modify, and distribute including in commercial products.
Attribution required: include this notice in your NOTICE file.

---

*Miroir is part of the Parnasse ecosystem. The gesture is a language. The data is a commons.* 🪞
