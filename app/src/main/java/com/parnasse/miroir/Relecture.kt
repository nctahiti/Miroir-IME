package com.parnasse.miroir

/**
 * RELECTURE — l'encre et le sens ont deux régimes.
 *
 * Le label d'un groupe a deux états, et un seul propriétaire à la fois :
 *
 *   nominal — ce que l'inférence a entendu dans l'encre ;
 *   courant — ce que la page doit lire (le corrigé, dès qu'un correcteur parle).
 *
 * Un groupe est CORRIGÉ quand les deux diffèrent. Tant qu'il l'est, l'inférence n'a plus la
 * plume sur ce groupe : seuls un geste ou un fait la lui rendent —
 *
 *   · ABSORBER un trait neuf  → l'intention s'agrandit, l'inférence repart ;
 *   · TENIR                   → je ratifie le corrigé, la dette s'éteint ;
 *   · REVENIR                 → le correcteur s'est trompé, le nominal reprend sa place.
 *
 * Le REMPLACEMENT des traits (réécrire par-dessus un mot corrigé, en relecture) n'est PAS une
 * intention nouvelle : l'encre change de main, le sens reste. C'est la loi de la relecture.
 *
 * Cinq verbes sont tout le vocabulaire de la fonction :
 *
 *   poserLabelInfere  l'inférence pose — elle n'écrase jamais un corrigé
 *   corriger          la main corrige — le nominal est gravé au premier écart, et n'en bouge plus
 *   tenir             je ratifie — le nominal s'aligne sur le courant
 *   rendre            je rends — le courant revient au nominal
 *   estCorrige        la question que tout le reste pose
 *
 * ⚠️ Une seule source : ces deux cartes SONT le label du groupe. Nul autre n'en garde copie —
 * les vues (`groupLabels`, `originalLabels`) qui existaient ailleurs en sont des alias.
 */
class Relecture {

    /** firstIdx → ce que la page lit (le courant). */
    val labels = mutableMapOf<Int, String>()

    /** firstIdx → ce que l'inférence avait entendu (le nominal). */
    val nominaux = mutableMapOf<Int, String>()

    /** Le groupe porte-t-il une correction non ratifiée ? */
    fun estCorrige(firstIdx: Int): Boolean =
        nominaux[firstIdx]?.let { it != labels[firstIdx] } == true

    /**
     * L'inférence pose son label.
     * Rend false si le groupe est corrigé : le corrigé tient, l'inférence se tait.
     */
    fun poserLabelInfere(firstIdx: Int, label: String): Boolean {
        if (estCorrige(firstIdx)) return false
        labels[firstIdx] = label
        nominaux[firstIdx] = label
        return true
    }

    /**
     * La main corrige (lettre, insertion, suppression, plume).
     * Le nominal est gravé au premier écart — jamais écrasé par une correction suivante.
     */
    fun corriger(firstIdx: Int, label: String) {
        if (nominaux[firstIdx] == null) nominaux[firstIdx] = labels[firstIdx] ?: label
        labels[firstIdx] = label
    }

    /** Tenir — le corrigé devient nominal : la dette de révision s'éteint. */
    fun tenir(firstIdx: Int) {
        labels[firstIdx]?.let { nominaux[firstIdx] = it }
    }

    /**
     * Revenir — le nominal reprend sa place.
     * @param repli label à poser si aucun nominal n'a jamais été gravé (groupe jamais inféré).
     */
    fun rendre(firstIdx: Int, repli: String? = null) {
        val nominal = nominaux[firstIdx]
        if (nominal != null) labels[firstIdx] = nominal
        else if (repli != null) labels[firstIdx] = repli
    }

    /** La page change : les deux cartes repartent ensemble. */
    fun oublier() {
        labels.clear()
        nominaux.clear()
    }

    /** Le groupe meurt : ses deux états meurent avec lui. */
    fun retirer(firstIdx: Int) {
        labels.remove(firstIdx)
        nominaux.remove(firstIdx)
    }
}
