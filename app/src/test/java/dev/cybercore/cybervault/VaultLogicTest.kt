package dev.cybercore.cybervault

import dev.cybercore.cybervault.core.EntryInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultLogicTest {
    private val entries = listOf(
        EntryInfo("github", "2026-10-20", "work account"),
        EntryInfo("Cloudflare", "2026-10-20", null),
        EntryInfo("bank", "2026-10-19", "github recovery codes"),
    )

    @Test
    fun searchMatchesLabelsAndNotesIgnoringCase() {
        assertEquals(entries, filterEntries(entries, "  "))
        assertEquals(listOf("Cloudflare"), filterEntries(entries, "cloud").map { it.label })
        assertEquals(listOf("github", "bank"), filterEntries(entries, "GITHUB").map { it.label })
        assertTrue(filterEntries(entries, "nothing-matches").isEmpty())
    }

    @Test
    fun strengthBandsMatchEntropy() {
        assertEquals(Strength.Weak, Strength.of(30.0))
        assertEquals(Strength.Fair, Strength.of(60.0))
        assertEquals(Strength.Strong, Strength.of(77.5))
        assertEquals(Strength.Fortress, Strength.of(129.0))
    }

    @Test
    fun roughEntropyGrowsWithLengthAndVariety() {
        assertEquals(0.0, roughEntropy(""), 0.0)
        assertTrue(roughEntropy("aaaaaaaaaa") < roughEntropy("aA1!aA1!aA"))
        assertTrue(roughEntropy("correct horse battery staple") > 100)
    }
}
