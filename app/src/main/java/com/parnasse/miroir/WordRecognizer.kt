package com.parnasse.miroir

/**
 * Interface minimale de reconnaissance mot-à-mot.
 *
 * Le Miroir capture des strokes, les groupe en mots,
 * et passe chaque groupe à un WordRecognizer qui retourne du texte.
 *
 * Implémentations possibles :
 *   - GoogleInkRecognizer (scribe_latin TFLite via ML Kit)
 *   - OnnxRecognizer (modèle custom ONNX, pour expérimentation future)
 *   - HybridRecognizer (Google + fallback custom)
 */
interface WordRecognizer {
    /** État de chargement du modèle */
    val isLoaded: Boolean

    /**
     * Reconnaît un groupe de strokes comme un mot.
     *
     * @param strokes Registre complet des strokes de la session
     * @param group Indices des strokes qui forment le mot à reconnaître
     * @return Texte reconnu, ou "" si échec
     */
    fun recognize(strokes: List<StrokeRecord>, group: List<Int>): String

    /** Libère les ressources du modèle */
    fun close()
}
