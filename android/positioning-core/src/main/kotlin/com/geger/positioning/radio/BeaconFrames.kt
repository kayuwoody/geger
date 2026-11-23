package com.geger.positioning.radio

import java.security.MessageDigest

/*
 * Decoding Bluetooth beacon broadcasts, and privacy-safe Wi-Fi identifiers (milestone M4b).
 *
 * Concept: a beacon repeats a tiny advertisement several times a second. Two formats matter:
 *  - iBeacon: Apple's format, inside "manufacturer data" for company 0x004C:
 *        [0x02, 0x15, UUID (16 bytes), major (2), minor (2), measured power at 1 m (1, signed)]
 *  - Eddystone-UID: Google's format, inside "service data" for service 0xFEAA:
 *        [0x00, power at 0 m (1, signed), namespace (10 bytes), instance (6 bytes), reserved (2)]
 * Anything else (phones, earbuds, watches) doesn't match these layouts and is never logged.
 * Parsing is plain Kotlin on byte arrays, so it is unit-tested without Android.
 */

/** A decoded beacon broadcast: its identity key, format, and the transmit power it advertises. */
data class BeaconFrame(val key: String, val format: String, val txPowerDbm: Int)

object BeaconFrames {

    /** Apple's Bluetooth company id, under which iBeacon data is broadcast. */
    const val APPLE_COMPANY_ID = 0x004C

    /** The 16-bit service id Eddystone broadcasts its data under. */
    const val EDDYSTONE_SERVICE = 0xFEAA

    /**
     * Decodes iBeacon manufacturer data (the bytes after Apple's company id), or null if it isn't
     * an iBeacon. The key is "UUID:major:minor" with the UUID upper-case, as in VenueMap.beaconKey.
     */
    fun parseIBeacon(data: ByteArray?): BeaconFrame? {
        if (data == null || data.size < 23) return null
        if (data[0] != 0x02.toByte() || data[1] != 0x15.toByte()) return null
        val hex = data.copyOfRange(2, 18).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        return BeaconFrame("$uuid:$major:$minor", "ibeacon", data[22].toInt())
    }

    /**
     * Decodes Eddystone service data, or null unless it is a UID frame (type 0x00).
     * The key is "eddystone:<namespace hex>:<instance hex>".
     */
    fun parseEddystoneUid(data: ByteArray?): BeaconFrame? {
        if (data == null || data.size < 18 || data[0] != 0x00.toByte()) return null
        val ns = data.copyOfRange(2, 12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val inst = data.copyOfRange(12, 18).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return BeaconFrame("eddystone:$ns:$inst", "eddystone", data[1].toInt())
    }

    /**
     * The identifier to log for a Wi-Fi access point.
     * Concept: an allow-listed network (the mall's own Wi-Fi) is logged by its real BSSID;
     * anything else gets "h:" + the first 12 hex digits of the SHA-256 of its BSSID — the same code
     * every session (so positions can still be learned from it) without revealing whose hotspot
     * it was.
     */
    fun wifiId(bssid: String, ssid: String?, allowList: Collection<String>): String {
        if (ssid != null && ssid in allowList) return bssid.lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(bssid.lowercase().toByteArray())
        return "h:" + digest.take(6).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
