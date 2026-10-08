package com.parnasse.miroir

/**
 * TokenIntention — la graine de la coquille IME.
 *
 * La coquille IME ne fait qu'une chose : faire NAÎTRE le token d'intention.
 *   onStartInput  → le token naît CHAUD (le champ a le focus)
 *   onFinishInput → le sceau tombe, le token est FIGÉ
 *
 * La charge utile est minimale : {texte, contexte}. Rien d'autre.
 * Pas de rattachement, pas d'inférence — la machine se tait pendant le
 * geste ; les arêtes se tissent après coup, par les trois axes.
 *
 * Le contexte reprend la clé de champ que l'ancienne coquille savait déjà
 * construire (buildFieldKey) : app hôte, identifiant du champ, nom du champ.
 * C'est l'identité du lieu de naissance.
 */
class TokenIntention(
    /** La clé du champ — l'identité du lieu de naissance (packageName, fieldId, fieldName). */
    val cleChamp: String,
    /** Le texte transcrit pendant la session — vide tant que la main n'a rien écrit. */
    val texte: String = "",
    /** Le moment de la naissance (onStartInput). */
    val naissance: Long = System.currentTimeMillis(),
) {
    /** Le sceau (onFinishInput) — null tant que le token est CHAUD. */
    var sceau: Long? = null
        private set

    val estChaud: Boolean get() = sceau == null
    val estFige: Boolean get() = sceau != null

    /** onFinishInput — le focus part, le token se fige. */
    fun sceller(instant: Long = System.currentTimeMillis()) {
        if (sceau == null) sceau = instant
    }
}

/**
 * La coquille est EPD-agnostique : la qualité d'affichage n'est pas essentielle.
 * La fontaine ne se règle pas — elle s'active et se désactive, c'est tout.
 */
interface FontaineCommande {
    fun activer()
    fun desactiver()
    val estActive: Boolean
}
