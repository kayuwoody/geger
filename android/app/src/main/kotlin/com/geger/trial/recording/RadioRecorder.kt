package com.geger.trial.recording

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.geger.positioning.config.RadioConfig
import com.geger.positioning.log.RecordType
import com.geger.positioning.log.record
import com.geger.positioning.radio.BeaconFrames
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/** What the radios are doing, for the live display. */
data class RadioLive(
    val beaconsHeard: Int,
    val strongestBeacon: String?,
    val strongestRssi: Int?,
    val wifiScans: Long,
    val wifiAps: Int,
    val rttCapable: Int,
    val rttRounds: Long,
    val gnssUsed: Int,
    val gnssVisible: Int,
    val gnssFixes: Long,
    val gnssAccuracyM: Double,
)

/**
 * Records Bluetooth beacons, Wi-Fi and GPS during a session (milestone M4b), for offline evaluation
 * of which positioning layers a mall needs.
 *
 * Concept: each radio delivers its own kind of event at its own pace — beacon broadcasts many times
 * a second, Wi-Fi scans every few tens of seconds (Android rations them), Wi-Fi ranging about once
 * a second when supported, GPS once a second when it can see satellites. All callbacks run on one
 * background thread and become log lines, stamped on the same elapsedRealtime clock as everything
 * else (BLE results, Wi-Fi scan entries, ranging results and GPS fixes all carry that clock).
 *
 * Privacy (PDPA): Bluetooth scanning is filtered in hardware to iBeacon and Eddystone-UID formats,
 * so shoppers' phones, earbuds and watches are never seen, and beacon MAC addresses aren't logged.
 * Wi-Fi access points are logged by hashed BSSID unless their network is on the allow list.
 *
 * Every part is optional: a radio that is switched off, missing, or not permitted is skipped and
 * reported in the radio_status record at the start of the session.
 */
@SuppressLint("MissingPermission") // each use is guarded by granted(); SecurityException is caught too
class RadioRecorder(
    private val context: Context,
    private val config: RadioConfig,
    private val beaconNames: Map<String, String>,
    private val sink: (type: String, line: String) -> Unit,
) {
    /** Records written per stream (ble, wifi_scan, wifi_rtt, gnss_fix, gnss_status). */
    val counts = ConcurrentHashMap<String, AtomicLong>()

    private val beaconRssi = ConcurrentHashMap<String, Int>()
    @Volatile private var wifiAps = 0
    @Volatile private var rttCapable = 0
    @Volatile private var gnssUsed = 0
    @Volatile private var gnssVisible = 0
    @Volatile private var gnssAccuracy = Double.NaN
    @Volatile private var lastGnssStatusNs = 0L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var bleCallback: ScanCallback? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var locationListener: LocationListener? = null
    private var gnssCallback: GnssStatus.Callback? = null
    private var lastScanResults: List<android.net.wifi.ScanResult> = emptyList()
    private var ssidByBssid: Map<String, String?> = emptyMap()
    @Volatile private var running = false

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val location = context.getSystemService(LocationManager::class.java)
    private val rtt: WifiRttManager? =
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) context.getSystemService(WifiRttManager::class.java) else null

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    private fun count(stream: String) = counts.computeIfAbsent(stream) { AtomicLong() }.incrementAndGet()
    private fun emit(stream: String, line: String) { count(stream); sink(stream, line) }

    /** Can this app scan for Bluetooth beacons right now? */
    private fun bleAllowed() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_SCAN) else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Starts every enabled radio and logs a radio_status record saying what is on, permitted and supported. */
    fun start() {
        val t = HandlerThread("geger-radios").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        running = true
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val status = record(SystemClock.elapsedRealtimeNanos(), RecordType.RADIO_STATUS)
            .bool("bluetooth_on", bt?.isEnabled == true).bool("bluetooth_permitted", bleAllowed())
            .bool("location_on", location?.isLocationEnabled == true).bool("location_permitted", fine)
            .bool("wifi_on", wifi?.isWifiEnabled == true)
            .bool("wifi_rtt_supported", rtt != null).bool("wifi_rtt_available", rtt?.isAvailable == true)
            .bool("nearby_wifi_permitted", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.NEARBY_WIFI_DEVICES))
        val started = mutableListOf<String>()
        if (config.bleEnabled && startBle(h)) started += "ble"
        if (config.wifiEnabled && fine && startWifi(h)) started += "wifi"
        if (config.gnssEnabled && fine && startGnss(h)) started += "gnss"
        status.rawJson("started", started.joinToString(",", "[", "]") { "\"$it\"" })
        sink(RecordType.RADIO_STATUS, status.build())
    }

    // ---- Bluetooth beacons ---------------------------------------------------------------------

    /**
     * Scans continuously for beacon broadcasts. The filters run in the Bluetooth chip, so other
     * devices are discarded before the app sees them: iBeacon (Apple manufacturer data starting
     * 0x02 0x15, optionally limited to configured UUIDs) and Eddystone-UID (service 0xFEAA, frame 0x00).
     */
    private fun startBle(h: Handler): Boolean {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        if (!bleAllowed()) return false
        val filters = mutableListOf<ScanFilter>()
        if (config.bleUuids.isEmpty()) {
            filters += ScanFilter.Builder().setManufacturerData(BeaconFrames.APPLE_COMPANY_ID, byteArrayOf(0x02, 0x15), byteArrayOf(-1, -1)).build()
        } else {
            for (u in config.bleUuids) {
                val bytes = u.replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (bytes.size != 16) continue
                filters += ScanFilter.Builder()
                    .setManufacturerData(BeaconFrames.APPLE_COMPANY_ID, byteArrayOf(0x02, 0x15) + bytes, ByteArray(18) { -1 }).build()
            }
        }
        filters += ScanFilter.Builder().setServiceData(EDDYSTONE, byteArrayOf(0x00), byteArrayOf(-1)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                h.post { onBeacon(result) }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                h.post { results.forEach(::onBeacon) }
            }
            override fun onScanFailed(errorCode: Int) {
                sink(RecordType.NOTE, record(SystemClock.elapsedRealtimeNanos(), RecordType.NOTE).str("text", "[bluetooth scan failed: code $errorCode]").build())
            }
        }
        return try {
            scanner.startScan(filters, settings, cb)
            bleCallback = cb
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** One beacon broadcast → a ble record (identity, signal strength, advertised power). */
    private fun onBeacon(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val frame = BeaconFrames.parseIBeacon(rec.getManufacturerSpecificData(BeaconFrames.APPLE_COMPANY_ID))
            ?: BeaconFrames.parseEddystoneUid(rec.getServiceData(EDDYSTONE)) ?: return
        beaconRssi[frame.key] = r.rssi
        emit(RecordType.BLE, record(r.timestampNanos, RecordType.BLE)
            .str("key", frame.key).str("beacon", beaconNames[frame.key]).str("format", frame.format)
            .int("rssi", r.rssi).int("tx", frame.txPowerDbm).build())
    }

    // ---- Wi-Fi scans and ranging -------------------------------------------------------------

    /**
     * Requests a Wi-Fi scan every few seconds (Android may refuse — it allows 4 per 2 minutes
     * unless "Wi-Fi scan throttling" is off in developer options) and logs every result set.
     * If the phone supports Wi-Fi round-trip-time ranging, also ranges to the access points that
     * support it (802.11mc), about once a second.
     */
    private fun startWifi(h: Handler): Boolean {
        val w = wifi ?: return false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { h.post { onScanResults() } }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, h, ContextCompat.RECEIVER_NOT_EXPORTED)
        wifiReceiver = receiver
        val scanEvery = (config.wifiScanIntervalS * 1000).toLong().coerceAtLeast(2_000)
        h.post(object : Runnable {
            @Suppress("DEPRECATION")
            override fun run() {
                if (!running) return
                try { w.startScan() } catch (_: SecurityException) { }
                h.postDelayed(this, scanEvery)
            }
        })
        if (config.wifiRttEnabled && rtt != null) {
            val rangeEvery = (config.wifiRttIntervalS * 1000).toLong().coerceAtLeast(200)
            h.postDelayed(object : Runnable {
                override fun run() {
                    if (!running) return
                    rangeOnce(h)
                    h.postDelayed(this, rangeEvery)
                }
            }, rangeEvery)
        }
        return true
    }

    /** Logs the latest scan: per access point its (hashed) id, frequency, strength and ranging support. */
    @Suppress("DEPRECATION")
    private fun onScanResults() {
        val results = try { wifi?.scanResults.orEmpty() } catch (_: SecurityException) { return }
        val now = SystemClock.elapsedRealtimeNanos()
        lastScanResults = results
        ssidByBssid = results.associate { it.BSSID to it.SSID }
        wifiAps = results.size
        val responders = results.filter { it.is80211mcResponder }
        rttCapable = responders.size
        val aps = results.joinToString(",", "[", "]") { r ->
            val ssid = r.SSID?.takeIf { it in config.wifiSsidAllowList }
            val ageMs = (now / 1_000_000) - r.timestamp / 1_000
            "{\"id\":\"${BeaconFrames.wifiId(r.BSSID, r.SSID, config.wifiSsidAllowList)}\"," +
                (if (ssid != null) "\"ssid\":\"${ssid.replace("\"", "\\\"")}\"," else "") +
                "\"freq\":${r.frequency},\"rssi\":${r.level},\"mc\":${r.is80211mcResponder},\"age_ms\":$ageMs}"
        }
        emit(RecordType.WIFI_SCAN, record(now, RecordType.WIFI_SCAN).int("n", results.size).int("mc", responders.size).rawJson("aps", aps).build())
    }

    /** One ranging round to the strongest ranging-capable access points from the last scan. */
    private fun rangeOnce(h: Handler) {
        val manager = rtt ?: return
        if (!manager.isAvailable) return
        val targets = lastScanResults.filter { it.is80211mcResponder }.sortedByDescending { it.level }.take(RangingRequest.getMaxPeers())
        if (targets.isEmpty()) return
        val request = RangingRequest.Builder().addAccessPoints(targets).build()
        val exec = Executor { h.post(it) }
        try {
            manager.startRanging(request, exec, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) {
                    emit(RecordType.WIFI_RTT, record(SystemClock.elapsedRealtimeNanos(), RecordType.WIFI_RTT).int("failure", code).build())
                }
                override fun onRangingResults(results: MutableList<RangingResult>) = logRanging(results)
            })
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Logs one ranging round: per access point its distance and spread (metres) if it succeeded. */
    private fun logRanging(results: List<RangingResult>) {
        val now = SystemClock.elapsedRealtimeNanos()
        val entries = results.joinToString(",", "[", "]") { r ->
            val bssid = r.macAddress?.toString() ?: "?"
            val id = BeaconFrames.wifiId(bssid, ssidByBssid[bssid], config.wifiSsidAllowList)
            if (r.status == RangingResult.STATUS_SUCCESS) {
                String.format(Locale.ROOT, "{\"id\":\"%s\",\"ok\":true,\"dist_m\":%.3f,\"std_m\":%.3f,\"rssi\":%d,\"n\":%d,\"good\":%d,\"t_ms\":%d}",
                    id, r.distanceMm / 1000.0, r.distanceStdDevMm / 1000.0, r.rssi, r.numAttemptedMeasurements, r.numSuccessfulMeasurements, r.rangingTimestampMillis)
            } else {
                "{\"id\":\"$id\",\"ok\":false,\"status\":${r.status}}"
            }
        }
        emit(RecordType.WIFI_RTT, record(now, RecordType.WIFI_RTT).rawJson("results", entries).build())
    }

    // ---- GPS ---------------------------------------------------------------------------------

    /**
     * Asks for raw GPS fixes once a second and listens to satellite status. Indoors there will
     * often be no fix at all; the satellite counts still show how close GPS came.
     */
    private fun startGnss(h: Handler): Boolean {
        val lm = location ?: return false
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
        val listener = LocationListener { loc: Location -> h.post { onGnssFix(loc) } }
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) = onSatellites(s)
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, h.looper)
            registerGnss(lm, status, h)
            locationListener = listener
            gnssCallback = status
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Satellite-status registration: the Executor form exists from Android 11, the Handler form before. */
    private fun registerGnss(lm: LocationManager, status: GnssStatus.Callback, h: Handler) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.registerGnssStatusCallback(Executor { h.post(it) }, status)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(status, h)
        }
    }

    /** One GPS fix → gnss_fix (position, accuracy and motion). */
    private fun onGnssFix(loc: Location) {
        gnssAccuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN
        emit(RecordType.GNSS_FIX, record(loc.elapsedRealtimeNanos, RecordType.GNSS_FIX)
            .num("lat", loc.latitude).num("lon", loc.longitude)
            .num("accuracy_m", if (loc.hasAccuracy()) loc.accuracy.toDouble() else Double.NaN)
            .num("alt_m", if (loc.hasAltitude()) loc.altitude else Double.NaN)
            .num("speed_mps", if (loc.hasSpeed()) loc.speed.toDouble() else Double.NaN)
            .num("bearing_deg", if (loc.hasBearing()) loc.bearing.toDouble() else Double.NaN).build())
    }

    /** Satellite status → gnss_status, at most once a second: visible, used, mean signal of used ones. */
    private fun onSatellites(s: GnssStatus) {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastGnssStatusNs < 900_000_000L) return
        lastGnssStatusNs = now
        var used = 0
        var cn0 = 0.0
        for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) { used++; cn0 += s.getCn0DbHz(i) }
        gnssUsed = used
        gnssVisible = s.satelliteCount
        emit(RecordType.GNSS_STATUS, record(now, RecordType.GNSS_STATUS)
            .int("visible", s.satelliteCount).int("used", used).num("mean_cn0_used", if (used > 0) cn0 / used else Double.NaN).build())
    }

    // ---- Live view and shutdown ----------------------------------------------------------------

    /** Current radio activity for the recording screen. */
    fun live(): RadioLive {
        val strongest = beaconRssi.maxByOrNull { it.value }
        return RadioLive(
            beaconRssi.size, strongest?.key?.let { beaconNames[it] ?: it.takeLast(9) }, strongest?.value,
            counts["wifi_scan"]?.get() ?: 0, wifiAps, rttCapable, counts["wifi_rtt"]?.get() ?: 0,
            gnssUsed, gnssVisible, counts["gnss_fix"]?.get() ?: 0, gnssAccuracy,
        )
    }

    /** Stops every radio and the background thread. */
    fun stop() {
        running = false
        try {
            bleCallback?.let { cb -> context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        } catch (_: Exception) { }
        wifiReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        locationListener?.let { runCatching { location?.removeUpdates(it) } }
        gnssCallback?.let { runCatching { location?.unregisterGnssStatusCallback(it) } }
        thread?.quitSafely()
        thread?.join(1_000)
        thread = null
    }

    private companion object {
        /** Eddystone's 16-bit service id as a full Bluetooth UUID. */
        val EDDYSTONE: ParcelUuid = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
    }
}
