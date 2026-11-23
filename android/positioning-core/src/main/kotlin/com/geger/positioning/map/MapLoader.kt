package com.geger.positioning.map

import com.geger.positioning.geometry.Polygon
import com.geger.positioning.geometry.Vec2
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Outcome of loading a map.
 *
 * Concept: errors make a map unusable (the app refuses it); warnings are suspicious but allowed
 * (e.g. a plate position just outside the corridor because plates hang on walls). Both carry
 * human-readable messages that name the offending item, so the tester can fix the map quickly.
 */
sealed class MapLoadResult {
    abstract val warnings: List<String>

    /** The map passed every check. */
    data class Ok(val map: VenueMap, override val warnings: List<String>) : MapLoadResult()

    /** The map has at least one error and must not be used. */
    data class Failed(val errors: List<String>, override val warnings: List<String>) : MapLoadResult()
}

/**
 * Parses and validates map.json (schema version 1).
 *
 * Concept: validation happens in two passes.
 *  1. Structure: JSON shape, unique ids, references that resolve, numbers in range, valid polygons,
 *     edge kinds that match the level change (an "escalator_up" must actually go up).
 *  2. Geometry (only if pass 1 found no errors, because it needs a consistent map): nodes,
 *     plates and checkpoints inside walkable space, and route edges that don't cut through walls.
 *     These are warnings, because a slightly-off survey shouldn't block a test day.
 */
object MapLoader {

    /** The only map schema version this code understands. */
    const val SUPPORTED_SCHEMA = 1

    private val json = Json { ignoreUnknownKeys = true }

    /** Reads and validates the map file at [file]. */
    fun loadFile(file: File): MapLoadResult =
        if (!file.isFile) MapLoadResult.Failed(listOf("Map file not found: ${file.path}"), emptyList())
        else parse(file.readText())

    /** Parses and validates map JSON [text]. Never throws for bad input; returns [MapLoadResult.Failed]. */
    fun parse(text: String): MapLoadResult {
        val file = try {
            json.decodeFromString(MapFile.serializer(), text)
        } catch (e: SerializationException) {
            return MapLoadResult.Failed(listOf("Invalid map JSON: ${e.message}"), emptyList())
        } catch (e: IllegalArgumentException) {
            return MapLoadResult.Failed(listOf("Invalid map JSON: ${e.message}"), emptyList())
        }
        return validate(file)
    }

    /** Runs both validation passes over an already-parsed [file]. */
    fun validate(file: MapFile): MapLoadResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ---- Top level -------------------------------------------------------------------
        if (file.schema != SUPPORTED_SCHEMA) errors += "schema: expected $SUPPORTED_SCHEMA, got ${file.schema}"
        if (file.units != "m") errors += "units: expected \"m\", got \"${file.units}\""
        if (file.venue.isBlank()) errors += "venue: must not be blank"
        if (file.levels.isEmpty()) errors += "levels: at least one level is required"

        // ---- Levels ----------------------------------------------------------------------
        checkIds("levels", file.levels.map { it.id }, errors)
        file.levels.forEachIndexed { i, l ->
            if (!l.elevationM.isFinite()) errors += "levels[$i] '${l.id}': elevation_m must be a finite number"
        }
        val levelIds = file.levels.map { it.id }.toSet()
        fun checkLevel(where: String, levelId: String) {
            if (levelId !in levelIds) errors += "$where: unknown level '$levelId'"
        }

        // ---- Areas -----------------------------------------------------------------------
        fun buildAreas(section: String, areas: List<AreaFile>): List<Area> = areas.mapIndexedNotNull { i, a ->
            val where = "$section[$i]"
            checkLevel(where, a.level)
            val badPoint = a.polygon.indexOfFirst { it.size != 2 }
            if (badPoint >= 0) {
                errors += "$where: point $badPoint must be [x, y], got ${a.polygon[badPoint]}"
                return@mapIndexedNotNull null
            }
            val polygon = Polygon(a.polygon.map { Vec2(it[0], it[1]) })
            val problem = polygon.problem()
            if (problem != null) {
                errors += "$where: polygon $problem"
                null
            } else {
                Area(a.level, polygon)
            }
        }
        val walkable = buildAreas("walkable", file.walkable)
        val obstacles = buildAreas("obstacles", file.obstacles)

        // ---- Nodes -----------------------------------------------------------------------
        checkIds("nodes", file.nodes.map { it.id }, errors)
        file.nodes.forEachIndexed { i, n ->
            checkLevel("nodes[$i] '${n.id}'", n.level)
            if (!n.x.isFinite() || !n.y.isFinite()) errors += "nodes[$i] '${n.id}': x and y must be finite numbers"
        }
        val nodeById = file.nodes.associateBy { it.id }
        val levelElevation = file.levels.associate { it.id to it.elevationM }

        // ---- Edges -----------------------------------------------------------------------
        val edges = mutableListOf<Edge>()
        val seenEdges = mutableSetOf<Triple<String, String, String>>()
        file.edges.forEachIndexed { i, e ->
            val where = "edges[$i] ${e.from}→${e.to}"
            val kind = EdgeKind.fromJson(e.kind)
            if (kind == null) {
                errors += "$where: unknown kind '${e.kind}' (expected one of ${EdgeKind.jsonNames.joinToString()})"
            }
            val from = nodeById[e.from]
            val to = nodeById[e.to]
            if (from == null) errors += "$where: unknown node '${e.from}'"
            if (to == null) errors += "$where: unknown node '${e.to}'"
            if (e.from == e.to) errors += "$where: an edge must join two different nodes"
            if (kind == null || from == null || to == null || e.from == e.to) return@forEachIndexed

            if (!seenEdges.add(Triple(e.from, e.to, e.kind))) warnings += "$where: duplicate edge"

            val fromElev = levelElevation[from.level]
            val toElev = levelElevation[to.level]
            if (fromElev != null && toElev != null) {
                val sameLevel = from.level == to.level
                when (kind) {
                    EdgeKind.WALK -> if (!sameLevel) errors += "$where: a walk edge must stay on one level (${from.level} → ${to.level})"
                    EdgeKind.LIFT -> if (sameLevel) errors += "$where: a lift edge must join two different levels"
                    EdgeKind.ESCALATOR_UP -> if (toElev <= fromElev) {
                        errors += "$where: escalator_up must go up, but ${from.level} (${fromElev} m) → ${to.level} (${toElev} m)"
                    }
                    EdgeKind.ESCALATOR_DOWN -> if (toElev >= fromElev) {
                        errors += "$where: escalator_down must go down, but ${from.level} (${fromElev} m) → ${to.level} (${toElev} m)"
                    }
                    EdgeKind.STAIRS -> Unit // stairs may join levels or half-levels on the same floor
                }
            }
            edges += Edge(e.from, e.to, kind)
        }

        // ---- Anchors (QR plates) ---------------------------------------------------------
        checkIds("anchors", file.anchors.map { it.id }, errors)
        file.anchors.forEachIndexed { i, a ->
            val where = "anchors[$i] '${a.id}'"
            checkLevel(where, a.level)
            if (!a.x.isFinite() || !a.y.isFinite()) errors += "$where: x and y must be finite numbers"
            checkAngle("$where: facing_deg", a.facingDeg, errors)
            if (!(a.sizeM > 0.0) || !a.sizeM.isFinite()) errors += "$where: size_m must be a positive number, got ${a.sizeM}"
            if (!(a.mountHeightM >= 0.0) || !a.mountHeightM.isFinite()) {
                errors += "$where: mount_height_m must be zero or more, got ${a.mountHeightM}"
            }
        }

        // ---- Checkpoints -----------------------------------------------------------------
        checkIds("checkpoints", file.checkpoints.map { it.id }, errors)
        file.checkpoints.forEachIndexed { i, c ->
            val where = "checkpoints[$i] '${c.id}'"
            checkLevel(where, c.level)
            if (!c.x.isFinite() || !c.y.isFinite()) errors += "$where: x and y must be finite numbers"
            checkAngle("$where: heading_deg", c.headingDeg, errors)
        }

        // ---- Destinations ----------------------------------------------------------------
        checkIds("destinations", file.destinations.map { it.id }, errors)
        file.destinations.forEachIndexed { i, d ->
            val where = "destinations[$i] '${d.id}'"
            if (d.name.isBlank()) errors += "$where: name must not be blank"
            if (d.node !in nodeById) errors += "$where: unknown node '${d.node}'"
        }

        // ---- Backgrounds -----------------------------------------------------------------
        file.backgrounds.forEachIndexed { i, b ->
            val where = "backgrounds[$i]"
            checkLevel(where, b.level)
            if (b.image.isBlank()) errors += "$where: image must not be blank"
            if (!(b.pxPerM > 0.0) || !b.pxPerM.isFinite()) errors += "$where: px_per_m must be a positive number"
            if (b.originPx.size != 2 || b.originPx.any { !it.isFinite() }) errors += "$where: origin_px must be [x, y]"
        }

        // ---- Beacons (Bluetooth) ------------------------------------------------------------
        checkIds("beacons", file.beacons.map { it.id }, errors)
        val uuidPattern = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
        file.beacons.forEachIndexed { i, b ->
            val where = "beacons[$i] '${b.id}'"
            checkLevel(where, b.level)
            if (!b.x.isFinite() || !b.y.isFinite()) errors += "$where: x and y must be finite numbers"
            if (!(b.heightM >= 0.0) || !b.heightM.isFinite()) errors += "$where: height_m must be zero or more, got ${b.heightM}"
            if (!uuidPattern.matches(b.uuid)) errors += "$where: uuid must look like D55248B4-8F80-46C8-88AC-EEC9C72B2B7C, got \"${b.uuid}\""
            if (b.major !in 0..65535) errors += "$where: major must be 0–65535, got ${b.major}"
            if (b.minor !in 0..65535) errors += "$where: minor must be 0–65535, got ${b.minor}"
        }
        file.beacons.groupBy { beaconKey(it.uuid, it.major, it.minor) }.filter { it.value.size > 1 }.forEach { (key, list) ->
            errors += "beacons: ${list.joinToString { "'${it.id}'" }} share the identity $key (uuid + major + minor must be unique)"
        }

        if (errors.isNotEmpty()) return MapLoadResult.Failed(errors, warnings)

        // ---- Build the model ---------------------------------------------------------------
        val map = VenueMap(
            schema = file.schema,
            venue = file.venue,
            version = file.version,
            levels = file.levels.associate { it.id to Level(it.id, it.name, it.elevationM) },
            walkable = walkable,
            obstacles = obstacles,
            nodes = file.nodes.associate { it.id to Node(it.id, it.level, Vec2(it.x, it.y)) },
            edges = edges,
            anchors = file.anchors.associate {
                it.id to Anchor(it.id, it.level, Vec2(it.x, it.y), it.mountHeightM, it.facingDeg, it.sizeM)
            },
            checkpoints = file.checkpoints.associate { it.id to Checkpoint(it.id, it.level, Vec2(it.x, it.y), it.headingDeg) },
            destinations = file.destinations.associate { it.id to Destination(it.id, it.name, it.node) },
            backgrounds = file.backgrounds.map { Background(it.level, it.image, it.pxPerM, Vec2(it.originPx[0], it.originPx[1])) },
            beacons = file.beacons.associate {
                it.id to Beacon(it.id, it.level, Vec2(it.x, it.y), it.heightM, it.uuid.uppercase(), it.major, it.minor)
            },
        )

        // ---- Pass 2: geometry warnings -----------------------------------------------------
        val walk = Walkability(map)
        for (level in map.levels.values) {
            if (map.walkable.none { it.levelId == level.id }) warnings += "level '${level.id}': has no walkable area"
        }
        for (node in map.nodes.values) {
            if (!walk.isWalkable(node.levelId, node.position)) {
                warnings += "node '${node.id}': at ${fmt(node.position)} on ${node.levelId} is outside walkable space"
            }
        }
        val connected = map.edges.flatMap { listOf(it.fromId, it.toId) }.toSet()
        for (node in map.nodes.values) {
            if (node.id !in connected) warnings += "node '${node.id}': has no edges, so routes can't use it"
        }
        for (edge in map.edges) {
            val from = map.nodes.getValue(edge.fromId)
            val to = map.nodes.getValue(edge.toId)
            if (from.levelId == to.levelId && walk.pathCrossesWall(from.levelId, from.position, to.position)) {
                warnings += "edge ${edge.fromId}→${edge.toId}: the straight line between the nodes leaves walkable space"
            }
        }
        for (a in map.anchors.values) {
            if (!walk.isWalkable(a.levelId, a.position)) {
                warnings += "anchor '${a.id}': at ${fmt(a.position)} on ${a.levelId} is outside walkable space " +
                    "(fine if it marks a wall, but check the survey)"
            }
        }
        for (c in map.checkpoints.values) {
            if (!walk.isWalkable(c.levelId, c.position)) {
                warnings += "checkpoint '${c.id}': at ${fmt(c.position)} on ${c.levelId} is outside walkable space"
            }
        }
        return MapLoadResult.Ok(map, warnings)
    }

    /** Adds an error for each blank or repeated id in a section (ids must be unique per section). */
    private fun checkIds(section: String, ids: List<String>, errors: MutableList<String>) {
        ids.forEachIndexed { i, id -> if (id.isBlank()) errors += "$section[$i]: id must not be blank" }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 && it.key.isNotBlank() }.forEach { (id, n) ->
            errors += "$section: id '$id' is used $n times"
        }
    }

    /** Adds an error unless [deg] is a finite angle in [0, 360). */
    private fun checkAngle(where: String, deg: Double, errors: MutableList<String>) {
        if (!deg.isFinite() || deg < 0.0 || deg >= 360.0) errors += "$where must be in [0, 360), got $deg"
    }

    /** Formats a point as "(x, y)" with 2 decimals for messages (locale-independent decimal point). */
    private fun fmt(p: Vec2) = String.format(java.util.Locale.ROOT, "(%.2f, %.2f)", p.x, p.y)
}
