package fr.yannickuhrig.vyzo.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvScrimTest {
    private val stops = listOf(0.2f to 1f, 0.6f to 0.5f, 0.8f to 0f)

    @Test
    fun lOpaciteVarieLineairementEntreDeuxPaliers() {
        assertEquals(0.75f, stopAlpha(stops, 0.4f), 0.001f)
        assertEquals(0.25f, stopAlpha(stops, 0.7f), 0.001f)
    }

    @Test
    fun lOpaciteEstCelleDuPalierExact() {
        assertEquals(0.5f, stopAlpha(stops, 0.6f), 0.001f)
    }

    @Test
    fun avantLePremierEtApresLeDernierPalierLOpaciteNeChangePlus() {
        assertEquals(1f, stopAlpha(stops, 0f), 0.001f)
        assertEquals(0f, stopAlpha(stops, 1f), 0.001f)
    }

    @Test
    fun deuxPaliersConfondusFontUnSautNet() {
        val saut = listOf(0f to 0f, 0.5f to 0f, 0.5f to 1f, 1f to 1f)
        assertEquals(0f, stopAlpha(saut, 0.5f), 0.001f)
        assertEquals(1f, stopAlpha(saut, 0.51f), 0.001f)
    }

    @Test
    fun deuxDegradesSuperposesSeCumulent() {
        val moitie = listOf(0f to 0.5f, 1f to 0.5f)
        assertEquals(0.75f, scrimAlpha(moitie, moitie, 0.3f, 0.7f), 0.001f)
    }

    @Test
    fun unDegradeTransparentLaisseLAutreInchange() {
        val vide = listOf(0f to 0f, 1f to 0f)
        assertEquals(0.75f, scrimAlpha(stops, vide, 0.4f, 0.9f), 0.001f)
    }

    @Test
    fun laLueurEstPleineAuCentreEtSEteintAuRayon() {
        assertEquals(1f, glowAlpha(0.5f, 0.5f, 0.2f, 0.5f, 0.5f), 0.001f)
        assertEquals(0.5f, glowAlpha(0.5f, 0.5f, 0.2f, 0.6f, 0.5f), 0.001f)
        assertEquals(0f, glowAlpha(0.5f, 0.5f, 0.2f, 0.9f, 0.5f), 0.001f)
    }

    @Test
    fun laLueurResteRondeSurUnEcranLarge() {
        val versLaDroite = glowAlpha(0.5f, 0.5f, 0.4f, 0.5f + 0.1f, 0.5f)
        val versLeBas = glowAlpha(0.5f, 0.5f, 0.4f, 0.5f, 0.5f + 0.1f * 16f / 9f)
        assertEquals(versLaDroite, versLeBas, 0.001f)
    }

    @Test
    fun unDegradeOpaqueCouvreTout() {
        val plein = listOf(0f to 1f, 1f to 1f)
        assertEquals(1f, scrimAlpha(plein, stops, 0.4f, 0.9f), 0.001f)
    }
}
