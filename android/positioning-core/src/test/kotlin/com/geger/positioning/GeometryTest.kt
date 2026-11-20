package com.geger.positioning

import com.geger.positioning.geometry.Polygon
import com.geger.positioning.geometry.Segments
import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.Walkability
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for polygons, segment maths and walkability (M1 check: "wall-crossing edge cases").
 *
 * Concept: the edge cases that break naive implementations are points exactly on outlines,
 * rays through vertices, overlapping walkable polygons, collinear paths along walls, and paths
 * that leave and re-enter a concave area. Each gets its own test.
 */
class GeometryTest {

    private fun v(x: Double, y: Double) = Vec2(x, y)
    private val square = Polygon(listOf(v(0.0, 0.0), v(10.0, 0.0), v(10.0, 10.0), v(0.0, 10.0)))

    /** A U shape: a 10 × 10 square with a notch cut from the top middle (x 4..6, y 3..10). */
    private val uShape = Polygon(
        listOf(v(0.0, 0.0), v(10.0, 0.0), v(10.0, 10.0), v(6.0, 10.0), v(6.0, 3.0), v(4.0, 3.0), v(4.0, 10.0), v(0.0, 10.0)),
    )

    @Test
    fun `point in polygon inside, outside, on edge and on vertex`() {
        assertTrue(square.containsInclusive(v(5.0, 5.0)))
        assertFalse(square.containsInclusive(v(15.0, 5.0)))
        assertTrue(square.containsInclusive(v(10.0, 5.0)), "on edge counts as inside (inclusive)")
        assertTrue(square.containsInclusive(v(0.0, 0.0)), "on vertex counts as inside (inclusive)")
        assertFalse(square.containsStrict(v(10.0, 5.0)), "on edge is not strictly inside")
        assertTrue(square.containsStrict(v(5.0, 5.0)))
    }

    @Test
    fun `ray through a vertex is counted once`() {
        // A diamond: a ray from (0,0) towards +x passes exactly through the vertex (5, 0).
        val diamond = Polygon(listOf(v(5.0, -5.0), v(10.0, 0.0), v(5.0, 5.0), v(0.0, 0.0)))
        assertTrue(diamond.containsInclusive(v(4.0, 0.0)))
        assertFalse(diamond.containsInclusive(v(-1.0, 0.0)))
        assertFalse(diamond.containsInclusive(v(11.0, 0.0)))
    }

    @Test
    fun `concave polygon notch is outside`() {
        assertTrue(uShape.containsInclusive(v(2.0, 8.0)))
        assertFalse(uShape.containsInclusive(v(5.0, 8.0)), "inside the notch is outside the U")
        assertTrue(uShape.containsInclusive(v(5.0, 1.0)))
    }

    @Test
    fun `polygon problems are described`() {
        assertNull(square.problem())
        assertNull(uShape.problem())
        assertNotNull(Polygon(listOf(v(0.0, 0.0), v(10.0, 10.0), v(10.0, 0.0), v(0.0, 10.0))).problem())
        assertNotNull(Polygon(listOf(v(0.0, 0.0), v(5.0, 0.0), v(10.0, 0.0))).problem(), "zero area")
        assertNotNull(Polygon(listOf(v(0.0, 0.0), v(0.0, 0.0), v(10.0, 0.0), v(0.0, 10.0))).problem(), "repeated point")
    }

    @Test
    fun `segment crossing parameters`() {
        val t = Segments.crossingParams(v(0.0, 0.0), v(10.0, 0.0), v(5.0, -1.0), v(5.0, 1.0))
        assertEquals(1, t.size)
        assertEquals(0.5, t[0], 1e-12)
        assertTrue(Segments.crossingParams(v(0.0, 0.0), v(10.0, 0.0), v(0.0, 1.0), v(10.0, 1.0)).isEmpty(), "parallel, apart")
        val overlap = Segments.crossingParams(v(0.0, 0.0), v(10.0, 0.0), v(2.0, 0.0), v(4.0, 0.0))
        assertEquals(listOf(0.2, 0.4), overlap.map { Math.round(it * 1e9) / 1e9 }, "collinear overlap")
    }

    // ---- Walkability on maps --------------------------------------------------------------

    private val roomWithPillar = TestMaps.loadOk(
        TestMaps.minimal(obstacles = """[{"level": "L1", "polygon": [[4,4],[6,4],[6,6],[4,6]]}]"""),
    )

    @Test
    fun `walkable excludes obstacle interior but includes its outline`() {
        val w = Walkability(roomWithPillar)
        assertTrue(w.isWalkable("L1", v(1.0, 1.0)))
        assertFalse(w.isWalkable("L1", v(5.0, 5.0)), "inside the pillar")
        assertTrue(w.isWalkable("L1", v(4.0, 5.0)), "touching the pillar")
        assertFalse(w.isWalkable("L1", v(11.0, 5.0)), "outside the room")
        assertFalse(w.isWalkable("L9", v(1.0, 1.0)), "unknown level")
    }

    @Test
    fun `path through obstacle crosses a wall, path around it does not`() {
        val w = Walkability(roomWithPillar)
        assertTrue(w.pathCrossesWall("L1", v(1.0, 5.0), v(9.0, 5.0)))
        assertFalse(w.pathCrossesWall("L1", v(1.0, 2.0), v(9.0, 2.0)))
    }

    @Test
    fun `path sliding along an obstacle face is allowed`() {
        val w = Walkability(roomWithPillar)
        assertFalse(w.pathCrossesWall("L1", v(4.0, 3.0), v(4.0, 7.0)))
    }

    @Test
    fun `path along the room's own wall is allowed`() {
        val w = Walkability(roomWithPillar)
        assertFalse(w.pathCrossesWall("L1", v(0.0, 1.0), v(0.0, 9.0)))
    }

    @Test
    fun `path that starts or ends outside crosses a wall`() {
        val w = Walkability(roomWithPillar)
        assertTrue(w.pathCrossesWall("L1", v(-1.0, 1.0), v(2.0, 1.0)))
        assertTrue(w.pathCrossesWall("L1", v(2.0, 1.0), v(12.0, 1.0)))
    }

    @Test
    fun `zero-length path only checks the point`() {
        val w = Walkability(roomWithPillar)
        assertFalse(w.pathCrossesWall("L1", v(1.0, 1.0), v(1.0, 1.0)))
        assertTrue(w.pathCrossesWall("L1", v(5.0, 5.0), v(5.0, 5.0)))
    }

    @Test
    fun `crossing between overlapping walkable polygons is allowed`() {
        val map = TestMaps.loadOk(
            TestMaps.minimal(
                walkable = """[
                  {"level": "L1", "polygon": [[0,0],[10,0],[10,4],[0,4]]},
                  {"level": "L1", "polygon": [[6,0],[10,0],[10,20],[6,20]]}
                ]""",
            ),
        )
        val w = Walkability(map)
        // From the horizontal corridor, across the shared region, up the vertical corridor.
        assertFalse(w.pathCrossesWall("L1", v(7.0, 1.0), v(8.0, 15.0)))
        // Cutting the inside corner of the L leaves walkable space.
        assertTrue(w.pathCrossesWall("L1", v(1.0, 2.0), v(8.0, 15.0)))
    }

    @Test
    fun `path that leaves and re-enters a concave area crosses a wall`() {
        val map = TestMaps.loadOk(
            TestMaps.minimal(
                walkable = """[{"level": "L1", "polygon": [[0,0],[10,0],[10,10],[6,10],[6,3],[4,3],[4,10],[0,10]]}]""",
            ),
        )
        val w = Walkability(map)
        assertTrue(w.pathCrossesWall("L1", v(2.0, 8.0), v(8.0, 8.0)), "straight across the notch")
        assertFalse(w.pathCrossesWall("L1", v(2.0, 2.0), v(8.0, 2.0)), "below the notch")
    }
}
