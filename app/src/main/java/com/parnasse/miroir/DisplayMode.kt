package com.parnasse.miroir

/**
 * Modes d'affichage EPD du séquenceur, découplés du SDK Onyx.
 *
 * On NE référence PAS directement `UpdateMode` (Onyx) dans le noyau : cet enum maison
 * rend `DisplayController` testable en JVM pure (sans Android, sans tablette).
 * La traduction DisplayMode -> UpdateMode vit uniquement dans l'adaptateur OnyxEpdPort.
 */
enum class DisplayMode {
    /** Direct Update — l'encre suit le stylet en direct, pas de rafraîchissement. (écriture) */
    DU,
    /** Gray Update — rafraîchissement général ponctuel. (CaptureView L531) */
    GU,
    /** Regal — mode vue optimisé texte, ~120 ms. (MiroirIME.enterViewMode L337) */
    REGAL
}
