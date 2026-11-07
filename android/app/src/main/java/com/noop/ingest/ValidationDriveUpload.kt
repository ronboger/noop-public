package com.noop.ingest

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt-in upload of private validation bundles through Android's Storage Access Framework (SAF).
 *
 * The user selects a Google Drive folder once in the system picker. Android grants NOOP access only
 * to that folder; the Drive DocumentsProvider owns account authentication and cloud sync. NOOP needs
 * no Google Cloud project, OAuth client, token, Drive API, or visibility into any other Drive file.
 */
object ValidationDriveUpload {

    const val RECOMMENDED_FOLDER_NAME = "NOOP Validation Bundles"
    internal const val MANUAL = "explicit_manual_upload"

    private const val PERIODIC_WORK = "noop_validation_drive_daily"
    private const val IMMEDIATE_WORK = "noop_validation_drive_now"

    /**
     * Serializes the two upload triggers. [PERIODIC_WORK] and [IMMEDIATE_WORK] are distinct
     * WorkManager unique names, so WorkManager can run both workers concurrently; if each read the
     * session gate before either recorded its upload, a re-sync that changed the input fingerprint
     * between their exports would produce two Drive bundles for the same night. Both workers run in
     * this process, so a process-wide mutex around gate-read → upload → gate-write is sufficient.
     */
    private val uploadMutex = Mutex()

    internal suspend fun <T> withUploadLock(block: suspend () -> T): T =
        uploadMutex.withLock { block() }

    /**
     * Immutable identity for one produced artifact. App version is included because a prospective
     * phase may change implementation while retaining the same raw-input fingerprint.
     */
    fun artifactKey(
        inputFingerprintSha256: String,
        algorithmVersion: String,
        bundleVersion: Int,
        appVersionCode: Int,
    ): String = sha256(
        listOf(
            inputFingerprintSha256,
            algorithmVersion,
            bundleVersion.toString(),
            appVersionCode.toString(),
        ).joinToString("\n"),
    )

    /**
     * Stable Drive-visible name. Version fields preserve same-input phase bridges; the key prefix
     * makes retries idempotent even if local preferences are cleared.
     */
    fun driveFileName(
        publicName: String,
        bundleVersion: Int,
        appVersionCode: Int,
        artifactKey: String,
    ): String = driveFileName(
        publicName = publicName,
        algorithmVersion = null,
        bundleVersion = bundleVersion,
        appVersionCode = appVersionCode,
        artifactKey = artifactKey,
    )

    /** Versioned name including the algorithm phase; old callers retain the v5-era shape above. */
    fun driveFileName(
        publicName: String,
        algorithmVersion: String?,
        bundleVersion: Int,
        appVersionCode: Int,
        artifactKey: String,
    ): String {
        val stem = publicName.removeSuffix(".zip")
        val algorithmTag = algorithmVersion
            ?.replace(Regex("[^A-Za-z0-9]+"), "-")
            ?.trim('-')
            ?.takeIf { it.isNotEmpty() }
            ?.let { "-$it" }
            ?: ""
        return "$stem$algorithmTag-v$bundleVersion-code$appVersionCode-${artifactKey.take(12)}.zip"
    }

    internal fun sameDay(aMs: Long, bMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()

    internal fun automaticDue(nowMs: Long, wakeTs: Long, confirmedTs: Long?, lastUploadMs: Long): Boolean =
        confirmedTs != null && confirmedTs > wakeTs &&
            confirmedTs <= nowMs / 1000 - 3 * 3600 &&
            sameDay(wakeTs * 1000, nowMs) &&
            (lastUploadMs == 0L || !sameDay(lastUploadMs, nowMs))

    /**
     * One bundle per sleep session per algorithm phase / app version.
     *
     * A re-sync that mutates raw samples of an already-uploaded night must NOT produce a second
     * Drive file (input fingerprints churn while the night is still the same). A new night or a new
     * algorithm/app version must produce a fresh bundle so algorithm iteration still gets new files.
     *
     * @return false only when [sessionEndTs], [algorithmVersion], and [appVersionCode] all match the
     * last successful upload; true otherwise (including when nothing has been uploaded yet).
     */
    fun shouldUploadSession(
        lastSessionEndTs: Long,
        lastAlgorithmVersion: String?,
        lastAppVersionCode: Int,
        sessionEndTs: Long,
        algorithmVersion: String,
        appVersionCode: Int,
    ): Boolean =
        sessionEndTs != lastSessionEndTs ||
            algorithmVersion != lastAlgorithmVersion ||
            appVersionCode != lastAppVersionCode

    private fun constraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

    /** Self-heal or cancel the unique daily schedule from the persisted folder grant. */
    fun reschedule(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        if (!ValidationDrivePrefs.autoEnabled(context) ||
            ValidationDrivePrefs.destinationUri(context) == null
        ) {
            wm.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<ValidationDriveUploadWorker>(30, TimeUnit.MINUTES)
            .setConstraints(constraints())
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Queue a deduplicated immediate attempt; constraints still prevent cellular or low-battery work. */
    fun enqueueNow(context: Context, replaceExisting: Boolean = false) {
        if (!ValidationDrivePrefs.autoEnabled(context) ||
            ValidationDrivePrefs.destinationUri(context) == null
        ) return
        val request = OneTimeWorkRequestBuilder<ValidationDriveUploadWorker>()
            .setInputData(androidx.work.workDataOf(MANUAL to true))
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(PERIODIC_WORK)
        wm.cancelUniqueWork(IMMEDIATE_WORK)
    }

    internal fun sha256(file: File): String =
        file.inputStream().buffered().use(::sha256)

    internal fun sha256(context: Context, uri: Uri): String {
        val input = checkNotNull(context.contentResolver.openInputStream(uri)) {
            "The selected folder did not return the staged validation file."
        }
        return input.buffered().use(::sha256)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}

/** Durable, non-secret destination and upload status. */
object ValidationDrivePrefs {
    private const val FILE = "validation_drive_upload"

    enum class State { OFF, READY, UPLOADING, UPLOADED, ERROR }

    data class Snapshot(
        val destinationUri: String?,
        val destinationLabel: String?,
        val autoEnabled: Boolean,
        val state: State,
        val lastUploadMs: Long,
        val lastFileName: String?,
        val lastArtifactKey: String?,
        val lastError: String?,
        /** Sleep session end (unix seconds) of the last successful upload; 0 if none. */
        val lastSessionEndTs: Long = 0L,
        val lastAlgorithmVersion: String? = null,
        val lastAppVersionCode: Int = 0,
    )

    private fun p(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun destinationUri(context: Context): Uri? =
        p(context).getString("destination_uri", null)?.let(Uri::parse)

    fun setDestination(context: Context, uri: Uri, label: String?) {
        p(context).edit()
            .putString("destination_uri", uri.toString())
            .putString("destination_label", label)
            .putString("state", State.READY.name)
            .remove("last_error")
            .apply()
    }

    fun autoEnabled(context: Context): Boolean = p(context).getBoolean("auto", false)
    @Synchronized fun setAutoEnabled(context: Context, value: Boolean) {
        val edit = p(context).edit().putBoolean("auto", value)
        if (!value) edit.remove("presence_times")
        edit.apply()
    }

    // Only record affirmative user input while opted in. Keep at most one timestamp per
    // five minutes for the last day so a later sleep sync can revise the end of the night.
    @Synchronized fun recordPresence(context: Context) {
        if (!autoEnabled(context)) return
        val now = System.currentTimeMillis() / 1000
        val prefs = p(context)
        val times = prefs.getStringSet("presence_times", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.filter { it in (now - 86400)..now }
        if (times.any { now - it < 300 }) return
        prefs.edit().putStringSet("presence_times", (times + now).map { it.toString() }.toSet()).apply()
    }

    fun firstPresenceAfter(context: Context, wakeTs: Long): Long? =
        p(context).getStringSet("presence_times", emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.filter { it > wakeTs }.minOrNull()

    fun snapshot(context: Context): Snapshot {
        val prefs = p(context)
        val state = runCatching {
            State.valueOf(prefs.getString("state", State.OFF.name)!!)
        }.getOrDefault(State.OFF)
        return Snapshot(
            destinationUri = prefs.getString("destination_uri", null),
            destinationLabel = prefs.getString("destination_label", null),
            autoEnabled = prefs.getBoolean("auto", false),
            state = state,
            lastUploadMs = prefs.getLong("last_upload_ms", 0L),
            lastFileName = prefs.getString("last_file_name", null),
            lastArtifactKey = prefs.getString("last_artifact_key", null),
            lastError = prefs.getString("last_error", null),
            lastSessionEndTs = prefs.getLong("last_session_end_ts", 0L),
            lastAlgorithmVersion = prefs.getString("last_algorithm_version", null),
            lastAppVersionCode = prefs.getInt("last_app_version_code", 0),
        )
    }

    fun markReady(context: Context) {
        p(context).edit()
            .putString("state", State.READY.name)
            .remove("last_error")
            .apply()
    }

    fun markUploading(context: Context) {
        p(context).edit()
            .putString("state", State.UPLOADING.name)
            .remove("last_error")
            .apply()
    }

    /**
     * Restore UI state after a skip path where the previously uploaded bundle is still current.
     * Only flips [State] back to [State.UPLOADED] and clears [last_error]; does not touch
     * last_upload_ms / last_file_name / last_artifact_key / session-gate keys.
     */
    fun restoreUploadedState(context: Context) {
        p(context).edit()
            .putString("state", State.UPLOADED.name)
            .remove("last_error")
            .apply()
    }

    fun markUploaded(
        context: Context,
        artifactKey: String,
        fileName: String,
        sessionEndTs: Long,
        algorithmVersion: String,
        appVersionCode: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        p(context).edit()
            .putString("state", State.UPLOADED.name)
            .putLong("last_upload_ms", nowMs)
            .putString("last_file_name", fileName)
            .putString("last_artifact_key", artifactKey)
            .putLong("last_session_end_ts", sessionEndTs)
            .putString("last_algorithm_version", algorithmVersion)
            .putInt("last_app_version_code", appVersionCode)
            .remove("last_error")
            .apply()
    }

    fun markError(context: Context, message: String) {
        p(context).edit()
            .putString("state", State.ERROR.name)
            .putString("last_error", message.take(300))
            .apply()
    }

    fun clear(context: Context) {
        p(context).edit().clear().apply()
    }
}

/**
 * Writes through the user-granted DocumentsProvider folder and verifies the provider round-trip.
 * No original or already-complete artifact is overwritten.
 */
internal object ValidationFolderWriter {

    fun write(
        context: Context,
        destinationUri: Uri,
        artifact: RawSensorExport.ValidationBundleArtifact,
        artifactKey: String,
    ): String {
        check(artifact.file.isFile) { "Validation ZIP is missing before folder upload." }
        val root = checkNotNull(DocumentFile.fromTreeUri(context, destinationUri)) {
            "The selected Drive folder is no longer available."
        }
        check(root.isDirectory && root.canWrite()) {
            "NOOP no longer has write access to the selected Drive folder."
        }
        val finalName = ValidationDriveUpload.driveFileName(
            artifact.publicName,
            artifact.algorithmVersion,
            artifact.bundleVersion,
            artifact.appVersionCode,
            artifactKey,
        )
        val sourceHash = ValidationDriveUpload.sha256(artifact.file)
        root.findFile(finalName)?.let { existing ->
            check(existing.isFile) { "A folder already uses the validation filename $finalName." }
            // Drive's DocumentsProvider can report a stale or unknown length even when its input
            // stream already contains the complete file. The byte hash is the authoritative
            // round-trip check and preserves the no-overwrite rule without trusting cached metadata.
            val same = ValidationDriveUpload.sha256(context, existing.uri) == sourceHash
            check(same) {
                "A different file already uses the validation filename $finalName; nothing was overwritten."
            }
            return finalName
        }

        val stagedName = "$finalName.uploading-${UUID.randomUUID()}"
        val staged = checkNotNull(root.createFile("application/zip", stagedName)) {
            "The selected Drive folder refused a new validation file."
        }
        try {
            val output = checkNotNull(context.contentResolver.openOutputStream(staged.uri, "w")) {
                "The selected Drive folder refused validation file contents."
            }
            val copiedBytes = output.buffered().use { destination ->
                artifact.file.inputStream().buffered().use { source ->
                    source.copyTo(destination)
                }
            }
            check(copiedBytes == artifact.file.length()) {
                "Drive staging copy did not consume the complete local validation ZIP."
            }
            check(ValidationDriveUpload.sha256(context, staged.uri) == sourceHash) {
                "Drive staging SHA-256 did not match the local validation ZIP."
            }
            check(staged.renameTo(finalName)) {
                "Drive verified the ZIP but could not finalize its filename."
            }
            val finalized = checkNotNull(root.findFile(finalName)) {
                "Drive finalized the ZIP but could not list it afterward."
            }
            check(ValidationDriveUpload.sha256(context, finalized.uri) == sourceHash) {
                "Final Drive ZIP failed the SHA-256 round trip."
            }
            return finalName
        } catch (error: Exception) {
            runCatching { staged.delete() }
            throw error
        }
    }
}

/** Background build + system-folder write. No Activity, chooser, OAuth token, or direct Drive API. */
class ValidationDriveUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val destination = ValidationDrivePrefs.destinationUri(applicationContext)
        if (!ValidationDrivePrefs.autoEnabled(applicationContext) || destination == null) {
            return Result.success()
        }
        // The periodic and manual workers are separate unique works; the lock keeps their
        // export → session-gate → upload → markUploaded sequences from interleaving.
        return ValidationDriveUpload.withUploadLock { upload(destination) }
    }

    private suspend fun upload(destination: Uri): Result {
        return try {
            val noop = applicationContext as NoopApplication
            val device = noop.activeDeviceId
            val manual = inputData.getBoolean(ValidationDriveUpload.MANUAL, false)
            val now = System.currentTimeMillis()
            val wake = if (manual) null else {
                val lastUpload = ValidationDrivePrefs.snapshot(applicationContext).lastUploadMs
                if (lastUpload > 0 && ValidationDriveUpload.sameDay(lastUpload, now)) return Result.success()
                val nowTs = now / 1000
                val sleeps = noop.repository.sleepSessionsMerged(device, nowTs - 3 * 86400, nowTs + 86400)
                val night = RawSensorExport.selectMainNightForValidation(sleeps) ?: return Result.success()
                if (night.isNapShaped || !ValidationDriveUpload.sameDay(night.endTs * 1000, now) ||
                    nowTs - night.endTs < 3 * 3600) return Result.success()
                val confirmed = ValidationDrivePrefs.firstPresenceAfter(applicationContext, night.endTs)
                if (!ValidationDriveUpload.automaticDue(now, night.endTs, confirmed, lastUpload)) return Result.success()
                night.endTs
            }
            val artifact = RawSensorExport.exportWhoop4Research(
                context = applicationContext,
                repo = noop.repository,
                deviceId = device,
                interactive = false,
            ) ?: run {
                ValidationDrivePrefs.markError(
                    applicationContext,
                    "Could not build the latest validation bundle.",
                )
                return Result.retry()
            }
            try {
                if (wake != null && (artifact.sessionEndTs != wake ||
                    !ValidationDriveUpload.sameDay(wake * 1000, System.currentTimeMillis()))) return Result.success()
                ValidationDrivePrefs.markUploading(applicationContext)
                val snap = ValidationDrivePrefs.snapshot(applicationContext)
                // Session gate first: same night + algorithm + app version must not upload again even
                // when re-sync mutates the input fingerprint (artifactKey would differ).
                if (!ValidationDriveUpload.shouldUploadSession(
                        snap.lastSessionEndTs,
                        snap.lastAlgorithmVersion,
                        snap.lastAppVersionCode,
                        artifact.sessionEndTs,
                        artifact.algorithmVersion,
                        artifact.appVersionCode,
                    )
                ) {
                    // markUploading() ran before the session check; restore UPLOADED so the UI is not stuck.
                    ValidationDrivePrefs.restoreUploadedState(applicationContext)
                    return Result.success()
                }
                val key = ValidationDriveUpload.artifactKey(
                    artifact.inputFingerprintSha256,
                    artifact.algorithmVersion,
                    artifact.bundleVersion,
                    artifact.appVersionCode,
                )
                val expectedName = ValidationDriveUpload.driveFileName(
                    artifact.publicName,
                    artifact.algorithmVersion,
                    artifact.bundleVersion,
                    artifact.appVersionCode,
                    key,
                )
                if (snap.lastArtifactKey == key) {
                    ValidationDrivePrefs.markUploaded(
                        applicationContext,
                        key,
                        expectedName,
                        artifact.sessionEndTs,
                        artifact.algorithmVersion,
                        artifact.appVersionCode,
                    )
                    return Result.success()
                }
                val uploadedName = ValidationFolderWriter.write(
                    context = applicationContext,
                    destinationUri = destination,
                    artifact = artifact,
                    artifactKey = key,
                )
                ValidationDrivePrefs.markUploaded(
                    applicationContext,
                    key,
                    uploadedName,
                    artifact.sessionEndTs,
                    artifact.algorithmVersion,
                    artifact.appVersionCode,
                )
                Result.success()
            } finally {
                if (!artifact.file.delete()) {
                    Log.w(
                        "ValidationDriveUpload",
                        "Could not delete temporary validation ZIP ${artifact.file.name}",
                    )
                }
            }
        } catch (error: IOException) {
            Log.w("ValidationDriveUpload", "Validation folder upload failed", error)
            ValidationDrivePrefs.markError(
                applicationContext,
                error.message ?: "Validation folder upload failed.",
            )
            Result.retry()
        } catch (error: SecurityException) {
            Log.w("ValidationDriveUpload", "Validation folder permission was revoked", error)
            ValidationDrivePrefs.markError(
                applicationContext,
                "Drive folder access was revoked. Choose the folder again.",
            )
            Result.failure()
        } catch (error: Exception) {
            Log.w("ValidationDriveUpload", "Background validation upload failed", error)
            ValidationDrivePrefs.markError(
                applicationContext,
                error.message ?: "Background validation upload failed.",
            )
            Result.retry()
        }
    }
}
