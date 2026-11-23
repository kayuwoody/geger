package com.geger.positioning.map

import com.geger.positioning.geometry.Polygon
import com.geger.positioning.geometry.Vec2

/*
 * The validated map model used by the rest of the code.
 *
 * Concept: a VenueMap is only ever created by MapLoader after every check has passed, so code
 * that receives one can trust that ids resolve, polygons are valid and angles are in range.
 */

/** A floor of the venue. [elevationM] = floor height above the lowest mapped level. */
data class Level(val id: String, val name: String, val elevationM: Double)

/** A polygon on one level: either walkable space or an obstacle inside it. */
data class Area(val levelId: String, val polygon: Polygon)

/** A route-network point on a level, in map-frame metres. */
data class Node(val id: String, val levelId: String, val position: Vec2)

/**
 * How a route edge is travelled.
 * Concept: the kind decides direction (escalators are one-way) and whether a step-free route
 * may use it (stairs and escalators are not step-free; lifts are).
 */
enum class EdgeKind(val jsonName: String, val twoWay: Boolean, val stepFree: Boolean, val changesLevel: Boolean?) {
    WALK("walk", twoWay = true, stepFree = true, changesLevel = false),
    STAIRS("stairs", twoWay = true, stepFree = false, changesLevel = null),
    LIFT("lift", twoWay = true, stepFree = true, changesLevel = true),
    ESCALATOR_UP("escalator_up", twoWay = false, stepFree = false, changesLevel = true),
    ESCALATOR_DOWN("escalator_down", twoWay = false, stepFree = false, changesLevel = true);

    companion object {
        /** Looks up a kind by its JSON spelling (e.g. "escalator_up"); null if unknown. */
        fun fromJson(name: String): EdgeKind? = entries.firstOrNull { it.jsonName == name }

        /** All JSON spellings, for error messages. */
        val jsonNames: List<String> get() = entries.map { it.jsonName }
    }
}

/** A route-network link from [fromId] to [toId]. One-way kinds may only be travelled from → to. */
data class Edge(val fromId: String, val toId: String, val kind: EdgeKind)

/**
 * A QR plate.
 * [position] = point on the floor plan under the plate centre; [mountHeightM] = height of the
 * frame centre above the floor; [facingDeg] = map heading of the plate's outward normal;
 * [sizeM] = outer side length of the black frame.
 */
data class Anchor(
    val id: String,
    val levelId: String,
    val position: Vec2,
    val mountHeightM: Double,
    val facingDeg: Double,
    val sizeM: Double,
)

/** A surveyed floor mark; [headingDeg] = direction of the tape arrow (the way to aim the phone). */
data class Checkpoint(val id: String, val levelId: String, val position: Vec2, val headingDeg: Double)

/** A named routing target attached to a route node. */
data class Destination(val id: String, val name: String, val nodeId: String)

/** A floor image: [pxPerM] pixels per metre, [originPx] = pixel position of the map origin. */
data class Background(val levelId: String, val image: String, val pxPerM: Double, val originPx: Vec2)

/**
 * A Bluetooth beacon: [position] on the plan, [heightM] above its level's floor, and its iBeacon
 * identity. [key] ("UUID:major:minor", UUID upper-case) is how a heard broadcast is matched to it.
 */
data class Beacon(
    val id: String,
    val levelId: String,
    val position: Vec2,
    val heightM: Double,
    val uuid: String,
    val major: Int,
    val minor: Int,
) {
    val key: String get() = beaconKey(uuid, major, minor)
}

/** The lookup key for an iBeacon identity: upper-case UUID, major and minor joined by ':'. */
fun beaconKey(uuid: String, major: Int, minor: Int) = "${uuid.uppercase()}:$major:$minor"

/** A fully validated venue map. Build it with [MapLoader]; never construct one by hand in app code. */
data class VenueMap(
    val schema: Int,
    val venue: String,
    val version: Int,
    val levels: Map<String, Level>,
    val walkable: List<Area>,
    val obstacles: List<Area>,
    val nodes: Map<String, Node>,
    val edges: List<Edge>,
    val anchors: Map<String, Anchor>,
    val checkpoints: Map<String, Checkpoint>,
    val destinations: Map<String, Destination>,
    val backgrounds: List<Background>,
    val beacons: Map<String, Beacon> = emptyMap(),
) {
    /** Beacons by their identity key (see [beaconKey]), for matching what the phone hears. */
    val beaconsByKey: Map<String, Beacon> by lazy { beacons.values.associateBy { it.key } }

    /** The level with [id]. Throws if missing, which cannot happen for a validated map's own ids. */
    fun level(id: String): Level = levels.getValue(id)

    /** Height difference in metres between two levels (positive when [toLevelId] is higher). */
    fun elevationChange(fromLevelId: String, toLevelId: String): Double =
        level(toLevelId).elevationM - level(fromLevelId).elevationM
}
