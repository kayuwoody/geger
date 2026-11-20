package com.geger.positioning.routing

import com.geger.positioning.geometry.Vec2
import com.geger.positioning.map.Edge
import com.geger.positioning.map.EdgeKind
import com.geger.positioning.map.Node
import com.geger.positioning.map.VenueMap
import java.util.PriorityQueue
import kotlin.math.sqrt

/** Route-search options. [stepFreeOnly] avoids stairs and escalators (lifts and walking only). */
data class RouteOptions(val stepFreeOnly: Boolean = false)

/**
 * A found route.
 * [nodeIds] = nodes in travel order (start first); [legs] = the edge used for each step, already
 * oriented in travel direction; [lengthM] = physical 3D length; [costM] = what the search minimised
 * (length plus lift penalties).
 */
data class Route(val nodeIds: List<String>, val legs: List<Edge>, val lengthM: Double, val costM: Double)

/**
 * Finds the cheapest route between two nodes of a [VenueMap] with A* search.
 *
 * Concept — A* search: explore the graph outward from the start, always expanding the node whose
 * "cost so far + estimated cost to the goal" is lowest. The estimate here is the straight-line 3D
 * distance to the goal, which never overestimates (no route is shorter than a straight line), so
 * A* is guaranteed to find the cheapest route while exploring far fewer nodes than a blind search.
 *
 * Costs are in metres: an edge costs its 3D length (horizontal distance plus the height change
 * between levels). A lift edge also costs [liftPenaltyM] to account for waiting, so a short
 * escalator is preferred over a lift unless the route must be step-free.
 *
 * Direction: walk, stairs and lift edges work both ways; escalators only from → to.
 */
class Router(private val map: VenueMap, private val liftPenaltyM: Double = 20.0) {

    /** One directed way out of a node: the edge oriented in travel direction. */
    private data class Arc(val to: String, val edge: Edge)

    /** Outgoing arcs per node. Two-way edges appear in both directions. */
    private val arcs: Map<String, List<Arc>> = buildMap<String, MutableList<Arc>> {
        for (e in map.edges) {
            getOrPut(e.fromId) { mutableListOf() } += Arc(e.toId, e)
            if (e.kind.twoWay) getOrPut(e.toId) { mutableListOf() } += Arc(e.fromId, Edge(e.toId, e.fromId, e.kind))
        }
    }

    /**
     * Straight-line distance in metres between two nodes, including the height difference of
     * their levels. Used both as an edge's length and as A*'s goal estimate.
     */
    fun distance3d(a: Node, b: Node): Double {
        val horizontal = a.position.distanceTo(b.position)
        val vertical = map.elevationChange(a.levelId, b.levelId)
        return sqrt(horizontal * horizontal + vertical * vertical)
    }

    /** Search cost of travelling [edge]: its 3D length, plus the lift penalty for lifts. */
    private fun cost(edge: Edge): Double {
        val len = distance3d(map.nodes.getValue(edge.fromId), map.nodes.getValue(edge.toId))
        return if (edge.kind == EdgeKind.LIFT) len + liftPenaltyM else len
    }

    /**
     * Returns the cheapest route from [fromId] to [toId], or null if none exists under [options].
     * A route from a node to itself is that single node with zero length.
     * Throws IllegalArgumentException for unknown node ids (a programming error, not a map error).
     */
    fun route(fromId: String, toId: String, options: RouteOptions = RouteOptions()): Route? {
        require(fromId in map.nodes) { "unknown start node '$fromId'" }
        require(toId in map.nodes) { "unknown goal node '$toId'" }
        val goal = map.nodes.getValue(toId)

        val bestCost = HashMap<String, Double>().apply { put(fromId, 0.0) }
        val cameBy = HashMap<String, Arc>() // node → the arc used to reach it on the best route
        // Queue entries: (estimated total cost, node id). Stale entries are skipped when popped.
        val open = PriorityQueue<Pair<Double, String>>(compareBy { it.first })
        open += distance3d(map.nodes.getValue(fromId), goal) to fromId
        val done = HashSet<String>()

        while (open.isNotEmpty()) {
            val (_, current) = open.poll()
            if (!done.add(current)) continue
            if (current == toId) return buildRoute(fromId, toId, cameBy, bestCost.getValue(toId))
            val costHere = bestCost.getValue(current)
            for (arc in arcs[current].orEmpty()) {
                if (options.stepFreeOnly && !arc.edge.kind.stepFree) continue
                if (arc.to in done) continue
                val newCost = costHere + cost(arc.edge)
                if (newCost < (bestCost[arc.to] ?: Double.POSITIVE_INFINITY)) {
                    bestCost[arc.to] = newCost
                    cameBy[arc.to] = arc
                    open += (newCost + distance3d(map.nodes.getValue(arc.to), goal)) to arc.to
                }
            }
        }
        return null
    }

    /** Walks the "came by" links back from the goal to rebuild the route in travel order. */
    private fun buildRoute(fromId: String, toId: String, cameBy: Map<String, Arc>, costM: Double): Route {
        val legs = ArrayDeque<Edge>()
        var node = toId
        while (node != fromId) {
            val arc = cameBy.getValue(node)
            legs.addFirst(arc.edge)
            node = arc.edge.fromId
        }
        val nodeIds = listOf(fromId) + legs.map { it.toId }
        val length = legs.sumOf { distance3d(map.nodes.getValue(it.fromId), map.nodes.getValue(it.toId)) }
        return Route(nodeIds, legs.toList(), length, costM)
    }

    /**
     * The route node closest (straight line) to [p] on level [levelId], or null if that level has
     * no nodes. Used to start a route from wherever the user currently is.
     */
    fun nearestNode(levelId: String, p: Vec2): Node? =
        map.nodes.values.filter { it.levelId == levelId }.minByOrNull { it.position.distanceTo(p) }
}
