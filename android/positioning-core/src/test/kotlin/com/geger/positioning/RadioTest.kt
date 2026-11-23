package com.geger.positioning

import com.geger.positioning.map.MapLoadResult
import com.geger.positioning.map.MapLoader
import com.geger.positioning.map.beaconKey
import com.geger.positioning.radio.BeaconFrames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for the radio side of M4b: decoding beacon broadcasts, privacy-safe Wi-Fi ids, and beacons
 * in the map.
 */
class RadioTest {

    /** Builds iBeacon manufacturer data as a beacon broadcasts it (after Apple's company id). */
    private fun ibeacon(uuidHex: String, major: Int, minor: Int, tx: Int): ByteArray {
        val uuid = uuidHex.replace("-", "").chunked(2).map { it.toInt(16).toByte() }
        return (listOf(0x02.toByte(), 0x15.toByte()) + uuid +
            listOf((major shr 8).toByte(), major.toByte(), (minor shr 8).toByte(), minor.toByte(), tx.toByte())).toByteArray()
    }

    @Test
    fun `iBeacon frames decode to the same key the map uses`() {
        val f = BeaconFrames.parseIBeacon(ibeacon("D55248B4-8F80-46C8-88AC-EEC9C72B2B7C", 2, 513, -59))!!
        assertEquals("D55248B4-8F80-46C8-88AC-EEC9C72B2B7C:2:513", f.key)
        assertEquals(beaconKey("d55248b4-8f80-46c8-88ac-eec9c72b2b7c", 2, 513), f.key)
        assertEquals(-59, f.txPowerDbm)
        assertEquals("ibeacon", f.format)
    }

    @Test
    fun `other Apple broadcasts are not mistaken for beacons`() {
        // AirPods / Find My use Apple's company id with other type bytes; they must never be logged.
        assertNull(BeaconFrames.parseIBeacon(byteArrayOf(0x07, 0x19, 0x01, 0x02, 0x20, 0x55)))
        assertNull(BeaconFrames.parseIBeacon(ByteArray(25) { 0x12 }))
        assertNull(BeaconFrames.parseIBeacon(null))
    }

    @Test
    fun `Eddystone UID frames decode, other Eddystone frames don't`() {
        val uid = byteArrayOf(0x00, (-20).toByte()) + ByteArray(10) { (it + 1).toByte() } + ByteArray(6) { (0xA0 + it).toByte() } + ByteArray(2)
        val f = BeaconFrames.parseEddystoneUid(uid)!!
        assertEquals("eddystone:0102030405060708090a:a0a1a2a3a4a5", f.key)
        assertEquals(-20, f.txPowerDbm)
        assertNull(BeaconFrames.parseEddystoneUid(byteArrayOf(0x10, 0x00, 0x03) + "example".toByteArray()), "URL frames are not UID frames")
    }

    @Test
    fun `wifi ids are hashed unless the network is allow-listed`() {
        val hashed = BeaconFrames.wifiId("AA:BB:CC:DD:EE:FF", "Someone's iPhone", listOf("EkoCheras_Free_WiFi"))
        assertTrue(hashed.startsWith("h:") && hashed.length == 14, hashed)
        assertEquals(hashed, BeaconFrames.wifiId("aa:bb:cc:dd:ee:ff", null, emptyList()), "same code every time, case-insensitive")
        assertNotEquals(hashed, BeaconFrames.wifiId("aa:bb:cc:dd:ee:fe", null, emptyList()))
        assertEquals("aa:bb:cc:dd:ee:ff", BeaconFrames.wifiId("AA:BB:CC:DD:EE:FF", "EkoCheras_Free_WiFi", listOf("EkoCheras_Free_WiFi")))
    }

    @Test
    fun `beacons load from the map with normalised identities`() {
        val dir = File(System.getProperty("geger.fixturesDir"))
        val r = MapLoader.loadFile(File(dir, "ok-full.json"))
        assertTrue(r is MapLoadResult.Ok, "$r")
        val map = (r as MapLoadResult.Ok).map
        assertEquals(2, map.beacons.size)
        assertEquals("B02", map.beaconsByKey.getValue("D55248B4-8F80-46C8-88AC-EEC9C72B2B7C:2:2").id, "lower-case uuid in the file still matches")
        assertTrue(r.warnings.none { it.contains("beacon") }, "beacons on walls give no walkability warning")
    }

    @Test
    fun `beacon errors are explained`() {
        val dir = File(System.getProperty("geger.fixturesDir"))
        fun errors(name: String) = (MapLoader.loadFile(File(dir, name)) as MapLoadResult.Failed).errors
        assertTrue(errors("bad-beacon-uuid.json").any { it.contains("uuid must look like") })
        assertTrue(errors("bad-beacon-duplicate-identity.json").any { it.contains("'B01', 'B02' share the identity") })
        assertTrue(errors("bad-beacon-major-range.json").any { it.contains("major must be 0–65535") })
    }
}
