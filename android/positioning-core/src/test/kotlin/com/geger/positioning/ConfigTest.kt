package com.geger.positioning

import com.geger.positioning.config.TrialConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for the config file (SPEC.md rule 7).
 *
 * Concept: a saved config must load back identically; a partial file changes only what it names;
 * a misspelt field must fail loudly instead of being silently ignored.
 */
class ConfigTest {

    @Test
    fun `defaults round-trip through JSON`() {
        val defaults = TrialConfig.defaults()
        assertEquals(defaults, TrialConfig.fromJson(defaults.toJson()))
        assertTrue(defaults.toJson().contains("\"particleCount\": 1000"), "writer includes defaults")
    }

    @Test
    fun `partial file overrides only the fields it names`() {
        val c = TrialConfig.fromJson("""{"filter": {"particleCount": 500}, "steps": {"stepLengthM": 0.65}}""")
        assertEquals(500, c.filter.particleCount)
        assertEquals(0.65, c.steps.stepLengthM)
        assertEquals(TrialConfig.defaults().qr, c.qr)
        assertEquals(42L, c.filter.seed)
    }

    @Test
    fun `misspelt field is rejected with a clear message`() {
        val e = assertThrows<IllegalArgumentException> { TrialConfig.fromJson("""{"filter": {"particleCont": 500}}""") }
        assertTrue(e.message!!.contains("particleCont")) { e.message }
    }

    @Test
    fun `malformed JSON is rejected`() {
        assertThrows<IllegalArgumentException> { TrialConfig.fromJson("{ nope") }
    }
}
