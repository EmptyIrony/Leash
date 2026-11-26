package dev.leash.personal.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LeashPaletteTest {
    @Test
    fun accentMatchesIos() {
        assertEquals(0x4B50E6L, LeashPalette.accent.light)
        assertEquals(0x8088FFL, LeashPalette.accent.dark)
    }

    @Test
    fun resolveAppliesModeAndOpacity() {
        val raisedDark = LeashPalette.bgRaised.resolve(dark = true)
        assertEquals(0.85f, raisedDark.alpha, 0.01f)
        assertEquals(1f, LeashPalette.bgRaised.resolve(dark = false).alpha, 0f)
        assertNotEquals(LeashPalette.bgBase.resolve(true), LeashPalette.bgBase.resolve(false))
    }

    @Test
    fun colorSchemesAreOpaqueSurfaces() {
        for (dark in listOf(false, true)) {
            assertEquals(1f, leashColorScheme(dark).surfaceContainer.alpha, 0f)
            assertEquals(1f, leashColorScheme(dark).outlineVariant.alpha, 0f)
        }
    }
}
