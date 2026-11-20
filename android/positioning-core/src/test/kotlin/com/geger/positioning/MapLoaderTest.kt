package com.geger.positioning

import com.geger.positioning.map.EdgeKind
import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

/**
 * Tests for map loading and validation (M1 checks: "sample map loads; broken maps are rejected
 * with clear messages").
 *
 * Concept: each broken-map test makes exactly one thing wrong and checks that the error message
 * names that thing, so a tester reading the message knows what to fix.
 */
class MapLoaderTest {

    /** Loads [json], expects failure, and returns the error messages. */
    private fun errorsOf(json: String): List<String> = when (val r = MapLoader.parse(json)) {
        is MapLoadResult.Failed -> r.errors
        is MapLoadResult.Ok -> fail("expected the map to be rejected, but it loaded (warnings: ${r.warnings})")
    }

    /** Asserts some message in [messages] contains every one of [fragments]. */
    private fun assertMentions(messages: List<String>, vararg fragments: String) {
        assertTrue(messages.any { m -> fragments.all { m.contains(it) } }) {
            "expected a message containing ${fragments.toList()}, got: $messages"
        }
    }

    @Test
    fun `sample map loads with no errors or warnings`() {
        val r = MapLoader.loadFile(TestMaps.sampleFile)
        assertTrue(r is MapLoadResult.Ok) { "sample map failed: $r" }
        r as MapLoadResult.Ok
        assertEquals(emptyList<String>(), r.warnings)
        assertEquals(2, r.map.levels.size)
        assertEquals(8, r.map.nodes.size)
        assertEquals(EdgeKind.ESCALATOR_UP, r.map.edges.first { it.fromId == "n3" && it.toId == "n6" }.kind)
        assertEquals(0.27, r.map.anchors.getValue("A02").sizeM)
    }

    @Test
    fun `missing file is reported`() {
        val r = MapLoader.loadFile(java.io.File("/no/such/map.json"))
        assertTrue(r is MapLoadResult.Failed)
        assertMentions((r as MapLoadResult.Failed).errors, "not found")
    }

    @Test
    fun `malformed JSON is rejected`() {
        assertMentions(errorsOf("{ not json"), "Invalid map JSON")
    }

    @Test
    fun `missing required field is rejected`() {
        assertMentions(errorsOf("""{"schema": 1, "venue": "x", "units": "m", "levels": []}"""), "Invalid map JSON", "version")
    }

    @Test
    fun `number written as text is rejected`() {
        val text = TestMaps.sampleText().replace("{\"id\": \"n1\", \"level\": \"L1\", \"x\": 2.0,", "{\"id\": \"n1\", \"level\": \"L1\", \"x\": \"2.0\",")
        assertMentions(errorsOf(text), "Invalid map JSON", "found text \"2.0\"")
    }

    @Test
    fun `wrong schema and units are rejected`() {
        val text = TestMaps.sampleText().replace("\"schema\": 1", "\"schema\": 2").replace("\"units\": \"m\"", "\"units\": \"ft\"")
        val errors = errorsOf(text)
        assertMentions(errors, "schema", "2")
        assertMentions(errors, "units", "ft")
    }

    @Test
    fun `duplicate node id is rejected`() {
        val text = TestMaps.sampleText().replace("\"id\": \"n9\"", "\"id\": \"n1\"")
        assertMentions(errorsOf(text), "nodes", "'n1'", "2 times")
    }

    @Test
    fun `edge to unknown node is rejected`() {
        val text = TestMaps.sampleText().replace("{\"from\": \"n6\", \"to\": \"n10\", \"kind\": \"walk\"}", "{\"from\": \"n6\", \"to\": \"n99\", \"kind\": \"walk\"}")
        assertMentions(errorsOf(text), "unknown node 'n99'")
    }

    @Test
    fun `unknown edge kind is rejected with the allowed list`() {
        val text = TestMaps.sampleText().replace("\"kind\": \"lift\"", "\"kind\": \"elevator\"")
        assertMentions(errorsOf(text), "unknown kind 'elevator'", "lift")
    }

    @Test
    fun `escalator_up that goes down is rejected`() {
        val text = TestMaps.sampleText().replace("\"kind\": \"escalator_up\"", "\"kind\": \"escalator_down\"")
        assertMentions(errorsOf(text), "escalator_down must go down", "n3")
    }

    @Test
    fun `walk edge between levels is rejected`() {
        val text = TestMaps.sampleText().replace("\"kind\": \"lift\"", "\"kind\": \"walk\"")
        assertMentions(errorsOf(text), "walk edge must stay on one level")
    }

    @Test
    fun `angle outside 0 to 360 is rejected`() {
        val text = TestMaps.sampleText().replace("\"heading_deg\": 90}", "\"heading_deg\": 400}")
        assertMentions(errorsOf(text), "heading_deg", "[0, 360)", "400")
    }

    @Test
    fun `unknown level reference is rejected`() {
        val text = TestMaps.sampleText().replace("{\"id\": \"C04\", \"level\": \"L2\"", "{\"id\": \"C04\", \"level\": \"L9\"")
        assertMentions(errorsOf(text), "C04", "unknown level 'L9'")
    }

    @Test
    fun `non-positive plate size is rejected`() {
        val text = TestMaps.sampleText().replace("\"size_m\": 0.27", "\"size_m\": 0")
        assertMentions(errorsOf(text), "A02", "size_m")
    }

    @Test
    fun `self-crossing polygon is rejected`() {
        val bowTie = """[{"level": "L1", "polygon": [[0,0],[10,10],[10,0],[0,6]]}]"""
        assertMentions(errorsOf(TestMaps.minimal(walkable = bowTie)), "walkable[0]", "cross")
    }

    @Test
    fun `polygon with too few points is rejected`() {
        val line = """[{"level": "L1", "polygon": [[0,0],[10,0]]}]"""
        assertMentions(errorsOf(TestMaps.minimal(walkable = line)), "walkable[0]", "at least 3 points")
    }

    @Test
    fun `malformed polygon point is rejected`() {
        val bad = """[{"level": "L1", "polygon": [[0,0],[10,0,5],[10,10]]}]"""
        assertMentions(errorsOf(TestMaps.minimal(walkable = bad)), "walkable[0]", "point 1")
    }

    @Test
    fun `destination pointing at unknown node is rejected`() {
        val text = TestMaps.sampleText().replace("\"node\": \"n10\"", "\"node\": \"nX\"")
        assertMentions(errorsOf(text), "D1", "unknown node 'nX'")
    }

    @Test
    fun `node outside walkable space gives a warning, not an error`() {
        val json = TestMaps.minimal(
            nodes = """[{"id": "a", "level": "L1", "x": 5, "y": 5}, {"id": "b", "level": "L1", "x": 15, "y": 5}]""",
            edges = """[{"from": "a", "to": "b", "kind": "walk"}]""",
        )
        val r = MapLoader.parse(json)
        assertTrue(r is MapLoadResult.Ok) { "expected Ok, got $r" }
        assertMentions(r.warnings, "node 'b'", "outside walkable space")
        assertMentions(r.warnings, "edge a→b", "leaves walkable space")
    }

    @Test
    fun `edge through an obstacle gives a warning`() {
        val json = TestMaps.minimal(
            obstacles = """[{"level": "L1", "polygon": [[4,4],[6,4],[6,6],[4,6]]}]""",
            nodes = """[{"id": "a", "level": "L1", "x": 1, "y": 5}, {"id": "b", "level": "L1", "x": 9, "y": 5}]""",
            edges = """[{"from": "a", "to": "b", "kind": "walk"}]""",
        )
        val r = MapLoader.parse(json)
        assertTrue(r is MapLoadResult.Ok)
        assertMentions(r.warnings, "edge a→b", "leaves walkable space")
    }

    @Test
    fun `unconnected node gives a warning`() {
        val json = TestMaps.minimal(nodes = """[{"id": "lonely", "level": "L1", "x": 5, "y": 5}]""")
        val r = MapLoader.parse(json)
        assertTrue(r is MapLoadResult.Ok)
        assertMentions(r.warnings, "node 'lonely'", "no edges")
    }
}
