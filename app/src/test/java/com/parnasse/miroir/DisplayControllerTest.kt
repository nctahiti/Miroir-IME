package com.parnasse.miroir

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test de régression du séquenceur (État A) — Règle d'Or.
 *
 * [FakeEpdPort] enregistre la séquence EXACTE des appels EPD émis par l'organe.
 * Aucune dépendance Android/Onyx : tourne en JVM pure.
 *   ./gradlew :app:testDebugUnitTest
 */
class DisplayControllerTest {

    /** Faux port : journalise chaque appel dans l'ordre où il survient. */
    private class FakeEpdPort : EpdPort {
        val journal = mutableListOf<String>()
        override fun setHandwritingPenState(on: Boolean) { journal += "penState=" + if (on) "ON" else "OFF" }
        override fun enablePost(on: Boolean) { journal += "post=" + if (on) "ON" else "OFF" }
        override fun setDefaultMode(mode: DisplayMode) { journal += "defaultMode=$mode" }
        override fun refresh(mode: DisplayMode) { journal += "refresh=$mode" }
    }

    @Test
    fun entrerEcriture_ouvre_le_DU_plein() {
        val port = FakeEpdPort()
        DisplayController(port).entrerEcriture()
        assertEquals(listOf("penState=ON", "post=OFF", "defaultMode=DU"), port.journal)
    }

    @Test
    fun reasserterDU_maintient_sans_changer_le_mode_par_defaut() {
        val port = FakeEpdPort()
        DisplayController(port).reasserterDU()
        // Pas de defaultMode ici : on MAINTIENT, on ne RE-déclare pas le mode.
        assertEquals(listOf("penState=ON", "post=OFF"), port.journal)
    }

    @Test
    fun poserLabelPuisDU_pose_PUIS_revient_en_DU() {
        // Le cœur de la fracture A : le retour DU doit suivre le refresh, dans CET ordre.
        val port = FakeEpdPort()
        DisplayController(port).poserLabelPuisDU(DisplayMode.GU)
        assertEquals(listOf("refresh=GU", "penState=ON", "post=OFF"), port.journal)
    }

    @Test
    fun poserLabelPuisDU_respecte_le_mode_de_pose_parametrable() {
        val port = FakeEpdPort()
        DisplayController(port).poserLabelPuisDU(DisplayMode.REGAL)
        assertEquals(listOf("refresh=REGAL", "penState=ON", "post=OFF"), port.journal)
    }

    @Test
    fun entrerVue_sort_du_DU_vers_le_mode_demande() {
        val port = FakeEpdPort()
        DisplayController(port).entrerVue(DisplayMode.GU)
        assertEquals(listOf("penState=OFF", "post=ON", "defaultMode=GU"), port.journal)
    }
}
