# Fallback doigts — Pixel 6a (25 juillet 2026)

## Contexte

Sur les appareils sans EPD Onyx (Pixel 6a), la Fontaine est désactivée
(`FontaineOverlay.isAvailable()` → false car `Build.MANUFACTURER != "ONYX"`).
Le rendu doit passer par `CaptureView.onDraw()` standard.

## Problème

Le TouchHelper Onyx embarqué dans l'APK (via les JARs) s'initialise quand même
sur le Pixel → `useTouchHelper = true`. Résultat : `CaptureView.onTouchEvent()`
ignore les événements `TOOL_TYPE_FINGER` (ligne 554 : seul `TOOL_TYPE_STYLUS`
est accepté). Sans Fontaine et sans TouchHelper effectif, personne ne capture
les strokes → écran muet.

## Solution nécessaire

1. **`CaptureView.onTouchEvent()`** : accepter aussi `TOOL_TYPE_FINGER` quand
   `!useTouchHelper` (fallback).
```kotlin
val isStylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
val isFinger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
if (isStylus || (isFinger && !useTouchHelper)) {
    handleCaptureEvent(event)
}
```

2. **`CaptureView.disableTouchHelper()`** : méthode publique pour forcer
   `useTouchHelper = false` + `touchHelperAttempted = true`.

3. **`CaptureActivity.onCreate()`** : après `setContentView(root)`, appeler
   `captureView?.disableTouchHelper()` si `fontaineOverlay == null`.

## Bloqueur

Le build Kotlin ne résout pas `disableTouchHelper()` même après `clean`.
Cause probable : cache Gradle ou conflit avec d'autres versions archivées
de `CaptureView.kt` dans `.archives/` et `archive/`.

→ Revert au commit `a89a4d8` (build fonctionnel). À reprendre.

## État au 25 juillet 2026

- Fontaine désactivée sur Pixel ✅ (détection fabricant)
- `onDraw` standard fonctionne ✅
- Capture des doigts ❌ (bloquée par `useTouchHelper=true`)
- Build bloqué ❌ (résolution `disableTouchHelper()`)
