# Miroir IME — System Architecture Disclosure

## A Handwriting Capture & Annotation System for e-Ink Android Devices

**Prior Art Disclosure — Miroir IME Project**  
**Nicolas — June 27, 2026 — v0.4.0**  
**Released under Apache License 2.0**  
**Published to establish public prior art. Not a patent application.**

---

## Abstract

This document describes the Miroir IME: a complete system architecture for capturing, grouping, recognizing, annotating, and persisting handwritten input on e-ink Android tablets. Miroir operates as an Android `InputMethodService` — it replaces the standard keyboard with a full-screen handwriting surface that works transparently inside any application.

The system is built around five interconnected pipelines: (1) a stylus capture pipeline driven by the Onyx Pen SDK at hardware frequency, (2) a spatio-temporal grouping engine that fuses strokes into semantic words using organic blob absorption, (3) an asynchronous inference pipeline that transcribes grouped strokes via ML Kit Digital Ink without blocking capture, (4) a kinetic annotation layer (UxK) that embeds editing directly into the gesture vocabulary, and (5) a persistence layer that saves structured training pairs in the `.note` format (parnasse.note.v1).

Miroir is a reference architecture. Its components — the grouping engine, the blob absorption model, the EPD refresh controller, the dual-surface design — are described here in sufficient detail to serve as a blueprint for any developer building handwriting capture on constrained e-ink devices. The reference implementation is published under Apache 2.0 at https://github.com/nctahiti/Miroir-IME.

Two companion disclosures cover the UxK gesture vocabulary (CC0) and the Conduit V★ binary streaming protocol (CC0). This document covers the system architecture that integrates them.

---

## 1. The Problem: Handwriting on e-Ink Android

### 1.1 Why e-Ink is Different

e-Ink displays (E Ink Corporation) operate on fundamentally different principles from LCD/OLED:

- **No continuous refresh.** Pixels are bistable — they hold their state without power. Updating them requires physically moving charged pigment particles through a viscous fluid.
- **Refresh modes trade speed for quality.** DU (Direct Update) gives ~30 ms latency with ghosting; GU (Gray Update) gives clean text at ~120 ms; REGAL gives publication-quality at ~450 ms.
- **Partial refresh is possible but constrained.** The Onyx SDK exposes `handwritingRepaint(view, l, t, r, b)` for fast partial updates — but this function is only available in specific contexts (handwriting mode, pen active).
- **Ghosting accumulates.** Without periodic full refreshes, previous screen states remain visible as faint after-images.

A handwriting IME on e-ink must solve the refresh problem continuously: ink must appear to flow from the stylus in real time, while previously written text remains legible without ghosting.

### 1.2 Why an IME?

An Android `InputMethodService` is the system-level keyboard interface. Deploying handwriting capture as an IME rather than a standalone app provides three architectural advantages:

1. **Universal surface.** The IME appears inside any application's text field — browser, notes, email — without the host app needing any integration.
2. **Text commitment.** The IME has direct access to the `InputConnection` — recognized text is committed to the host app's text field atomically.
3. **System lifecycle.** The OS manages the IME's lifecycle: it is created when a text field gains focus, destroyed when focus is lost. The IME cannot leak memory or processes across sessions.

The trade-off: IMEs run in a constrained process that the OS can kill at any time. Persistence must be crash-safe. This constraint shaped the Conduit V★ protocol (CC0, separate disclosure).

---

## 2. System Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     MiroirIME.kt (InputMethodService)         │
│                                                               │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐  │
│  │ Pipeline 1   │   │ Pipeline 2   │   │ Pipeline 3       │  │
│  │ CAPTURE      │──▶│ GROUP        │──▶│ INFERENCE        │  │
│  │              │   │              │   │                  │  │
│  │ TouchHelper  │   │ GroupManager │   │ ML Kit Digital   │  │
│  │ (Onyx SDK)   │   │ BlobAbsorber │   │ Ink Recognition  │  │
│  │ 60+ Hz       │   │ StateMachine │   │ async, timer     │  │
│  └──────┬───────┘   └──────┬───────┘   └────────┬─────────┘  │
│         │                  │                     │            │
│         ▼                  ▼                     ▼            │
│  StrokeRegistry     InkGroup + blobs      Label + injection   │
│  (in memory)        (spatial index)       (InputConnection)   │
│                                                               │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │ Pipeline 4 — KINETIC ANNOTATION (UxK — CC0 disclosure)  │ │
│  │  → Right: write-on-selection    ← Left: temporal erase  │ │
│  │  ↓ Down: spatial displacement   ↑ Up: label correction  │ │
│  └──────────────────────────────────────────────────────────┘ │
│                                                               │
│  ┌──────────────────────┐  ┌──────────────────────────────┐   │
│  │ Pipeline 5a          │  │ Pipeline 5b                  │   │
│  │ PERSISTENCE          │  │ EPD CONTROLLER               │   │
│  │ .note (JSON)         │  │ DU/GU/REGAL                  │   │
│  │ stroke → word pairs  │  │ handwritingRepaint           │   │
│  └──────────────────────┘  └──────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
         │
         ▼
  CaptureActivity (standalone notepad, same surface, no host app)
```

---

## 3. Pipeline 1 — Stylus Capture

### 3.1 Hardware Path

The capture chain on a Boox Note Air 5C:

```
Wacom digitizer (284 Hz physical)
  → Onyx Pen SDK (TouchHelper, 60+ Hz software events)
    → MotionEvent (Android input system)
      → onTouchEvent() in CaptureView
        → StrokeRegistry (in-memory ring buffer)
          → VStarWriter (Conduit V★, optional, 13 bytes/sample)
```

**Key architectural decision:** The Onyx TouchHelper is initialized once at `onCreateInputView()` and forwards all MotionEvents to the IME's view hierarchy. The IME never polls — it receives events pushed by the system. This ensures zero-latency capture at whatever rate the hardware + driver provide.

### 3.2 Point Model

Each captured point carries 7 dimensions:

| Dimension | Source | Range |
|-----------|--------|-------|
| x, y | MotionEvent axis values | Screen pixels (float) |
| timestamp | `event.eventTime` | Nanoseconds since boot |
| pressure | `event.getPressure()` | 0.0–1.0 |
| azimuth | `event.getOrientation()` | 0–360° |
| tilt | `event.getToolMajor()` | 0–90° |
| distance | TouchHelper hover distance | mm |

Points are accumulated into a `currentPath` (list of Point objects) while the stylus is down. On pen-up, the path is sealed into a `StrokeRecord` and appended to `strokeRegistry`.

### 3.3 VStarWriter — Conduit V★ Streaming (Optional)

For applications requiring real-time streaming to a backend, the VStarWriter writes each point as a 13-byte binary delta token directly to disk (`filesDir/vstar/session_*.vstar`). The format is specified in the Conduit VStar prior art disclosure (CC0, Zenodo). In the reference implementation, VStar writing is optional — the primary persistence path is the `.note` format.

---

## 4. Pipeline 2 — Spatio-Temporal Grouping

### 4.1 The Grouping Problem

Handwriting recognition works on words, not individual strokes. The system must decide which strokes belong to the same word. This is a clustering problem in space and time.

Existing approaches use:
- **Geometric only** — group by bounding box overlap (fails for cursive, diacritics)
- **Temporal only** — group by inter-stroke delay (fails when user pauses between letters)
- **Fixed grid** — divide page into cells (fails for variable word length)

Miroir uses **blob absorption**: a dynamic, organic boundary computed from the geometric envelope of each group's strokes.

### 4.2 Group Lifecycle (State Machine)

```
                 ┌──────────┐
     stroke      │  LOADED  │  (active, receiving strokes)
  ──────────────▶│          │
                 └────┬─────┘
                      │ long-press (300 ms)
                      ▼
                 ┌──────────┐
                 │ SELECTED │  (user-chosen, priority absorption)
                 └────┬─────┘
                      │ timer expiry (2s inactivity) or page change
                      ▼
                 ┌──────────┐
                 │  STORED  │  (persisted, can be evicted from memory)
                 └──────────┘
```

**Critical design rule:** A SELECTED group is NEVER automatically deselected. Only the user (via long-press elsewhere, tap-in-void, or mode exit) can change the selection. This "permissiveness principle" ensures that the user's intent is never overridden by system heuristics.

### 4.3 Absorption Algorithm

When a new stroke is sealed (`onStrokeSealed()`), the system tests it against existing groups in priority order:

```
1. SELECTED group (if exists)
   → expand bounds by absorption margin
   → test stroke bounding box vs expanded bounds
   → if intersect → ABSORB (stroke joins selected group)

2. ACTIVE group (most recently created)
   → same test with standard bounds
   → if intersect → ABSORB

3. NEARBY groups (spatial proximity)
   → test stroke center vs all group bounds
   → if multiple intersections → choose nearest

4. No match → create NEW group (LOADED)
```

The absorption margin for the SELECTED group is intentionally generous (typically 2× standard margin). This is the mechanism behind UxK Gesture → (write-on-selection): the expanded blob catches strokes that would otherwise form a new group.

### 4.4 The Blob — Organic Boundary Computation

The blob is the visual and computational boundary of an ink group. It is NOT a rectangle. It is computed as the union of per-point absorption ellipses, traced by ray casting.

**Algorithm:**

```
for each point in group.strokes:
    ellipse center = (point.x, point.y)
    ellipse radii  = (absorptionWidth, absorptionHeight)
    for each angle in [0, 2π) step resolution:
        cast ray from center at angle
        find intersection with other ellipses
        take furthest intersection as boundary point

connect boundary points → closed polygon path
```

**Why organic?** Rectangular bounds create false positives (absorbing strokes from adjacent words) and false negatives (missing diacritics above a word). The organic blob follows the actual shape of the handwriting — a diacritic above "é" is inside the blob because the per-point ellipses extend upward, while a stroke from the line above is excluded because the ellipses taper at the edges.

**Blob uses:**
1. **Absorption** — stroke-to-group membership decision
2. **Selection target** — long-press within or near a blob selects its group
3. **Visual feedback** — rendered as a semi-transparent outline showing the active group
4. **Mode indication** — expanded blob during write-on-selection, highlighted during correction

---

## 5. Pipeline 3 — Asynchronous Inference

### 5.1 The Timing Problem

ML Kit Digital Ink recognition takes 50–200 ms per word. If run synchronously on the main thread, it would block stylus capture — the user would see ink lag behind the pen.

Miroir's solution: **timer-based asynchronous inference per group.**

### 5.2 Inference Flow

```
Stroke sealed → GroupManager.onStrokeSealed()
  → group receives stroke
  → group.touch() (reset inactivity timer)
  → scheduleGroupInference(group, delay=800ms)
      │
      ▼ (after 800ms of inactivity on this group)
  recognizeGroup(group)
  → extract all strokes from group
  → DigitalInkWrapper.recognize(strokes)  // ML Kit, async
  → label = recognized text
  → group.setLabel(label)
  → computeBlobPath(group)  // regenerate blob
  → injectReadingOrder()    // sort all groups spatially, commit text
```

### 5.3 Timer Management

Each group has an independent 800 ms timer. The timer is reset on every new stroke added to the group. This implements a "quiet period" heuristic: recognition fires when the user has paused writing on a specific word for 800 ms, indicating the word is complete.

If the user continues writing without pause, the timer never fires — the group accumulates strokes indefinitely until selection, page change, or explicit validation.

### 5.4 Reading Order Injection

Recognized labels are committed to the host application's text field in spatial reading order, not temporal creation order:

1. All groups with labels are sorted by: `baseline (Y) → then X`
2. Groups on the same baseline (within `lineSpacing × 0.5` tolerance) form a line
3. Lines are separated by newlines (1 empty line → `\n`, 2+ → `\n\n`)
4. Text is committed via `InputConnection.commitText(orderedText, 1)`

This ensures that writing "hello" on line 1, then "world" on line 2, then returning to add "the" before "world" on line 1 produces: `hello the\nworld` — matching the visual layout, not the temporal sequence.

### 5.5 Label Correction & The Symbiotic Loop

When the user triggers UxK Gesture ↑ (label correction) on a misrecognized word:

1. The current label is displayed as individual letters in a zoomed overlay
2. User taps a letter → correction mode enters
3. User writes correction stroke(s) → new inference on the corrected input
4. New label replaces the original
5. On ✓ (validate), the corrected pair `(original_strokes, corrected_label)` is queued
6. The pair can be contributed to the open ODbL HTR dataset

See UxK prior art disclosure (CC0, Zenodo) for the complete gesture specification.

---

## 6. Pipeline 4 — Kinetic Annotation (UxK)

The UxK gesture vocabulary is documented in its own prior art disclosure (CC0, Zenodo). Here we describe only its architectural integration into the Miroir system.

### 6.1 Gesture Dispatch

All gestures originate from `CaptureView.onTouchEvent()` and are dispatched through a three-layer state machine:

**Layer 1 — WRITING MODE (default)**
```
stylus down + drag  →  accumulate points in currentPath
blob contact        →  absorption decision (see §4.3)
pen up              →  seal stroke, return to writing
```

**Layer 2 — SELECTION**
```
long press (300 ms) → selectGroup(blob under stylus)
                     → group state → SELECTED
                     → blob expands (visual feedback)
```

**Layer 3 — KINETIC EDIT** (from SELECTED, hold + drag direction)
```
→ Right  → WRITE_ON_SELECTION   (see §4.3, expanded absorption)
← Left   → TEMPORAL_ERASURE     (point-by-point reverse removal)
↓ Down   → SPATIAL_DISPLACEMENT (translate group by (dx, dy))
↑ Up     → LABEL_CORRECTION     (see §5.5, overlay + re-inference)
```

### 6.2 Termination Protocol

| Gesture | Exits on | State after exit |
|---------|----------|------------------|
| → Right (write-on-selection) | Pen up | Writing mode, group stays SELECTED |
| ← Left (temporal erasure) | Pen up | Writing mode, erasure persists |
| ↓ Down (displacement) | Pen up | Writing mode, new position persists |
| ↑ Up (label correction) | Tap in void | Writing mode, label validated |

The asymmetric termination of ↑ (label correction) is architecturally significant: it is the only gesture that does not auto-exit on pen-up, because correction may require multiple strokes before validation. This required a separate "correction overlay" rendering path (see §8.2).

---

## 7. Pipeline 5a — Persistence (.note format)

### 7.1 The `.note` Format (parnasse.note.v1)

The `.note` format is a JSON-based session storage format. Its design principle: **each file is a training pair container.**

```json
{
  "format": "parnasse.note.v1",
  "device": "Boox Note Air 5C",
  "created": "2026-06-27T14:30:00+0200",
  "origin": [1234.5, 567.8],
  "baselines": [200.0, 350.0, 500.0],
  "groups": [
    {
      "orderIndex": 0,
      "strokeIds": ["uuid-1", "uuid-2"],
      "bounds": {"l": 100, "t": 190, "r": 350, "b": 240},
      "label": "bonjour",
      "anchor": [100.0, 200.0],
      "created": "2026-06-27T14:30:01+0200",
      "modified": "2026-06-27T14:30:05+0200"
    }
  ],
  "strokes": [
    {
      "id": "uuid-1",
      "inkId": 1234567890,
      "points": [[x1,y1,p1,t1], [x2,y2,p2,t2], ...]
    }
  ]
}
```

### 7.2 Page Management

Miroir supports multiple pages within a writing session:

- `savePage()` — serializes all groups + strokes + baselines to `page_N/state.json` + `groups.json`
- `loadPage(n)` — deserializes page N, rebuilds `inkStrokeIdToRegistryIndex`, recalculates blobs
- Navigation: ◀ previous page, ▶ next page
- Empty page guard: `savePage()` returns early if `strokeRegistry.isEmpty()` — prevents overwriting a saved page with empty data

### 7.3 Block Sessions

Pages are organized into blocks: `blocks/<appName>_<timestamp>/page_N/`. A block is created at `onStartInputView()` (when the IME is invoked) and closed on explicit user action. This groups all writing done within one app session, even across page navigations.

---

## 8. Pipeline 5b — EPD Refresh Controller

### 8.1 DisplayController

The `DisplayController` abstracts the Onyx EPD SDK behind a testable interface (`EpdPort`), with a real implementation (`OnyxEpdPort`) and the ability to swap in a mock for testing.

```
DisplayController
  ├── EpdPort (interface)
  │     ├── setDefaultMode(mode)
  │     ├── enablePost(boolean)
  │     ├── handwritingRepaint(view, l, t, r, b)
  │     └── refreshScreen(view, mode)
  └── OnyxEpdPort (real implementation)
```

### 8.2 Rendering Architecture

The IME view hierarchy uses a **bitmap + live stroke** dual-buffer approach:

1. **Bitmap buffer** — all sealed strokes are rendered onto a persistent `android.graphics.Bitmap`. This bitmap is drawn in `onDraw()` as the background layer.
2. **Live stroke** — the current in-progress stroke (`currentPath`) is drawn directly on the Canvas in `onDraw()`, on top of the bitmap.
3. **Labels** — transcription labels are drawn on the Canvas (not the bitmap), with targeted `handwritingRepaint()` calls that refresh only the label's bounding rectangle.

**Why not draw everything on the bitmap?** Labels are updated asynchronously when recognition completes. Drawing them on the bitmap would require a full bitmap redraw on every label update → visible flicker. Drawing them on the Canvas with targeted partial refresh avoids this.

### 8.3 EPD Mode Transitions

```
         pen down
  GU ──────────────▶ DU (handwriting mode ON, enablePost=0)
  ▲                    │
  │                    │ pen up (+ reassert DU to prevent driver fallback)
  │                    ▼
  │                  DU (maintained)
  │                    │
  │                    │ exit handwriting
  │                    ▼
  └────────────────── GU (entrerVue, normal viewing mode)
  
  REGAL: reserved for full page transitions (page change, block list display)
```

**Reassertion problem:** The Onyx driver automatically reverts from DU to GU ~500 ms after pen-up. Miroir's `reasserterDU()` forces the mode back to DU at each pen-up, ensuring the writing surface stays responsive between strokes.

### 8.4 Throttling

`onDraw()` calls are throttled to ~33 Hz (30 ms minimum interval) via `throttledInvalidate()`. At 60 Hz stylus event rate, every other frame is skipped — sufficient for perceived real-time ink flow on e-ink's ~30 ms DU refresh, while preventing unnecessary redraws.

---

## 9. The Dual-Surface Architecture

Miroir provides two surfaces sharing the same codebase:

### 9.1 Coupled Mode (MiroirIME)

- Active inside any host application's text field
- Text committed via `InputConnection`
- Lifecycle managed by Android IME framework
- Block sessions tied to host app identity

### 9.2 Decoupled Mode (CaptureActivity)

- Standalone notepad, launched by shortcut
- Manages its own `.note` storage in `filesDir/blocnote/`
- No host application — text is displayed internally
- Same UxK gesture vocabulary, same rendering pipeline
- Used for: dictation exercises, training data collection, uncoupled writing

Both modes share: `CaptureView`, `GroupManager`, `DisplayController`, `VStarWriter`, `BlobAbsorber`, and the UxK state machine.

---

## 10. The Partition — Spatio-Temporal Index

### 10.1 The Problem of Retrieval

During a writing session, the system must quickly answer queries like:
- "Which group contains this point?" (for absorption, selection)
- "Which groups are on this baseline?" (for reading order)
- "Which groups are visible in this viewport?" (for rendering)

A linear scan over all groups is O(n) — acceptable for <50 groups, but the architecture is designed for extension to larger sessions.

### 10.2 Current Solution: Ordered Map + Baseline Index

Groups are stored in a `LinkedHashMap<groupId, InkGroup>` preserving creation order. A separate baseline index maps rounded Y-coordinates to lists of groups on that line. Both are rebuilt incrementally as groups are added or modified.

### 10.3 Future: Spatial Grid

The architecture reserves the extension point for a quadtree or grid-based spatial index. Groups would be inserted into grid cells based on their bounding box; point queries would check only the cell containing the point and its immediate neighbors — O(1) average. This is documented here to establish prior art on the architectural extension.

---

## 11. The Interline — Baseline Model

### 11.1 Template

`Template.kt` provides a configurable set of horizontal baselines:

```
spacing = 80 px (configurable, ~7 mm on Boox at 300 DPI)
lines = [0, 80, 160, 240, 320, ...]
```

Each group is snapped to the nearest baseline via `snapToLine(y)` at creation. The snap is spatial metadata only — point coordinates are never modified. The snap determines reading order and label placement.

### 11.2 Baseline Extraction

Baselines are extracted from the writing session:

1. Collect all group anchors (first point of first stroke)
2. Round Y to nearest multiple of spacing
3. Deduplicate (multiple groups on same line → one baseline)
4. Sort ascending
5. Store in `state.json` for page restoration

### 11.3 Reading Order Reconstruction

`buildReadingOrder()` is the function that answers "what is the text on this page, in order?"

```
1. Collect all groups with labels
2. Sort by (baseline, then anchor.x)
3. Group by baseline
4. For each baseline group:
     labels = groups.map { it.label }.joinToString(" ")
     emit(labels)
5. Join baselines with "\n"
```

Empty lines (no groups on that baseline) are preserved — they are semantically meaningful (paragraph breaks).

---

## 12. The Correction Overlay — A UI Pattern for IMEs

### 12.1 The Problem: Dialogs in IMEs

`AlertDialog`, `Dialog`, and `PopupWindow` — even with `TYPE_APPLICATION_PANEL` — throw `BadTokenException` when launched from an `InputMethodService`. The IME window token is not valid for child dialogs.

### 12.2 Solution: Internal Overlay Panel

Miroir's approach: a `LinearLayout` at the root of the IME view hierarchy, initially `GONE`, toggled `VISIBLE` with injected content at runtime.

```
MiroirIME root layout
├── CaptureView (writing surface, always present)
└── overlayPanel (LinearLayout, GONE by default)
    └── [injected content: correction letters, block list, transcription view]
```

**Pattern:** `showOverlay(content: View, title: String)` / `hideOverlay()`

This pattern is used for:
- **Label correction overlay** — zoomed letters, tap to correct (UxK ↑)
- **Block list** — long-press ◀ to see all writing sessions
- **Transcription view** — long-press ▶ to see formatted text of all pages

---

## 13. The `.note` Format — Complete Specification

### 13.1 Design Goals

The `.note` format (parnasse.note.v1) is a self-contained JSON file that stores a complete writing session: raw strokes, word groups, transcriptions, and metadata. It is designed for:

- **Session persistence** — complete save/load of a writing page
- **HTR dataset** — training pairs (strokes, transcription) ready for model training
- **Interoperability** — open, human-readable, documented format
- **Anonymization** — private context (sentence, document) is excluded; only gestures and their transcriptions remain

### 13.2 Full JSON Schema

```json
{
  "format": "parnasse.note.v1",
  "device": "Boox Note Air 5C",
  "created": "2026-06-27T14:30:00+0200",
  "mode": "blocnote",
  "label": "My note",
  "sessionOrigin": [1234.5, 567.8],
  "words": [
    {
      "origin": [100.0, 200.0],
      "transcription": "bonjour",
      "strokes": [
        {
          "points": [
            [x1, y1, pressure1, timestamp1],
            [x2, y2, pressure2, timestamp2]
          ]
        }
      ]
    }
  ]
}
```

### 13.3 Root Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `format` | string | ✅ | Always `"parnasse.note.v1"` |
| `device` | string | ✅ | Tablet model (e.g., `"Boox Note Air 5C"`) |
| `created` | string | ✅ | ISO 8601 creation timestamp |
| `mode` | string | ✅ | Writing mode: `"blocnote"` or `"formulaire"` |
| `label` | string | No | Note title or name |
| `sessionOrigin` | [float, float] | ✅ | Absolute position of session's first point (spatial anchor) |
| `words` | array | ✅ | List of word groups |

### 13.4 Word Structure (`words[i]`)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `origin` | [float, float] | ✅ | Absolute position of the group's origin |
| `transcription` | string | No | ML Kit transcription of the word (may be empty if not inferred) |
| `strokes` | array | ✅ | List of strokes composing this word |

Each stroke is an array of points: `[x, y, pressure, timestamp]`.

**Coordinates** are relative to `sessionOrigin + word.origin`. To obtain absolute coordinates:

```
abs_x = sessionOrigin.x + word.origin.x + point.x
abs_y = sessionOrigin.y + word.origin.y + point.y
```

### 13.5 Usage as HTR Dataset

Each `words[i]` entry constitutes a **training pair**:

```
INPUT  : strokes (points x, y, p, t) — the raw gesture
OUTPUT : transcription — the corrected word
```

**Included:** strokes, transcription, spatial origin  
**Excluded:** full sentence context, writer identity, host application, precise timestamp

The format is designed for concatenation — multiple `.note` files can be merged into a single training dataset.

See [`docs/format-note.md`](docs/format-note.md) for the complete specification with examples.

---

## 14. The Anonymization Pipeline

### 14.1 Principle: Gesture vs. Context

The dataset separates what is public (the gesture) from what is private (the context):

| Information | In dataset? | Justification |
|-------------|-------------|---------------|
| Strokes (x, y, p, t) | ✅ Yes | Pure gesture — no identifiable information |
| Transcription (word) | ✅ Yes | Isolated word, not a sentence |
| Full sentence | ❌ No | Context could reveal private content |
| Source document | ❌ No | Remains local |
| Writer name/ID | ❌ No | No personal identifier |
| Host application | ❌ No | Not collected |
| Precise timestamp | ❌ No | Date only (not exact time) |

### 14.2 Anonymization Steps

When a validated pair is queued for dataset contribution:

1. **Origin → 0** — spatial origin of the word is zeroed. The relative point coordinates are preserved; the absolute position on the user's page is discarded.
2. **dt → 0** — inter-stroke temporal deltas are zeroed. The temporal rhythm of writing is preserved; the absolute wall-clock time is not.
3. **Checksum** — a content hash of the stroke data is computed for deduplication and integrity verification.
4. **Word isolation** — only the target word's strokes are included. Neighboring words, the full sentence context, and the page layout are discarded.

### 14.3 The Symbiotic Loop

The anonymization pipeline is the mechanism by which the Miroir IME generates the open HTR dataset. The loop:

```
WRITE → Miroir transcribes → CORRECT (UxK ↑, natural gesture)
  ↑                                      │
  │                                      ↓
  └─── Model improves ←── VALIDATED PAIRS (ODbL dataset)
```

Only UxK Gesture ↑ (label correction) produces validated pairs. The user corrects because the wrong word would be committed to the host application — not because they are contributing to a dataset. The annotation is a side effect of a useful action.

The dataset is governed by the **ODbL v1.0** (Open Database License) — the same license as OpenStreetMap. Anyone can use, share, and adapt the database; any derived database must remain under ODbL (Share-Alike); attribution is required; there is no contagion on code that uses the database.

See [`docs/dataset-libre.md`](docs/dataset-libre.md) for the complete vision and federated architecture.

---

## 15. The Conduit / .note Relationship — Protocol vs. Storage

### 15.1 Two Formats, One System

The Miroir architecture distinguishes between **transmission** and **storage**:

| Property | Conduit `.vstar` | Storage `.note` |
|----------|------------------|-----------------|
| **Purpose** | Real-time transmission | Session storage and HTR dataset |
| **Encoding** | Binary, fixed-width (13 bytes/point) | JSON, human-readable |
| **Granularity** | Sample by sample | Semantic group (word) |
| **Buffering** | None — direct disk write | In-memory until group sealed |
| **Metaphor** | Toll highway | Destination receptacle |
| **Reversibility** | `.vstar → .note` : lossless* | `.note` is source of truth |

> \* Lossless modulo Short quantization. At `vstar_unit=0.1`, spatial precision is 0.1 mm/unit — below one physical pixel on the Boox at 300 DPI (~0.085 mm/pixel). Quantization error is bounded per token and does not accumulate across the delta chain.

### 15.2 Data Flow

```
Stylus capture (284 Hz physical)
  │
  ├──→ VStarWriter → .vstar file (streaming, crash-safe, 13 bytes/sample)
  │         │
  │         └──→ [optional: transmit to backend]
  │
  └──→ StrokeRegistry → GroupManager → InkGroup → .note file (JSON, complete session)
            │
            └──→ ML Kit inference → label → validated pair → ODbL dataset
```

### 15.3 Design Rationale

The Conduit V★ is optimized for the **constrained streaming problem**: an Android IME process that can be killed at any moment, writing to disk with no guaranteed flush window. Fixed-width 13-byte tokens with direct unbuffered writes ensure that process termination loses at most one sample.

The `.note` format is optimized for the **training pair problem**: a complete, self-contained JSON document where each word is stored with its strokes and transcription as an independent unit, ready for dataset contribution.

The two formats are complementary. A `.vstar` stream can be losslessly converted to a `.note` file. The `.note` file is the canonical storage format; the `.vstar` stream is the canonical transmission format.

The Conduit V★ protocol is specified in its own prior art disclosure (CC0, Zenodo). Only the architectural relationship is described here.

---

## 16. Original Contributions

The following are claimed as original contributions, to the best of the author's knowledge as of June 27, 2026:

1. **Organic blob absorption** as a spatial grouping mechanism for ink strokes on touch devices, computed by ray casting over per-point absorption ellipses rather than rectangular bounding boxes, used simultaneously for absorption, selection targeting, and visual feedback.

2. **The asymmetric timer model for handwriting inference**: independent per-group 800 ms timers that reset on each new stroke, enabling recognition to fire only when the user pauses on a specific word — decoupling capture rate from inference latency without requiring stroke count thresholds.

3. **The bitmap + live-stroke dual-buffer rendering architecture for e-ink IMEs**, where sealed strokes are on a persistent bitmap and the current stroke is drawn on the Canvas layer, enabling targeted `handwritingRepaint()` for label updates without full-screen refresh.

4. **The DU reassertion pattern**: forcing the EPD mode back to DU at every pen-up to prevent the Onyx driver's automatic 500 ms GU fallback, maintaining handwriting responsiveness across inter-stroke pauses.

5. **The internal overlay panel pattern** for displaying interactive UI within an `InputMethodService` context where system dialogs are unavailable, using a root-level `LinearLayout` toggled VISIBLE/GONE with runtime content injection.

6. **The dual-surface architecture** (IME + standalone Activity) sharing the same capture, grouping, inference, and annotation pipeline, where both surfaces feed the same symbiotic annotation loop and use the same `.note` persistence format.

7. **The `.note` format (parnasse.note.v1)** as a training-pair-native JSON session format where each semantic word group is stored with its strokes and transcription as an independent pair, decoupling storage from transmission (`.vstar`).

8. **The spatio-temporal reading order reconstruction** algorithm: baseline snapping, tolerance-based line grouping (`|Δy| < spacing × 0.5`), and ordered text injection via `InputConnection.commitText()`.

9. **The permissiveness principle in ink group state machines**: the rule that a SELECTED group is never automatically deselected by the system — only explicit user action can change it — preventing system heuristics from overriding user intent during multi-step writing and editing sequences.

---

## 17. Known Prior Art (Not Claimed)

The following concepts are established and are NOT claimed as original:

- Android `InputMethodService` as a handwriting surface (Google Handwriting Input, MyScript Stylus, Samsung Keyboard handwriting mode)
- Google ML Kit Digital Ink Recognition (Google LLC, 2020+)
- Onyx Pen SDK (`TouchHelper`, `EpdController`) (Onyx International Inc.)
- EPD refresh modes DU/GU/REGAL (E Ink Corporation, Onyx)
- Spatial grouping of strokes by bounding box overlap (Microsoft, Apple, MyScript)
- Timer-based recognition triggering (various IMEs use fixed delays after last stroke)
- Gesture-based editing on touch devices (Apple Scribble, Samsung Notes, MyScript)
- Double-buffer rendering (standard pattern in graphics since 1970s)

---

## 18. Relationship to Companion Disclosures

| Disclosure | Topic | License | Status |
|------------|-------|---------|--------|
| **UxK — Kinetic UX** | Four-directional long-press gesture vocabulary, state machine, symbiotic annotation loop | CC0 | Zenodo |
| **Conduit V★** | Fixed-width 13-byte binary delta streaming protocol | CC0 | Zenodo |
| **Miroir IME** (this document) | Complete system architecture: capture, grouping, inference, persistence, EPD control | Apache 2.0 | GitHub + Zenodo |

The three disclosures are designed to be read independently. UxK and Conduit cover components; Miroir covers the system that integrates them. Each can be implemented separately.

---

## 19. Governance

| Layer | License | Philosophy |
|-------|---------|------------|
| **Miroir IME** (code + architecture) | Apache 2.0 | Free use, modify, distribute. Attribution required. |
| **UxK** (gesture vocabulary) | CC0 | Public domain. No restrictions. |
| **Conduit V★** (protocol) | CC0 prior art | Open specification. Proprietary implementation allowed. |
| **Dataset** (stroke → word pairs) | ODbL | Commons — anyone can train, no one can enclose. |

---

## 20. Reference Implementation

- **Repository:** https://github.com/nctahiti/Miroir-IME
- **Language:** Kotlin (Android, API 29+)
- **Key files:** `MiroirIME.kt` (~2300 loc), `CaptureView.kt`, `GroupManager.kt`, `BlobAbsorber.kt`, `DisplayController.kt`, `VStarWriter.kt`, `GroupPersistence.kt`
- **Tests:** `DisplayControllerTest.kt` (JVM unit tests for EPD controller)
- **License:** Apache 2.0

---

## 21. Prior Art Declaration

This document is released under Apache License 2.0. Its purpose is to establish public prior art and to serve as a reference architecture for developers building handwriting capture systems on e-ink Android devices. It does not constitute a patent application.

The reference implementation at https://github.com/nctahiti/Miroir-IME is the authoritative source for implementation details. This document describes the architecture; the code is the specification.

**Author:** Nicolas  
**Date:** June 27, 2026  
**Project:** Miroir IME — Parnasse ecosystem  
**License:** Apache License 2.0  
**Zenodo DOI:** [to be completed upon submission]

---

*Miroir is part of the Parnasse ecosystem. The gesture is a language. The data is a commons.* 🪞
