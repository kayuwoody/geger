package com.geger.positioning.map

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Raw shapes of map.json (schema version 1), exactly as stored on disk.
 *
 * Concept: these "file" classes mirror the JSON one-to-one and do no checking. The loader
 * (MapLoader) turns them into the validated VenueMap model, so the rest of the code never sees a
 * half-valid map. Field names use the JSON's snake_case via @SerialName.
 */

/** The whole map.json document. Optional sections default to empty lists. */
@Serializable
data class MapFile(
    val schema: Int,
    val venue: String,
    val version: Int,
    val units: String,
    val levels: List<LevelFile>,
    val walkable: List<AreaFile> = emptyList(),
    val obstacles: List<AreaFile> = emptyList(),
    val nodes: List<NodeFile> = emptyList(),
    val edges: List<EdgeFile> = emptyList(),
    val anchors: List<AnchorFile> = emptyList(),
    val checkpoints: List<CheckpointFile> = emptyList(),
    val destinations: List<DestinationFile> = emptyList(),
    val backgrounds: List<BackgroundFile> = emptyList(),
)

/** A floor. `elevation_m` is its floor height above the lowest mapped level. */
@Serializable
data class LevelFile(
    val id: String,
    val name: String,
    @SerialName("elevation_m") val elevationM: Double,
)

/** A walkable area or an obstacle: a polygon as a list of [x, y] pairs on one level. */
@Serializable
data class AreaFile(
    val level: String,
    val polygon: List<List<Double>>,
)

/** A route-network point (junction, door, escalator end, lift door). */
@Serializable
data class NodeFile(
    val id: String,
    val level: String,
    val x: Double,
    val y: Double,
)

/** A route-network link. `kind` is checked by the loader so typos give a clear message. */
@Serializable
data class EdgeFile(
    val from: String,
    val to: String,
    val kind: String,
)

/** A QR plate: where it hangs, which way it faces, how big its black frame is. */
@Serializable
data class AnchorFile(
    val id: String,
    val level: String,
    val x: Double,
    val y: Double,
    @SerialName("mount_height_m") val mountHeightM: Double,
    @SerialName("facing_deg") val facingDeg: Double,
    @SerialName("size_m") val sizeM: Double,
)

/** A surveyed floor mark where the tester captures ground truth. */
@Serializable
data class CheckpointFile(
    val id: String,
    val level: String,
    val x: Double,
    val y: Double,
    @SerialName("heading_deg") val headingDeg: Double,
)

/** A named place the app can route to, attached to a route node. */
@Serializable
data class DestinationFile(
    val id: String,
    val name: String,
    val node: String,
)

/** A floor image shown behind the map, with its scale and where the map origin sits on it. */
@Serializable
data class BackgroundFile(
    val level: String,
    val image: String,
    @SerialName("px_per_m") val pxPerM: Double,
    @SerialName("origin_px") val originPx: List<Double>,
)
