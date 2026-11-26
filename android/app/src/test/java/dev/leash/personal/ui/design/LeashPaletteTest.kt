package dev.leash.personal.ui.design

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
    fun tokensApplyModeAndOpacity() {
        val dark = LeashDesignColors.of(dark = true)
        val light = LeashDesignColors.of(dark = false)
        assertEquals(0.85f, dark.bgRaised.alpha, 0.01f)
        assertEquals(1f, light.bgRaised.alpha, 0f)
        assertNotEquals(dark.bgBase, light.bgBase)
    }

    @Test
    fun opaqueRolesForMaterialSlots() {
        for (dark in listOf(false, true)) {
            val c = LeashDesignColors.of(dark)
            assertEquals(1f, c.opaque(c.bgRaised).alpha, 0f)
            assertEquals(1f, c.opaque(c.hairline).alpha, 0f)
        }
    }

    @Test
    fun accentLabelsReadOnTheirFill() {
        // white on the light accent, page ink on the light dark-mode accent (Fable: white there is 2.7:1)
        assertEquals(androidx.compose.ui.graphics.Color.White, LeashDesignColors.of(dark = false).onAccent)
        assertEquals(androidx.compose.ui.graphics.Color(0xFF07080D), LeashDesignColors.of(dark = true).onAccent)
    }
}
