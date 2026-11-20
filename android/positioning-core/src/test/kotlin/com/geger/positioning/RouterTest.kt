package com.geger.positioning

import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.EdgeKind
import com.geger.positioning.routing.RouteOptions
import com.geger.positioning.routing.Router
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.sqrt

/**
 * Tests for A* routing on the sample map (M1 check: "A* routes across an escalator and respects
 * one-way edges").
 *
 * Sample map recap: L1 corridor n1–n2–n8–n3–n9; escalator UP n3→n6; escalator DOWN n7→n8;
 * lift n9↔n10; L2 nodes n6, n7, n10 all linked by walking.
 */
class RouterTest {

    private val map = TestMaps.sample()
    private val router = Router(map, liftPenaltyM = 20.0)

    @Test
    fun `route goes up the escalator rather than the lift`() {
        val r = checkNotNull(router.route("n1", "n10"))
        assertEquals(listOf("n1", "n2", "n8", "n3", "n6", "n10"), r.nodeIds)
        assertTrue(r.legs.any { it.kind == EdgeKind.ESCALATOR_UP })
        assertFalse(r.legs.any { it.kind == EdgeKind.LIFT })
    }

    @Test
    fun `step-free route uses the lift`() {
        val r = checkNotNull(router.route("n1", "n10", RouteOptions(stepFreeOnly = true)))
        assertEquals(listOf("n1", "n2", "n8", "n3", "n9", "n10"), r.nodeIds)
        assertTrue(r.legs.none { !it.kind.stepFree })
    }

    @Test
    fun `escalator up is never travelled downwards`() {
        // The reverse of escalator n3→n6 would be the shortest way down, but it is one-way.
        val r = checkNotNull(router.route("n6", "n3"))
        assertTrue(r.legs.none { it.fromId == "n6" && it.toId == "n3" })
        assertEquals(listOf("n6", "n7", "n8", "n3"), r.nodeIds, "should use the down escalator n7→n8")
        assertTrue(r.legs.any { it.kind == EdgeKind.ESCALATOR_DOWN })
    }

    @Test
    fun `legs are oriented in travel direction`() {
        val r = checkNotNull(router.route("n10", "n1"))
        for (i in r.legs.indices) {
            assertEquals(r.nodeIds[i], r.legs[i].fromId)
            assertEquals(r.nodeIds[i + 1], r.legs[i].toId)
        }
    }

    @Test
    fun `route length is the 3D distance and cost adds lift penalties`() {
        val r = checkNotNull(router.route("n9", "n10"))
        assertEquals(5.0, r.lengthM, 1e-9, "lift straight up one 5 m level")
        assertEquals(25.0, r.costM, 1e-9)
        val esc = checkNotNull(router.route("n3", "n6"))
        assertEquals(sqrt(10.0 * 10.0 + 5.0 * 5.0), esc.lengthM, 1e-9)
    }

    @Test
    fun `route to itself is a single node`() {
        val r = checkNotNull(router.route("n1", "n1"))
        assertEquals(listOf("n1"), r.nodeIds)
        assertEquals(0.0, r.lengthM)
    }

    @Test
    fun `unreachable node gives null`() {
        val m = TestMaps.loadOk(
            TestMaps.minimal(
                nodes = """[{"id": "a", "level": "L1", "x": 1, "y": 1}, {"id": "b", "level": "L1", "x": 2, "y": 1},
                           {"id": "island", "level": "L1", "x": 9, "y": 9}]""",
                edges = """[{"from": "a", "to": "b", "kind": "walk"}]""",
            ),
        )
        assertNull(Router(m).route("a", "island"))
    }

    @Test
    fun `step-free route that needs an escalator gives null`() {
        val m = TestMaps.loadOk(
            TestMaps.minimal(
                levels = """[{"id": "L1", "name": "1", "elevation_m": 0}, {"id": "L2", "name": "2", "elevation_m": 5}]""",
                walkable = """[{"level": "L1", "polygon": [[0,0],[10,0],[10,10],[0,10]]},
                               {"level": "L2", "polygon": [[0,0],[10,0],[10,10],[0,10]]}]""",
                nodes = """[{"id": "a", "level": "L1", "x": 1, "y": 1}, {"id": "b", "level": "L2", "x": 5, "y": 1}]""",
                edges = """[{"from": "a", "to": "b", "kind": "escalator_up"}]""",
            ),
        )
        assertNotNull(Router(m).route("a", "b"))
        assertNull(Router(m).route("a", "b", RouteOptions(stepFreeOnly = true)))
    }

    @Test
    fun `unknown node id is a programming error`() {
        assertThrows<IllegalArgumentException> { router.route("n1", "nope") }
    }

    @Test
    fun `nearest node on a level`() {
        assertEquals("n2", router.nearestNode("L1", Vec2(35.0, 3.0))?.id)
        assertEquals("n7", router.nearestNode("L2", Vec2(33.0, 21.0))?.id)
        assertNull(router.nearestNode("L9", Vec2(0.0, 0.0)))
    }
}
