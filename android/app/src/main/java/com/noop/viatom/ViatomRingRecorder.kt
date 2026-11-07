package com.noop.viatom

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import com.noop.data.HrRow
import com.noop.data.StreamBatch
import com.noop.data.WhoopRepository
import com.noop.ui.blePermissions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Independent auxiliary recorder for the owner's SleepImage PO2 ring on the current A5 transport —
 * scan → connect → authenticate → notification stream → decrypt → record. Owns its OWN scanner and
 * GATT and never touches [com.noop.ble.SourceCoordinator] or WhoopBleClient, so WHOOP keeps running
 * undisturbed beside it (a paired night needs both). Kotlin mirror of the probe's validated flow in
 * Tools/sleepimage-probe (same connect sequence, same chunking, same reconnect/re-auth resilience).
 *
 * Owner credentials are REQUIRED and stay in app-private storage (`files/credentials.json`,
 * `{"secret_hex": "…", "access_hex": "…", "etype": 1}`) — never committed, logged or bundled.
 * The recorder sends only the two requests the captured vendor session sent (auth exchange +
 * AES-128 notification control); no settings, reset, firmware or delete commands exist here.
 *
 * Persistence: every decrypted record lands in `files/viatom-a5-<start>.jsonl` with host-receipt
 * timestamps; each new status record's pulse also persists to `hrSample` under device id
 * [DEVICE_ID] via the injected [persist] sink (a real measured bpm under a separate device id —
 * never mixed with WHOOP samples). SpO2/waveform/accel have no honest column yet; the JSONL is the
 * record of record until they get one.
 */
@SuppressLint("MissingPermission") // every BLE call is behind the runtime-permission preflight
class ViatomRingRecorder private constructor(private val appContext: Context) {

    enum class Phase { IDLE, SCANNING, CONNECTING, AUTHENTICATING, RECORDING, FAILED }

    data class Reading(
        val spo2: Int?, val pulse: Int?, val battery: Int?,
        val perfusion: Int, val motion: Int, val worn: Boolean, val atMs: Long,
    )

    data class State(
        val phase: Phase = Phase.IDLE,
        val deviceName: String? = null,
        val deviceAddress: String? = null,
        val reading: Reading? = null,
        val statusFrames: Int = 0,
        val waveformFrames: Int = 0,
        val accelFrames: Int = 0,
        val otherFrames: Int = 0,
        val gaps: Int = 0,
        val reconnects: Int = 0,
        val recordFile: String? = null,
        val message: String? = null,
        val credentialsInstalled: Boolean = false,
        val startedAtMs: Long? = null,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Store the recorder persists pulse rows into — set once at the composition root and left
     * resident: the recorder MUST outlive the screen that wired it, so a navigation away can't
     * silently stop persistence mid-recording. Writes go through [persistScope], a supervisor
     * scope the recorder owns rather than borrows.
     */
    var repository: WhoopRepository? = null
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var credSecret: ByteArray? = null
    private var credAccess: ByteArray? = null
    private var credEtype = 1

    private var gatt: BluetoothGatt? = null
    private var a5Write: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var session: ViatomA5Session? = null
    private var stream = ViatomA5Stream()
    private var scanning = false
    private var recording = false
    private var authSent = false
    private var controlSent = false
    private var controlWritePending = false
    private var lastStatusIndex = -1L
    private var recordFileName: String? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val scanTimeout = Runnable { stopScan(); if (device == null) fail("No PO2 candidate seen in 20 s") }
    private val setupTimeout = Runnable {
        if (!controlSent) fail("Setup timed out before the notification stream opened")
    }
    private val reconnectTask = Runnable { connectNow() }

    companion object {
        const val DEVICE_ID = "si-po2"
        private const val SCAN_WINDOW_MS = 20_000L
        private const val SETUP_TIMEOUT_MS = 20_000L
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val WRITE_CHUNK = 16
        private val CCC = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ADVERTISED_SERVICE = ParcelUuid.fromString(ViatomA5Request.SERVICE_UUID)
        private val NAME_TOKENS = listOf("po2", "sleepimage", "o2ring")

        @Volatile private var shared: ViatomRingRecorder? = null

        /** Process-wide recorder instance; the connection lifecycle outlives any one screen. */
        fun shared(context: Context): ViatomRingRecorder =
            shared ?: synchronized(this) {
                shared ?: ViatomRingRecorder(context.applicationContext).also { shared = it }
            }

        /** True when an owner credential pair is installed in app-private storage. */
        fun credentialsInstalled(context: Context): Boolean =
            File(context.filesDir, "credentials.json").exists()
    }

    // ---- lifecycle -------------------------------------------------------

    /** Start a recording session: preflight → scan → connect → auth → stream. Idempotent. */
    fun start() {
        if (_state.value.phase != Phase.IDLE && _state.value.phase != Phase.FAILED) return
        if (!hasBlePermissions()) {
            fail("This OS version's required Bluetooth permission is not granted")
            return
        }
        if (!loadCredentials()) {
            fail("No credentials.json in app-private storage — install the owner credential pair")
            return
        }
        reset()
        _state.value = State(
            phase = Phase.SCANNING,
            credentialsInstalled = true,
            recordFile = recordFileName,
            startedAtMs = System.currentTimeMillis(),
            message = "Scanning for a PO2 candidate…",
        )
        recordRow(event("record_start", null))
        acquireWakeLock()
        ViatomRingService.start(appContext)
        startScan()
    }

    /** User-requested stop. Idempotent; safe to call from any phase. */
    fun stop() {
        val wasRecording = recording
        recording = false
        handler.removeCallbacks(reconnectTask)
        if (wasRecording) {
            recordRow(event("record_stop", null))
        }
        closeGatt()
        releaseWakeLock()
        ViatomRingService.stop(appContext)
        _state.value = _state.value.copy(phase = Phase.IDLE, message = "Stopped")
    }

    private fun reset() {
        recording = true
        scanning = false
        authSent = false
        controlSent = false
        controlWritePending = false
        lastStatusIndex = -1L
        device = null
        gatt = null
        a5Write = null
        session = null
        stream = ViatomA5Stream()
        writeQueue.clear()
        writeInFlight = false
        recordFileName = "viatom-a5-" + System.currentTimeMillis() + ".jsonl"
    }

    // ---- preflight --------------------------------------------------------

    private fun hasBlePermissions(): Boolean =
        // The shared OS split — granular Bluetooth perms on API 31+, fine location below, where the
        // platform still gates BLE scan results behind it (BLUETOOTH_SCAN doesn't exist there, so
        // checking it directly would refuse every pre-12 start).
        blePermissions().all {
            appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    /** Owner pair from `files/credentials.json` — same contract as the probe; never logged. */
    private fun loadCredentials(): Boolean {
        val file = File(appContext.filesDir, "credentials.json")
        if (!file.exists()) return false
        return try { parseCredentials(file.readText()) } catch (_: Exception) { false }
    }

    /**
     * The SAME bounds ViatomA5Session enforces at construction — anything it would reject
     * (oversized secret, out-of-range etype) fails here instead of being stored and only
     * discovered at connect time.
     */
    private fun parseCredentials(text: String): Boolean {
        val cred = JSONObject(text)
        val secret = hex(cred.getString("secret_hex"))
        val access = hex(cred.getString("access_hex"))
        val etype = cred.optInt("etype", 1)
        if (secret.size !in 16..4096 || access.isEmpty() || access.size + 4 > 16 || etype !in 0..255) {
            return false
        }
        credSecret = secret
        credAccess = access
        credEtype = etype
        return true
    }

    /**
     * Install an owner credential pair chosen through the storage picker — the path non-debuggable
     * (staging/release) builds need, since `run-as` can't reach their files dir. Validates the same
     * shape [loadCredentials] requires before anything lands on disk. Returns true when a usable
     * file was installed; the raw bytes are never logged.
     */
    fun installCredentials(uri: android.net.Uri): Boolean {
        return try {
            val text = appContext.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
            if (!parseCredentials(text)) return false
            File(appContext.filesDir, "credentials.json").writeText(text)
            _state.value = _state.value.copy(credentialsInstalled = true)
            true
        } catch (_: Exception) { false }
    }

    /** Newest recording JSONL — the live session's file first, else the most recent one on disk. */
    fun latestRecording(): File? {
        val current = _state.value.recordFile?.let { File(appContext.filesDir, it) }
        if (current != null && current.isFile) return current
        return appContext.filesDir
            .listFiles { f -> f.name.startsWith("viatom-a5-") && f.name.endsWith(".jsonl") }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = appContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "noop:viatomA5Record")
                .also { it.setReferenceCounted(false) }
        }
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    // ---- scan -------------------------------------------------------------

    private fun startScan() {
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { fail("Bluetooth is off"); return }
        try {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            scanning = true
            handler.postDelayed(scanTimeout, SCAN_WINDOW_MS)
        } catch (e: SecurityException) { fail("BLE scan refused: ${e.message}") }
          catch (e: RuntimeException) { fail("BLE scan failed: ${e.message}") }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanFailed(code: Int) { scanning = false; fail("Scan failed (code $code)") }
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            val record = result.scanRecord ?: return
            val name = record.deviceName
            val lower = name?.lowercase(Locale.ROOT) ?: ""
            val services = record.serviceUuids
            val candidate = (services != null && services.contains(ADVERTISED_SERVICE)) ||
                NAME_TOKENS.any { lower.contains(it) }
            if (!candidate) return
            stopScan()
            device = result.device
            connectNow()
        }
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        val scanner = appContext.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    // ---- connect / authenticate ------------------------------------------

    private fun connectNow() {
        val target = device ?: return fail("No device to connect")
        if (!recording) return
        closeGattOnly()
        session = try {
            ViatomA5Session(credSecret ?: return fail("credentials lost"), credEtype)
        } catch (e: ViatomA5Session.SessionException) {
            return fail("credentials rejected: ${e.message}")
        }
        stream = ViatomA5Stream()
        authSent = false
        controlSent = false
        _state.value = _state.value.copy(
            phase = Phase.CONNECTING,
            deviceName = runCatching { target.name }.getOrNull() ?: _state.value.deviceName,
            deviceAddress = target.address,
            message = "Connecting…",
        )
        try {
            gatt = target.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            handler.postDelayed(setupTimeout, SETUP_TIMEOUT_MS)
        } catch (e: SecurityException) { fail("BLE connect refused: ${e.message}") }
          catch (e: RuntimeException) { fail("BLE connect failed: ${e.message}") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(connection: BluetoothGatt, code: Int, state: Int) {
            handler.post {
                if (connection != gatt) return@post
                if (code == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        if (!connection.discoverServices()) fail("Service discovery did not start")
                    } catch (e: SecurityException) { fail("BLE discover refused: ${e.message}") }
                } else if (state == BluetoothProfile.STATE_DISCONNECTED || code != BluetoothGatt.GATT_SUCCESS) {
                    onLinkDown(code)
                }
            }
        }

        override fun onServicesDiscovered(connection: BluetoothGatt, code: Int) {
            handler.post {
                if (connection != gatt) return@post
                if (code != BluetoothGatt.GATT_SUCCESS) return@post fail("Service discovery failed (status $code)")
                beginA5(connection)
            }
        }

        override fun onDescriptorWrite(
            connection: BluetoothGatt, descriptor: BluetoothGattDescriptor, code: Int,
        ) {
            handler.post {
                if (connection != gatt || authSent) return@post
                if (code != BluetoothGatt.GATT_SUCCESS) return@post fail("CCC subscribe failed (status $code)")
                sendAuthRequest(connection)
            }
        }

        override fun onCharacteristicWrite(
            connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, code: Int,
        ) {
            handler.post {
                if (connection != gatt) return@post
                writeInFlight = false
                if (code != BluetoothGatt.GATT_SUCCESS) {
                    return@post fail("BLE write failed (status $code)")
                }
                drainWrites(connection)
            }
        }

        override fun onCharacteristicChanged(
            connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotification(connection, value.copyOf())
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            connection: BluetoothGatt, characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { handleNotification(connection, it.copyOf()) }
            }
        }
    }

    private fun onLinkDown(code: Int) {
        handler.removeCallbacks(setupTimeout)
        if (recording) {
            // Overnight resilience — mirror the probe: reconnect and re-authenticate with a fresh
            // timestamp whether the stream was open or setup was still in flight. Each connection
            // negotiates its own session key; never join frames across the gap.
            val midStream = controlSent
            recordRow(
                event(
                    "gap",
                    "disconnected status=$code ${if (midStream) "mid-stream" else "during setup"}; reconnecting in 3 s",
                ),
            )
            _state.value = _state.value.copy(
                phase = Phase.CONNECTING,
                gaps = _state.value.gaps + 1,
                reconnects = _state.value.reconnects + 1,
                message = "Link down (status $code) — reconnecting",
            )
            session = null
            stream = ViatomA5Stream()
            controlSent = false
            closeGattOnly()
            handler.postDelayed(reconnectTask, RECONNECT_DELAY_MS)
        } else {
            closeGatt()
        }
    }

    /**
     * The session key was invalidated without a link drop (a corrupt-but-CRC-valid record). Same
     * recovery as [onLinkDown]: close this GATT (its disconnect callback is ignored — the reference
     * is already cleared), then reconnect and re-authenticate on a fresh timestamped session.
     */
    private fun recoverSession(detail: String) {
        if (!recording) return
        recordRow(event("session_recover", detail))
        _state.value = _state.value.copy(
            phase = Phase.CONNECTING,
            gaps = _state.value.gaps + 1,
            reconnects = _state.value.reconnects + 1,
            message = "Session invalidated — reconnecting",
        )
        session = null
        stream = ViatomA5Stream()
        controlSent = false
        closeGattOnly()
        handler.removeCallbacks(reconnectTask)
        handler.postDelayed(reconnectTask, RECONNECT_DELAY_MS)
    }

    private fun beginA5(connection: BluetoothGatt) {
        val service = connection.getService(
            java.util.UUID.fromString(ViatomA5Request.SERVICE_UUID),
        )
        val notify = service?.getCharacteristic(
            java.util.UUID.fromString(ViatomA5Request.NOTIFY_UUID),
        )
        a5Write = service?.getCharacteristic(
            java.util.UUID.fromString(ViatomA5Request.WRITE_UUID),
        )
        val ccc = notify?.getDescriptor(CCC)
        if (notify == null || a5Write == null || ccc == null) {
            return fail("A5 service/characteristic/CCC not present on this device")
        }
        try {
            if (!connection.setCharacteristicNotification(notify, true)) {
                return fail("Local notification registration refused")
            }
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                @Suppress("DEPRECATION")
                connection.writeDescriptor(ccc)
            }
            if (!accepted) fail("Android refused the CCC write")
        } catch (e: SecurityException) { fail("BLE subscribe refused: ${e.message}") }
    }

    private fun sendAuthRequest(connection: BluetoothGatt) {
        val secret = credSecret ?: return fail("credentials lost")
        val access = credAccess ?: return fail("credentials lost")
        authSent = true
        val payload = ViatomA5Request.authExchange(secret, access, System.currentTimeMillis() / 1000L)
        _state.value = _state.value.copy(phase = Phase.AUTHENTICATING, message = "Authenticating…")
        writeFrame(connection, ViatomA5Request.COMMAND_AUTH_EXCHANGE, payload)
    }

    private fun sendControlRequest(connection: BluetoothGatt) {
        try {
            val payload = ViatomA5Request.notifyControl(session!!.sessionKey())
            // The stream opens only once the WHOLE queued frame finishes on the link — a refused
            // or failed chunk calls fail() and must never be overwritten with "Recording".
            controlWritePending = true
            writeFrame(connection, ViatomA5Request.COMMAND_NOTIFY_CONTROL, payload)
        } catch (e: Exception) { controlWritePending = false; fail("Control request failed: ${e.message}") }
    }

    // A5 frames are chunked into 16-byte writes, but Android allows only ONE GATT operation in
    // flight — even WRITE_TYPE_NO_RESPONSE occupies mDeviceBusy until onCharacteristicWrite, so a
    // second back-to-back write returns WRITE_REQUEST_BUSY (review on PR #23 caught exactly this in
    // the probe). Queue the chunks and drain strictly through the write-completion callback.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private fun writeFrame(connection: BluetoothGatt, command: Int, payload: ByteArray) {
        val frame = ViatomA5Request.wrap(command, payload)
        var offset = 0
        while (offset < frame.size) {
            val length = minOf(WRITE_CHUNK, frame.size - offset)
            writeQueue.addLast(frame.copyOfRange(offset, offset + length))
            offset += length
        }
        drainWrites(connection)
    }

    private fun drainWrites(connection: BluetoothGatt) {
        if (writeInFlight) return
        val chunk = writeQueue.removeFirstOrNull()
        if (chunk == null) { onWritesDrained(); return }
        val target = a5Write ?: return fail("Write characteristic not resolved")
        writeInFlight = true
        try {
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                connection.writeCharacteristic(
                    target, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                target.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                @Suppress("DEPRECATION")
                target.setValue(chunk)
                @Suppress("DEPRECATION")
                connection.writeCharacteristic(target)
            }
            if (!accepted) {
                writeInFlight = false
                fail("Android refused a write chunk")
            }
        } catch (e: SecurityException) {
            writeInFlight = false
            fail("BLE write refused: ${e.message}")
        }
    }

    /**
     * The write queue just emptied after a completed chunk — the control request is the only
     * queued frame that opens a phase transition, and only here is it provably on the link.
     */
    private fun onWritesDrained() {
        if (!controlWritePending || !recording) return
        controlWritePending = false
        controlSent = true
        handler.removeCallbacks(setupTimeout)
        _state.value = _state.value.copy(phase = Phase.RECORDING, message = "Recording")
        recordRow(event("stream_open", null))
    }

    // ---- notifications → frames → records --------------------------------

    private fun handleNotification(connection: BluetoothGatt, value: ByteArray) {
        val ts = System.currentTimeMillis()
        handler.post {
            if (connection != gatt || !recording) return@post
            for (frame in stream.append(value)) handleFrame(connection, frame, ts)
        }
    }

    private fun handleFrame(connection: BluetoothGatt, frame: ViatomA5Frame, ts: Long) {
        when (frame.commandRaw) {
            ViatomA5Request.COMMAND_AUTH_EXCHANGE -> {
                try {
                    session!!.acceptExchange(frame)
                    sendControlRequest(connection)
                } catch (e: Exception) { fail("Auth exchange rejected: ${e.message}") }
            }
            ViatomA5Request.COMMAND_NOTIFY_CONTROL -> Unit // notification control accepted by the ring
            17, 18, 20 -> decryptAndRecord(frame, ts)
            else -> _state.value = _state.value.copy(otherFrames = _state.value.otherFrames + 1)
        }
    }

    private fun decryptAndRecord(frame: ViatomA5Frame, ts: Long) {
        val active = session ?: return
        val clear = try {
            active.decrypt(frame)
        } catch (e: ViatomA5Session.SessionException) {
            recordRow(event("decrypt_failed", e.failure.name))
            // DECRYPTION_FAILED invalidates the session key inside decrypt() — without recovery
            // every later record fails NO_SESSION and the rest of the night is silently lost.
            // Reconnect and re-authenticate for a fresh session, exactly as a link drop does.
            if (!active.hasSession) recoverSession("session invalidated by ${e.failure.name}")
            return
        }
        when (frame.commandRaw) {
            17 -> {
                _state.value = _state.value.copy(statusFrames = _state.value.statusFrames + 1)
                val record = ViatomStatusRecord.decode(clear)
                if (record != null) onStatusRecord(record, ts) else
                    recordRow("{\"ts_ms\":$ts,\"kind\":\"status\",\"unparsed\":\"${hex(clear)}\"}")
            }
            18 -> {
                _state.value = _state.value.copy(waveformFrames = _state.value.waveformFrames + 1)
                val wave = ViatomWaveform.decode(clear)
                recordRow(
                    if (wave != null) {
                        buildString {
                            append("{\"ts_ms\":").append(ts)
                            append(",\"kind\":\"waveform\",\"index\":").append(wave.deviceIndex)
                            append(",\"samples\":[")
                            wave.samples.forEachIndexed { i, s ->
                                if (i > 0) append(','); append(s)
                            }
                            append("]}")
                        }
                    } else "{\"ts_ms\":$ts,\"kind\":\"waveform\",\"unparsed\":\"${hex(clear)}\"}",
                )
            }
            20 -> {
                _state.value = _state.value.copy(accelFrames = _state.value.accelFrames + 1)
                recordRow("{\"ts_ms\":$ts,\"kind\":\"accel\",\"hex\":\"${hex(clear)}\"}")
            }
        }
    }

    private fun onStatusRecord(record: ViatomStatusRecord, ts: Long) {
        recordRow(
            "{\"ts_ms\":$ts,\"kind\":\"status\",\"index\":${record.index}," +
                "\"state\":${record.state},\"spo2\":${record.rawSpO2}," +
                "\"perfusion\":${record.rawPerfusion},\"pulse\":${record.rawPulse}," +
                "\"motion\":${record.rawMotion},\"battery\":${record.rawBattery}}",
        )
        _state.value = _state.value.copy(
            reading = Reading(
                spo2 = record.spo2Percent,
                pulse = record.pulseBpm,
                battery = record.batteryPercent,
                perfusion = record.rawPerfusion,
                motion = record.rawMotion,
                worn = record.isMeasuring,
                atMs = ts,
            ),
        )
        // Each index repeats (~2 Hz); persist the pulse once per index under the ring's own device
        // id — a real measured bpm that must never share WHOOP's sample namespace.
        val bpm = record.pulseBpm
        if (bpm != null && record.index != lastStatusIndex) {
            lastStatusIndex = record.index
            val repo = repository
            if (repo != null) {
                val row = HrRow(ts / 1000L, bpm)
                persistScope.launch {
                    runCatching { repo.insert(StreamBatch(hr = listOf(row)), DEVICE_ID) }
                }
            }
        }
    }

    // ---- recording file ---------------------------------------------------

    private fun event(kind: String, detail: String?): String = buildString {
        append("{\"ts_ms\":").append(System.currentTimeMillis())
        append(",\"elapsed_ms\":").append(SystemClock.elapsedRealtime())
        append(",\"event\":\"").append(kind).append('"')
        if (detail != null) append(",\"detail\":\"").append(detail).append('"')
        append('}')
    }

    private fun recordRow(json: String) {
        val name = recordFileName ?: return
        try {
            FileOutputStream(File(appContext.filesDir, name), true).use {
                it.write((json + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) { /* a dropped row must not take the link down */ }
    }

    // ---- teardown ---------------------------------------------------------

    private fun fail(message: String) {
        recording = false
        handler.removeCallbacks(reconnectTask)
        closeGatt()
        releaseWakeLock()
        ViatomRingService.stop(appContext)
        _state.value = _state.value.copy(phase = Phase.FAILED, message = message)
    }

    private fun closeGatt() {
        recording = false
        // A scan can still be live when teardown arrives (stop-before-discovery, scan-window
        // failure) — cancel it AND its timeout so a dead callback can't re-register on the next
        // start or overwrite the terminal state after the fact.
        stopScan()
        handler.removeCallbacks(setupTimeout)
        closeGattOnly()
    }

    private fun closeGattOnly() {
        handler.removeCallbacks(setupTimeout)
        writeQueue.clear()
        writeInFlight = false
        controlWritePending = false
        val old = gatt
        gatt = null
        a5Write = null
        if (old != null) {
            try { old.disconnect() } catch (_: SecurityException) {}
            try { old.close() } catch (_: SecurityException) {}
        }
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { Integer.parseInt(s.substring(it * 2, it * 2 + 2), 16).toByte() }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
