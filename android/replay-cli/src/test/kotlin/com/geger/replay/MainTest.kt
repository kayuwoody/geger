package com.geger.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for the CLI commands, run in-process via [runCli] with captured output.
 */
class MainTest {

    private val out = mutableListOf<String>()
    private val err = mutableListOf<String>()

    private fun cli(vararg args: String) = runCli(args.toList(), { out += it }, { err += it })

    @Test
    fun `validate-map accepts the sample map`() {
        val sample = System.getProperty("geger.sampleMap")
        assertEquals(0, cli("validate-map", sample))
        assertTrue(out.single().startsWith("OK: sample-two-level v1")) { out.toString() }
    }

    @Test
    fun `validate-map rejects a broken map with exit code 1`() {
        val broken = File.createTempFile("broken", ".json").apply { writeText("{ nope"); deleteOnExit() }
        assertEquals(1, cli("validate-map", broken.path))
        assertTrue(err.any { it.startsWith("ERROR: Invalid map JSON") }) { err.toString() }
    }

    @Test
    fun `unknown command prints usage`() {
        assertEquals(2, cli("frobnicate"))
        assertTrue(err.single().startsWith("Usage:"))
    }

    @Test
    fun `validate-config reports a misspelt field`() {
        val cfg = File.createTempFile("cfg", ".json").apply { writeText("""{"qr": {"detectHz": 3}}"""); deleteOnExit() }
        assertEquals(1, cli("validate-config", cfg.path))
        assertTrue(err.single().contains("detectHz")) { err.toString() }
    }
}
