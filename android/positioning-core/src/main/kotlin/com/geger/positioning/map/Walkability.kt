package com.geger.positioning.map

import com.geger.positioning.geometry.Segments
import com.geger.positioning.geometry.Vec2

/**
 * Answers "can a person stand here?" and "can a person walk straight from A to B?" on one level.
 *
 * Concept: walkable space is the UNION of the level's walkable polygons MINUS its obstacles.
 * Points on a walkable polygon's outline count as walkable; points on an obstacle's outline also
 * count as walkable (you can brush past a pillar, not walk through it).
 *
 * This is the map constraint the particle filter (milestone M5) uses: a guess whose step crosses
 * a wall is implausible. It is written for correctness first; a spatial index can be added later
 * if profiling on the phone shows it is needed.
 */
class Walkability(private val map: VenueMap) {

    private val walkableByLevel = map.walkable.groupBy { it.levelId }
    private val obstaclesByLevel = map.obstacles.groupBy { it.levelId }

    /** True if [p] is inside walkable space on level [levelId]. Unknown levels are never walkable. */
    fun isWalkable(levelId: String, p: Vec2): Boolean {
        val walkable = walkableByLevel[levelId] ?: return false
        if (walkable.none { it.polygon.containsInclusive(p) }) return false
        val obstacles = obstaclesByLevel[levelId] ?: return true
        return obstacles.none { it.polygon.containsStrict(p) }
    }

    /**
     * True if walking in a straight line from [a] to [b] on [levelId] leaves walkable space at any
     * point (crosses a wall, enters an obstacle, or starts/ends outside).
     *
     * Concept: walkability can only change where the path crosses some polygon outline. So we
     * collect every crossing point along the path (as t in [0, 1]), sort them, and test one point
     * in the middle of each stretch between neighbouring crossings, plus the two ends. If all of
     * those are walkable, the whole path is. This is exact, and it handles overlapping walkable
     * polygons correctly: crossing the shared inside edge of two overlapping corridors is fine,
     * because the midpoint on the far side is still inside the union.
     */
    fun pathCrossesWall(levelId: String, a: Vec2, b: Vec2): Boolean {
        if (!isWalkable(levelId, a) || !isWalkable(levelId, b)) return true
        if (a.distanceTo(b) <= Segments.EPS) return false

        val ts = mutableListOf(0.0, 1.0)
        val outlines = (walkableByLevel[levelId].orEmpty() + obstaclesByLevel[levelId].orEmpty())
        for (area in outlines) {
            for ((c, d) in area.polygon.edges) {
                ts += Segments.crossingParams(a, b, c, d)
            }
        }
        ts.sort()
        val r = b - a
        for (i in 0 until ts.size - 1) {
            val t0 = ts[i]
            val t1 = ts[i + 1]
            if (t1 - t0 <= Segments.EPS) continue
            val mid = a + r * ((t0 + t1) / 2.0)
            if (!isWalkable(levelId, mid)) return true
        }
        return false
    }
}
