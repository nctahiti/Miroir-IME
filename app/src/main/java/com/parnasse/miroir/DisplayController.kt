package com.parnasse.miroir

/**
 * Le chef d'orchestre du mode d'affichage — l'organe extrait du séquenceur (État A).
 *
 * Les gestes ne sont pas inventés : ils sont IMPORTÉS des deux rives existantes.
 *   - entrée DU   : CaptureView L509-511  ≡  MiroirIME.enterWriteMode L321-323
 *   - re-assert   : CaptureView L1724-1725 (maintien du DU à chaque pen-up)
 *   - sortie/vue  : CaptureView L531-533 (GU)  ≡  MiroirIME.enterViewMode L335-337 (REGAL)
 *
 * Aucune dépendance Android/Onyx : tout passe par [EpdPort] -> testable en JVM pure.
 *
 * PAS 1 (squelette muet) : AUCUNE rive n'est encore branchée dessus. Le comportement
 * de la tablette est strictement inchangé tant que CaptureView / MiroirIME ne l'appellent pas.
 */
class DisplayController(private val port: EpdPort) {

    /**
     * Entrer en écriture : DU plein, l'encre suit le stylet en direct.
     * (CaptureView L509-511 ≡ MiroirIME.enterWriteMode L321-323)
     */
    fun entrerEcriture() {
        port.setHandwritingPenState(true)
        port.enablePost(false)
        port.setDefaultMode(DisplayMode.DU)
    }

    /**
     * Geste (a) — maintenir le DU au lever du stylet.
     * Le driver Onyx dérive GU->500 ms à chaque pen-up : on le force à rester en DU.
     * Importé de CaptureView L1724-1725. ABSENT de MiroirIME.onStylusUp aujourd'hui (fracture A).
     */
    fun reasserterDU() {
        port.setHandwritingPenState(true)
        port.enablePost(false)
    }

    /**
     * Geste (b) — poser le label (refresh ponctuel) PUIS revenir en DU, indissociablement.
     *
     * C'est exactement la fracture A : côté IME, refreshAll() (= refreshScreen GU, L1102/307)
     * pose le label mais NE revient jamais en DU. Ici la pose et le retour sont atomiques.
     *
     * @param modePose GU ou REGAL — paramétrable, à régler au stylet via le futur banc d'essai.
     */
    fun poserLabelPuisDU(modePose: DisplayMode) {
        port.refresh(modePose)
        reasserterDU()
    }

    /**
     * Sortir de l'écriture vers le mode vue (transition vers l'édition B/C).
     * (CaptureView L531-533 : GU  ≡  MiroirIME.enterViewMode L335-337 : REGAL)
     *
     * @param mode GU ou REGAL — paramétrable selon la rive / le réglage.
     */
    fun entrerVue(mode: DisplayMode) {
        port.setHandwritingPenState(false)
        port.enablePost(true)
        port.setDefaultMode(mode)
    }
}
