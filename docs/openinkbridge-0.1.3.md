# OpenInkBridge v0.1.3 — Veille technologique

**Date** : 30 juillet 2026  
**Auteur** : Ved Suthar (GoVed)  
**Dépôt** : https://github.com/GoVed/OpenInkBridge  
**Release** : v0.1.3

---

## Résumé

Refonte logging/diagnostic. Ajout d'un logger structuré avec ring buffer et d'un collecteur de diagnostics exportable en JSON — pattern identique à notre buffer LLM (`/api/debug/llm-logs`).

---

## Nouveaux fichiers Android SDK

| Fichier | Lignes | Rôle |
|---------|--------|------|
| `OpenInkBridgeLogger.kt` | 142 | Ring buffer 500 entrées, 5 niveaux (ERROR→TRACE), 12 sous-systèmes |
| `OpenInkBridgeDiagnostics.kt` | 167 | `collectDiagnostics()` → JSON complet (device, backend, capabilities, logs) |
| `CoreBridge.kt` (+70) | diff | Intégration du logger dans les appels EPD |
| `EpdAdapterManager.kt` (+53) | diff | Traçage des changements de backend |

---

## Architecture du logger

```
┌──────────────────────────────────────────────────────────┐
│ OpenInkBridgeLogger                                      │
│                                                          │
│ ringBuffer[500] ← LogEntry(timestamp, level, subsystem,  │
│                            backend, event, message,      │
│                            parameters)                   │
│                                                          │
│ Niveaux : ERROR, WARN, INFO, DEBUG, TRACE                │
│ Sous-systèmes : Core, Backend, Renderer, PenInput,       │
│                 Refresh, Synchronization, JsBridge,      │
│                 Android, Linux, Performance,             │
│                 Configuration, Networking                │
│                                                          │
│ → logcat ET ring buffer (double sortie)                  │
└──────────────────────────────────────────────────────────┘
```

---

## DiagnosticsData — dump JSON

```json
{
  "version": "0.1.3",
  "platform": "Android SDK",
  "deviceModel": "NoteAir5C",
  "manufacturer": "Onyx",
  "selectedBackend": "OnyxEpd",
  "availableBackends": ["OnyxEpd", "StandardAndroid"],
  "fallbackReason": null,
  "capabilities": {
    "pressure": true, "tilt": false, "hover": true,
    "eraser": true, "refreshModes": ["SPEED", "BALANCED", "QUALITY"],
    "hardwareAcceleration": true
  },
  "refreshMode": "SPEED",
  "directDrawingActive": true,
  "recentLogs": [...]
}
```

---

## Parallèle avec notre architecture

| Concept | Nous (Cœur) | Eux (OpenInkBridge) |
|---------|------------|---------------------|
| Buffer circulaire | `/api/debug/llm-logs` (100 entrées) | `ringBuffer` (500 entrées) |
| Dump JSON | `LLMLogEntry` → JSON | `DiagnosticsData.toJson()` |
| Niveaux | implicite | ERROR/WARN/INFO/DEBUG/TRACE |
| Sous-systèmes | — | 12 sous-systèmes typés |
| Exposition | `GET /api/debug/llm-logs` | pas encore exposé |

---

## Intérêt pour le Miroir

1. **Remplacer les `Log.i("Miroir/Fontaine", ...)` éparpillés** par un buffer unifié
2. **Exposer un endpoint** `/api/debug/epd-logs` sur le même modèle que le buffer LLM
3. **Diagnostiquer les latences EPD** (raw drawing on/off, refresh modes, erreurs firmware)
4. **Tracer les changements de backend** (OnyxEpd ↔ StandardAndroid fallback)
5. **Détecter le ping-pong raw drawing** (bug corrigé dans `5ff1c57` — le logger permettrait de le confirmer en production)

---

## Points d'intégration potentiels

| Fichier Miroir | Intégration |
|----------------|-------------|
| `OnyxEpdPort.kt` | Logger les appels `setRawDrawingEnabled()`, `refresh()` |
| `AndroidEpdPort.kt` | Logger le fallback standard |
| `FontaineOverlay.kt` | Logger le cycle de vie (`activer`/`desactiver`/`keepRawDrawingActive`) |
| `CaptureActivity.kt` | `collectDiagnostics()` dans le menu paramètres |

---

## Statut

📌 **Noté — à intégrer ultérieurement.**  
Le buffer LLM existant sert de référence d'implémentation.  
Priorité actuelle : navigation permissive + alignement capture↔note.

---

*Capitaine à la barre. Documenté le 1er août 2026.*
