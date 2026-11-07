package com.noop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Whole-store EXPORT / IMPORT for device migration.
 *
 * NOOP keeps everything on-device in a single Room/SQLite file ([WhoopDatabase.DB_NAME]).
 * Moving to a new phone therefore means moving exactly that one file. There is no cloud,
 * no account, nothing leaves the device except through these two explicit, user-driven
 * file operations (a SAF document the user picks).
 *
 * Export: checkpoint the WAL into the main db file, then write a ZIP (the `.noopbak`
 * format) containing the SQLite file plus a small `settings.json` entry (#1000) with the
 * whitelisted profile/display settings (see [BackupSettingsCodec]), so a restore also
 * brings back weight/height/units and not just the rows. ZIP deflate typically reduces a
 * 100 MB+ SQLite backup to 10–20 MB — SQLite's page-aligned text data compresses very
 * well. The ZIP is a standard container: users can rename `.noopbak` → `.zip` and
 * extract the SQLite manually with any archive tool on any OS.
 *
 * Import: detect whether the picked file is a `.noopbak` ZIP (PK magic) or a legacy
 * plain `.sqlite` / `.noopdb` (SQLite magic) and handle both, so old backups keep
 * working. Validates the extracted/direct SQLite header, the backup's origin, AND its
 * structural integrity (`PRAGMA quick_check`, #1014) before touching the live DB.
 * Validates Room compatibility before closing the live store and atomically publishing the staged
 * database and settings. A cold open finishes any interrupted installation before Room reads it.
 */
object DataBackup {

    /** Entry name of the SQLite inside the `.noopbak` ZIP. */
    private const val ZIP_ENTRY_NAME = "noop-backup.sqlite"

    /** Entry name of the optional whitelisted-settings JSON (#1000). Matches the Apple exporter. */
    private const val SETTINGS_ENTRY_NAME = BackupSettingsCodec.ENTRY_NAME
    // #1410: a `manifest.json` entry recording which build produced this file (read only to classify, never
    // restored). Written LAST so older importers that stop at the first .sqlite entry are unaffected.
    private const val MANIFEST_ENTRY_NAME = BackupManifest.ENTRY_NAME

    internal const val MAX_BACKUP_SQLITE_BYTES = 2_147_483_648L
    private const val MAX_BACKUP_SETTINGS_BYTES = 1_048_576L

    /** "PK\u0005\u0006", the ZIP End Of Central Directory signature. */
    private val ZIP_EOCD_SIGNATURE: ByteArray = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    /** The fixed part of an End Of Central Directory record, before any comment. */
    private const val ZIP_EOCD_MIN_BYTES: Int = 22

    /** A ZIP comment can push the EOCD record up to 65535 bytes from the end, plus its own fixed part. */
    private const val ZIP_EOCD_SEARCH_BYTES: Int = 65_535 + ZIP_EOCD_MIN_BYTES

    /** First 16 bytes of every SQLite 3 file: "SQLite format 3\0". */
    private val SQLITE_MAGIC: ByteArray =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
        )

    /**
     * What a post-write check concluded about the file just produced.
     *
     * [UNVERIFIABLE] exists so that failing to READ a backup is never mistaken for evidence against it.
     * The caller deletes a [TORN] file, and deleting on "we could not look" would mean a provider having
     * a bad moment could destroy a backup that was perfectly good.
     */
    internal enum class BackupWriteVerdict { INTACT, TORN, UNVERIFIABLE }

    /**
     * The decision itself, separated from the reading of the file so the whole table can be pinned by a
     * plain JVM test: [tail] is null when the provider would not say how big the file is, and
     * [entryIntact] is null when it could not be opened for reading at all.
     */
    internal fun writeVerdict(tail: ByteArray?, entryIntact: Boolean?): BackupWriteVerdict = when {
        // A tail we COULD read that holds no end record is positive evidence of a cut-short write.
        tail != null && !hasEndOfCentralDirectory(tail) -> BackupWriteVerdict.TORN
        entryIntact == null -> BackupWriteVerdict.UNVERIFIABLE
        entryIntact -> BackupWriteVerdict.INTACT
        else -> BackupWriteVerdict.TORN
    }

    /**
     * Re-read the JUST-WRITTEN `.noopbak` at [uri] and say what it looks like.
     *
     * #1014 (write-side): a torn write, a truncated ZIP or a half-flushed SAF document on a full disk or
     * a flaky provider, otherwise leaves a `.noopbak` that silently "restores" into an empty store, and
     * is caught only by the import-side quick_check much later, when the original may be long gone. Fail
     * HERE at write time instead. Twin of the Apple `writtenBackupIsIntact`.
     *
     * A seekable provider can reject a missing end record cheaply. The streaming check also
     * validates the end record when the provider cannot seek or report its size.
     */
    internal fun verifyWrittenBackup(context: Context, uri: Uri): BackupWriteVerdict {
        val resolver = context.contentResolver
        val tail = readTail(resolver, uri, ZIP_EOCD_SEARCH_BYTES)
        if (tail != null && !hasEndOfCentralDirectory(tail)) return BackupWriteVerdict.TORN
        val entryIntact = runCatching {
            resolver.openInputStream(uri)?.use { backupStreamIsIntact(it) }
        }.getOrNull()
        return writeVerdict(tail, entryIntact)
    }

    /** Folder providers without rename support may expose a partially written canonical file.
     * Inspect only the compressed tail when listing them, avoiding inflation of unselected archives. */
    internal fun hasFinishedSnapshot(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val tail = snapshotTail(context, uri)
        if (tail != null && hasEndOfCentralDirectory(tail)) return true
        // A user may have given a legacy plain-SQLite backup a canonical .noopbak name.
        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(SQLITE_MAGIC.size)
                readFully(input, header) == header.size && header.contentEquals(SQLITE_MAGIC)
            } == true
        }.getOrDefault(false)
    }

    internal fun publishedByAttempt(context: Context, uri: Uri, token: String): Boolean {
        val tail = snapshotTail(context, uri) ?: return false
        return hasSnapshotToken(tail, token)
    }

    internal fun hasSnapshotToken(tail: ByteArray, token: String): Boolean {
        val tag = token.toByteArray(Charsets.US_ASCII)
        val end = tail.size - tag.size - ZIP_EOCD_MIN_BYTES
        if (end < 0 || tag.isEmpty()) return false
        if (ZIP_EOCD_SIGNATURE.indices.any { tail[end + it] != ZIP_EOCD_SIGNATURE[it] }) return false
        val length = (tail[end + 20].toInt() and 255) or ((tail[end + 21].toInt() and 255) shl 8)
        return length == tag.size && tag.indices.all { tail[end + ZIP_EOCD_MIN_BYTES + it] == tag[it] }
    }

    private fun snapshotTail(context: Context, uri: Uri): ByteArray? {
        val resolver = context.contentResolver
        return readTail(resolver, uri, ZIP_EOCD_SEARCH_BYTES) ?: runCatching {
            resolver.openInputStream(uri)?.use { ZipTailInput(it, ZIP_EOCD_SEARCH_BYTES).finish() }
        }.getOrNull()
    }

    private fun readTail(resolver: android.content.ContentResolver, uri: Uri, limit: Int): ByteArray? =
        runCatching {
            val descriptor = resolver.openFileDescriptor(uri, "r") ?: return@runCatching null
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                val length = descriptor.statSize
                if (length <= 0L) return@use null
                val window = minOf(length, limit.toLong()).toInt()
                stream.channel.position(length - window)
                ByteArray(window).takeIf { readFully(stream, it) == window }
            }
        }.getOrNull()

    /**
     * Whether [tail], the last bytes of a file, contains a ZIP End Of Central Directory record.
     *
     * Scans rather than checking a fixed offset, because the record sits [ZIP_EOCD_MIN_BYTES] from the
     * end only when the archive carries no comment.
     *
     * Finding the signature is NOT enough, and a first pass here that stopped there was wrong: lopping a
     * single byte off a good archive leaves the signature untouched and only damages the fields behind
     * it. So the record has to ADD UP. Its trailing comment-length field must account for exactly the
     * bytes that follow it, which a file cut short cannot do, whether it was cut by a byte or a
     * megabyte.
     */
    internal fun hasEndOfCentralDirectory(tail: ByteArray): Boolean {
        if (tail.size < ZIP_EOCD_MIN_BYTES) return false
        // From the end backwards: the LAST complete record is the real one.
        for (i in tail.size - ZIP_EOCD_MIN_BYTES downTo 0) {
            var hit = true
            for (k in ZIP_EOCD_SIGNATURE.indices) {
                if (tail[i + k] != ZIP_EOCD_SIGNATURE[k]) { hit = false; break }
            }
            if (!hit) continue
            // Comment length is the last field of the record, little-endian.
            val commentLength = (tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8)
            if (i + ZIP_EOCD_MIN_BYTES + commentLength == tail.size) return true
        }
        return false
    }

    /** Check every entry's CRC, the embedded database header and the archive's end record.
     * An unreadable provider stream is different from a demonstrably incomplete ZIP. */
    internal fun backupStreamIsIntact(stream: InputStream): Boolean {
        val tracked = ZipTailInput(stream, ZIP_EOCD_SEARCH_BYTES)
        return try {
            var foundDatabase = false
            ZipInputStream(tracked).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!foundDatabase && !entry.isDirectory && entry.name.substringAfterLast('/') == ZIP_ENTRY_NAME) {
                        val header = ByteArray(SQLITE_MAGIC.size)
                        if (readFully(zip, header) != header.size || !header.contentEquals(SQLITE_MAGIC)) return false
                        foundDatabase = true
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            foundDatabase && hasEndOfCentralDirectory(tracked.finish())
        } catch (_: java.util.zip.ZipException) {
            false
        } catch (_: java.io.EOFException) {
            false
        }
    }

    /** First 4 bytes of every ZIP file: "PK\x03\x04". */
    private val ZIP_MAGIC: ByteArray =
        byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** Outcome of an [importFrom] call. On success the app must be restarted. */
    sealed interface ImportResult {
        /** The new database is in place; tell the user to relaunch NOOP. */
        data object NeedsRestart : ImportResult

        /** Import failed and the original database is untouched. */
        data class Failed(val message: String) : ImportResult

        /**
         * The restore stopped ONLY because an entry exceeds [MAX_BACKUP_SQLITE_BYTES]. Distinct from
         * [Failed] so the caller can offer to go ahead: the cap is a decompression guard against a
         * hostile archive, and a backup the user just picked out of their own files is a different
         * threat model than the one it defends against. (#1807)
         */
        data class TooLarge(val message: String, val limitBytes: Long) : ImportResult
    }

    /**
     * What an export produced. [overRestoreCeiling] is true when the database is past the ceiling the
     * RESTORE path enforces, so the caller can say so while the user still has their data — the
     * alternative is finding out during a restore, which is the one moment the original is gone.
     *
     * Measured on the DATABASE, not the finished archive: the archive is deflated and the cap counts the
     * DECOMPRESSED stream, so the zip's own size says nothing about whether it can be read back. That is
     * also why compressing harder cannot help anyone past it. (#1807)
     */
    data class ExportOutcome(val bytes: Long, val overRestoreCeiling: Boolean, val limitBytes: Long)

    /**
     * Export the live database to [uri] as a compressed `.noopbak` (single-entry ZIP).
     *
     * Runs `PRAGMA wal_checkpoint(TRUNCATE)` first so the db file is fully consistent.
     * The ZIP uses deflate compression; typical reduction is 80–90% vs the raw SQLite.
     * Throws on failure so the caller can surface the message in a toast/snackbar.
     */
    @Throws(IOException::class)
    @Synchronized
    fun exportTo(context: Context, uri: Uri, snapshotToken: String? = null): ExportOutcome {
        val appContext = context.applicationContext

        if (restartRequired || RestoreTransaction(appContext.getDatabasePath(WhoopDatabase.DB_NAME)).isPending) throw IOException("Restart NOOP to finish restoring before exporting.")

        // Fold the WAL back into the main file so the snapshot is complete.
        val db = WhoopDatabase.get(appContext)
        db.query("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                throw IOException("The database is busy. Try the backup again.")
            }
        }

        val dbFile = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        if (!dbFile.exists()) {
            throw IOException("No database to export yet.")
        }

        // #1014 defence-in-depth (export side): after the checkpoint the single file IS the whole
        // store — verify it BEFORE archiving. A backup of an already-corrupt database only fails
        // the import-side integrity gate months later, when the original data may be long gone;
        // failing loudly NOW is the honest move. Read-only probe, sits safely beside the open Room
        // connection (WAL allows concurrent readers). Twin of the Apple writeVerifiedBackupZip.
        sqliteQuickCheckFailure(dbFile)?.let { complaint ->
            throw IOException(
                "Couldn't export: the NOOP database failed its integrity check, so a backup of it " +
                    "would not restore. Export the WHOOP-format CSV instead to save what's still " +
                    "readable. (SQLite: ${readableComplaint(complaint)})"
            )
        }

        // #1000: the whitelisted profile/display settings ride along as a second entry so a restore
        // brings back weight/height/units, not just the rows. Null (nothing user-set) degrades to the
        // legacy single-entry ZIP. The DB entry stays FIRST — older importers stop at the first
        // `.sqlite` entry, so entry order is part of the cross-platform container contract.
        val settingsJson = BackupSettingsBridge.snapshotJson(appContext)
        // #1410: build provenance for this export — which build wrote the file.
        val manifestJson = BackupManifest.json(
            appVersion = com.noop.BuildConfig.VERSION_NAME,
            appBuild = com.noop.BuildConfig.VERSION_CODE.toString(),
            platform = "android",
            schemaVersion = WhoopDatabase.SCHEMA_VERSION,
            exportedAtMs = System.currentTimeMillis(),
        )

        val resolver = appContext.contentResolver
        val output = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Could not open the chosen file for writing.")
        // Assigned inside the transaction below, where the entry is actually written.
        var entryBytes = 0L
        output.use { out ->
            // #1014: copy the file while HOLDING Room's write transaction. In WAL mode the main
            // file is only rewritten by a checkpoint, and a checkpoint only runs on a commit — so
            // with the (single) write connection parked in an empty transaction for the duration
            // of the copy, no commit can land and the bytes we stream can't be torn mid-page by a
            // concurrent auto-checkpoint. Writers queue behind us and proceed after; readers are
            // unaffected. Anything committed after the checkpoint above lives in the new WAL and
            // is simply (consistently) absent from this snapshot, same as before.
            db.runInTransaction {
                ZipOutputStream(out).use { zip ->
                    snapshotToken?.let(zip::setComment)
                    zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                    // #1807: count what actually LANDS in the entry, rather than reading dbFile.length()
                    // afterwards. The copy runs inside this transaction against a snapshot; once it
                    // commits, Room can checkpoint again and the main file's length can move, so a later
                    // read is a different number from the one the restore path will meet. The cap is
                    // enforced on this entry, so this is the only measurement that answers the question.
                    dbFile.inputStream().use { input -> entryBytes = input.copyTo(zip) }
                    zip.closeEntry()
                    if (settingsJson != null) {
                        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                        zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                    zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
        // #1014 (write-side): the SOURCE was verified before archiving, but the PRODUCED file can still
        // be torn by a full disk, a dying card, or a provider that drops the tail, and such a .noopbak
        // "restores" into an empty store, caught only by the import-side quick_check much later, when
        // the original may be long gone. So check what was just written, here, while it can still be
        // called a failed export rather than a bad backup.
        //
        // This check existed but was wired only into the SCHEDULED sync path, so the manual Settings
        // export, the one taken deliberately before wiping a phone, never ran it. It belongs inside
        // exportTo the way Apple's lives inside writeVerifiedBackupZip, so that no caller has to
        // remember to ask.
        when (verifyWrittenBackup(appContext, uri)) {
            BackupWriteVerdict.INTACT -> Unit
            // Never leave a corrupt file behind masquerading as a good snapshot. Which of the two
            // outcomes happened changes what the reader should do, so say which.
            BackupWriteVerdict.TORN -> {
                val removed = runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    .getOrDefault(false)
                throw IOException(
                    if (removed) {
                        "Couldn't finish the backup: the file written was incomplete, so it was " +
                            "removed rather than left looking like a good backup. The destination may " +
                            "be out of space. Try again, or choose somewhere else."
                    } else {
                        "Couldn't finish the backup: the file written was incomplete, and it could " +
                            "not be removed either, so delete it yourself rather than trust it. The " +
                            "destination may be out of space. Try again, or choose somewhere else."
                    },
                )
            }
            // Failing to READ the file back is not evidence against it, so it is LEFT ALONE. Deleting
            // here would let a storage provider having a bad moment destroy a good backup.
            BackupWriteVerdict.UNVERIFIABLE -> throw IOException(
                "The backup was written, but couldn't be read back to check it, so it has been left " +
                    "in place rather than deleted on a guess. Open it before you rely on it, or " +
                    "export again somewhere else.",
            )
        }

        // #1807: the file is written and valid either way — this only reports whether restoring it will
        // need the user to confirm.
        return ExportOutcome(
            bytes = entryBytes,
            overRestoreCeiling = overRestoreCeiling(entryBytes),
            limitBytes = MAX_BACKUP_SQLITE_BYTES,
        )
    }

    @Volatile private var restartRequired = false

    /**
     * Replace the live database with the backup at [uri].
     *
     * Accepts both the new `.noopbak` (ZIP) format and legacy plain `.sqlite`/`.noopdb`
     * files so older backups keep working after the format upgrade.
     *
     * A failed preparation leaves Room open. Once publication commits, a cold start finishes
     * installation and rebinds all long-lived repository and BLE handles.
     */
    @Synchronized
    fun importFrom(context: Context, uri: Uri, allowOversize: Boolean = false): ImportResult {
        val appContext = context.applicationContext
        val transaction = RestoreTransaction(appContext.getDatabasePath(WhoopDatabase.DB_NAME))
        if (restartRequired || transaction.isPending) return ImportResult.NeedsRestart
        return try {
            transaction.prepare()
            stageAndInstall(appContext, transaction, uri, allowOversize)
        } catch (e: Exception) {
            if (restartRequired || transaction.isPending) ImportResult.NeedsRestart
            else ImportResult.Failed("Could not restore the backup: ${e.message}")
        } finally {
            // A process kill skips finally. The next prepare cleans only unpublished work.
            if (transaction.stage.exists()) transaction.stage.deleteRecursively()
        }
    }

    private val startupLock = Any()
    private var startupRecovered = false

    /** A provider can request Room before Application.onCreate. Run once from whichever arrives first. */
    internal fun ensureStartupRecovery(context: Context) = synchronized(startupLock) {
        if (!startupRecovered) {
            recoverAtStartup(context)
            startupRecovered = true
        }
    }

    internal fun recoverAtStartup(context: Context) {
        val tx = RestoreTransaction(context.getDatabasePath(WhoopDatabase.DB_NAME))
        if (tx.stage.exists()) tx.stage.deleteRecursively()
        finishPendingRestore(context)
        try {
            recoverLegacySnapshot(context, tx)
        } catch (e: Exception) {
            // Once published we MUST finish before Room opens. Before publication both
            // legacy files are still intact and normal corruption preservation can run.
            if (tx.isPending) throw e
            Log.e("DataBackup", "Could not recover legacy restore; preserving original snapshot", e)
        }
    }

    private fun recoverLegacySnapshot(context: Context, tx: RestoreTransaction) {
        val fallback = File(tx.live.path + ".import-bak")
        if (!fallback.isFile || tx.isPending) return
        // Earlier releases left no commit marker. A healthy live DB wins. Preserve the
        // fallback under a different name so it cannot replace a later, unrelated store.
        val hasHeader = tx.live.exists() && tx.live.inputStream().use {
            val header = ByteArray(SQLITE_MAGIC.size)
            readFully(it, header) == header.size && header.contentEquals(SQLITE_MAGIC)
        }
        val complaint = if (hasHeader) sqliteQuickCheckFailure(tx.live, requireCorruptionEvidence = true) else "missing SQLite header"
        if (complaint != null) {
            if (!isValidSqliteHeader(fallback) || sqliteQuickCheckFailure(fallback) != null) return
            tx.prepare()
            fallback.copyTo(tx.database)
            validateCandidate(context, tx)
            tx.publish(preservePrevious = true)
            finishPendingRestore(context)
        }
        val saved = File(fallback.path + ".preserved-" + java.util.UUID.randomUUID())
        java.nio.file.Files.move(fallback.toPath(), saved.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    internal fun finishPendingRestore(context: Context) {
        val transaction = RestoreTransaction(context.getDatabasePath(WhoopDatabase.DB_NAME))
        if (!transaction.isPending) return
        try {
            transaction.recover { file -> BackupSettingsBridge.apply(context, file.readText(), file.lastModified()) }
        } catch (error: IOException) {
            // Keep the payload for another launch, but let the user open their recovered history
            // if only the preference write failed (for example when storage is full).
            if (!transaction.databaseInstalled) throw error
            Log.e("DataBackup", "History restored; settings recovery will retry on the next launch", error)
            return
        }
        com.noop.ui.NoopPrefs.of(context).edit()
            .putLong("backup.lastRestoreAt", System.currentTimeMillis() / 1000L).apply()
    }

    private fun stageAndInstall(
        appContext: Context,
        transaction: RestoreTransaction,
        uri: Uri,
        allowOversize: Boolean,
    ): ImportResult {
        val resolver = appContext.contentResolver

        val tempSqlite = transaction.database
        val staged = resolver.openInputStream(uri)?.buffered()?.use { input ->
            val header = ByteArray(SQLITE_MAGIC.size)
            input.mark(header.size)
            readFully(input, header)
            input.reset()
            stageBackupSqlite(input, header, tempSqlite, transaction.settings, allowOversize)
        } ?: StageResult.CANNOT_OPEN

        when (staged) {
            StageResult.OK -> Unit
            StageResult.CANNOT_OPEN -> return ImportResult.Failed("Could not open the chosen file.")
            StageResult.NO_DB_IN_ZIP -> return ImportResult.Failed("The backup archive doesn't contain a database file.")
            StageResult.INCOMPLETE_ZIP -> return ImportResult.Failed("The backup archive is incomplete. Choose a completed backup.")
            StageResult.SETTINGS_TOO_LARGE -> return ImportResult.Failed("The settings entry in this backup is too large.")
            StageResult.ENTRY_TOO_LARGE -> return ImportResult.TooLarge(
                "The backup archive is too large to restore safely.", MAX_BACKUP_SQLITE_BYTES,
            )
            StageResult.NOT_A_BACKUP -> return ImportResult.Failed(
                "That file is not a NOOP backup - it doesn't look like a .noopbak archive or a SQLite database.",
            )
        }

        // 3. Validate the extracted file is a real SQLite database (magic-byte check).
        if (!isValidSqliteHeader(tempSqlite)) {
            return ImportResult.Failed("The backup archive doesn't contain a valid NOOP database.")
        }

        // 3b. Origin check (parity with the Apple side's GRDB-origin rejection). The SQLite magic
        //     passes for ANY SQLite file: a GRDB (Mac/iOS NOOP) backup or some other app's database
        //     would otherwise sail through and REPLACE the live Room store, stranding the user. Read
        //     the backup's table names READ-ONLY and reject anything that isn't a Room (this-app)
        //     backup but still holds real data. Empty/pre-migration files fall through to Room's
        //     open-time migrator, exactly as before.
        val backupTables = sqliteTableNames(tempSqlite)
        when (backupOriginOf(backupTables)) {
            BackupOrigin.MAC ->
                return ImportResult.Failed(
                    "This isn't a NOOP backup from this app. It looks like a backup from the Mac or " +
                        "iOS NOOP app (it carries that platform's migration bookkeeping). Restoring it here " +
                        "would strand your store. To move your history across platforms, export the " +
                        "WHOOP-format CSV on the other device (Settings → Export data) and import that here.",
                )
            BackupOrigin.UNKNOWN ->
                if (holdsData(backupTables)) {
                    return ImportResult.Failed(
                        "This isn't a NOOP backup from this app. It's missing the database bookkeeping a " +
                            "NOOP backup carries (it looks like another app's database). Restoring it would " +
                            "strand your store.",
                    )
                }
            BackupOrigin.ANDROID -> Unit // our own backup, proceed.
        }

        // 3c. #1014 defence-in-depth: gates 3 and 3b read only the FIRST pages of the file — the
        //     16-byte magic and sqlite_master both survive a backup that was truncated mid-upload or
        //     torn by a flaky drive/cloud client, and such a file then "restores" into a store that
        //     silently shows no data (the #1014 report; the #1000 settings code was exonerated, but
        //     the family needed armour). Run SQLite's own `PRAGMA quick_check` over the STAGED file,
        //     read-only, BEFORE the live DB is touched, and refuse the swap honestly. quick_check
        //     (not integrity_check) skips index-content verification so it stays fast on a 100 MB+
        //     library while still catching truncation and malformed pages. Twin of the Apple side's
        //     DatabaseIntegrity gate.
        sqliteQuickCheckFailure(tempSqlite)?.let { complaint ->
            return ImportResult.Failed(
                "This backup file is damaged, so nothing was restored. Your current data is " +
                    "untouched. Try an earlier backup file. (SQLite: ${readableComplaint(complaint)})"
            )
        }

        validateCandidate(appContext, transaction)
        return WhoopDatabase.withDatabaseSwapped(beforeClose = {
            transaction.publish()
            restartRequired = true
        }) {
            finishPendingRestore(appContext)
            ImportResult.NeedsRestart
        }
    }

    private fun validateCandidate(appContext: Context, transaction: RestoreTransaction) {
        // Exercise the same schema validation and migrations as a real launch, on the staged
        // file. A valid SQLite database from a newer or incompatible Room schema must fail here.
        WhoopDatabase.validateRestore(appContext, transaction.database)
        sqliteQuickCheckFailure(transaction.database)?.let { throw IOException(readableComplaint(it)) }
        for (suffix in listOf("-wal", "-journal", "-shm")) {
            val sidecar = File(transaction.database.path + suffix)
            if (suffix != "-shm" && sidecar.length() > 0L) throw IOException("Backup has uncheckpointed changes.")
            java.nio.file.Files.deleteIfExists(sidecar.toPath())
        }

        if (transaction.stage.listFiles()?.any { it != transaction.database && it != transaction.settings } != false) {
            throw IOException("Unexpected files appeared while validating the staged database.")
        }
    }

    // ── Container staging (pure file/stream layer, unit-tested under real file I/O) ──────

    /** Outcome of [stageBackupSqlite]: the SQLite was staged, or why it wasn't. */
    enum class StageResult { OK, CANNOT_OPEN, NO_DB_IN_ZIP, NOT_A_BACKUP, ENTRY_TOO_LARGE, SETTINGS_TOO_LARGE, INCOMPLETE_ZIP }

    /**
     * Stage the SQLite payload of a backup into [dest], from an already-opened [input] stream whose
     * first bytes are [header]. Handles both the `.noopbak` ZIP (extract the `.sqlite` entry) and a
     * legacy plain SQLite (copy through). Closes [input]. Context-free + stream-driven so the unit
     * tests drive it with real `java.util.zip` archives and real files, exercising the exact extraction
     * the live import uses (no behaviour fork between test and production).
     *
     * When [settingsDest] is given, a `settings.json` entry (#1000) is ALSO staged there if the ZIP
     * carries one (either platform's exporter may have written it, in either entry order). Its absence
     * is not an error — every pre-#1000 backup is a single-entry ZIP — and it never affects the
     * returned [StageResult]: the DB is the payload that decides success.
     *
     * NOTE this does NOT validate the staged file's SQLite header or origin; [importFrom] does that
     * next, on the staged file. Keeping staging and validation separate keeps each pure-testable.
     */
    fun stageBackupSqlite(
        input: java.io.InputStream?,
        header: ByteArray,
        dest: File,
        settingsDest: File? = null,
        /** #1807: lift the SQLite cap for a file the USER chose. See [ImportResult.TooLarge]. */
        allowOversize: Boolean = false,
    ): StageResult {
        if (input == null) return StageResult.CANNOT_OPEN
        input.use { stream ->
            when {
                header.startsWith(ZIP_MAGIC) -> {
                    var foundDb = false
                    var foundSettings = false
                    val tracked = ZipTailInput(stream, ZIP_EOCD_SEARCH_BYTES)
                    ZipInputStream(tracked).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            when {
                                !entry.isDirectory && !foundDb &&
                                    entry.name.substringAfterLast('/') == ZIP_ENTRY_NAME -> {
                                    FileOutputStream(dest).use { out ->
                                        if (!copyBounded(zip, out, sqliteCap(allowOversize))) {
                                            dest.delete()
                                            return StageResult.ENTRY_TOO_LARGE
                                        }
                                    }
                                    foundDb = true
                                }
                                !entry.isDirectory && !foundSettings && settingsDest != null &&
                                    entry.name.substringAfterLast('/') == SETTINGS_ENTRY_NAME -> {
                                    FileOutputStream(settingsDest).use { out ->
                                        if (!copyBounded(zip, out, MAX_BACKUP_SETTINGS_BYTES)) {
                                            settingsDest.delete()
                                            return StageResult.SETTINGS_TOO_LARGE
                                        }
                                    }
                                    foundSettings = true
                                }
                            }
                            // Everything we could want is staged - stop reading the archive.
                            if (foundDb && (settingsDest == null || foundSettings)) break
                            entry = zip.nextEntry
                        }
                    }
                    if (!foundDb) return StageResult.NO_DB_IN_ZIP
                    // ZipInputStream accepts complete local entries in an unfinished archive.
                    // Consume remaining compressed bytes, including the index, without inflating them.
                    return if (hasEndOfCentralDirectory(tracked.finish())) StageResult.OK else StageResult.INCOMPLETE_ZIP
                }
                header.startsWith(SQLITE_MAGIC) -> {
                    FileOutputStream(dest).use { out ->
                        if (!copyBounded(stream, out, sqliteCap(allowOversize))) {
                            dest.delete()
                            return StageResult.ENTRY_TOO_LARGE
                        }
                    }
                    return StageResult.OK
                }
                else -> return StageResult.NOT_A_BACKUP
            }
        }
    }

    private class ZipTailInput(input: InputStream, size: Int) : java.io.FilterInputStream(input) {
        private val ring = ByteArray(size)
        private var length = 0
        private var next = 0

        override fun read(bytes: ByteArray, off: Int, len: Int): Int {
            val n = super.read(bytes, off, len)
            if (n > 0) {
                val keep = minOf(n, ring.size)
                val from = off + n - keep
                val first = minOf(keep, ring.size - next)
                bytes.copyInto(ring, next, from, from + first)
                bytes.copyInto(ring, 0, from + first, from + keep)
                next = (next + keep) % ring.size
                length = minOf(ring.size, length + keep)
            }
            return n
        }

        override fun read(): Int = super.read().also {
            if (it >= 0) {
                ring[next] = it.toByte()
                next = (next + 1) % ring.size
                length = minOf(ring.size, length + 1)
            }
        }

        // The caller owns the original stream, including the bytes after the last local entry.
        override fun close() = Unit

        fun finish(): ByteArray {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (read(buffer, 0, buffer.size) != -1) { /* collect the raw tail */ }
            return if (length < ring.size) ring.copyOf(length)
                else ring.copyOfRange(next, length) + ring.copyOfRange(0, next)
        }
    }

    /**
     * Whether a database of [entryBytes] is past the ceiling the RESTORE path enforces (#1807).
     *
     * Strictly greater: a database exactly at the cap still restores, because `copyBounded` refuses only
     * when a write would take it OVER. Extracted so the boundary is testable — proving it end to end
     * would need a >2 GiB fixture, and a test that recomputes the comparison and asserts its own
     * arithmetic proves nothing.
     */
    internal fun overRestoreCeiling(entryBytes: Long): Boolean = entryBytes > MAX_BACKUP_SQLITE_BYTES

    /** The SQLite cap, lifted when the user has chosen to go ahead for a file they picked. (#1807) */
    internal fun sqliteCap(allowOversize: Boolean): Long =
        if (allowOversize) Long.MAX_VALUE else MAX_BACKUP_SQLITE_BYTES

    private fun copyBounded(input: java.io.InputStream, out: java.io.OutputStream, cap: Long): Boolean {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return true
            if (total + read > cap) return false
            out.write(buffer, 0, read)
            total += read
        }
    }

    /** Write [dbFile]'s bytes into a deflate ZIP at [dest] (the `.noopbak` container), DB entry first,
     *  plus the optional `settings.json` entry (#1000) when [settingsJson] is non-null. Context-free
     *  twin of the stream the live [exportTo] writes, so tests round-trip a real archive of either
     *  shape (legacy single-entry when [settingsJson] is null). */
    @Throws(IOException::class)
    fun writeBackupZip(dbFile: File, dest: File, settingsJson: String? = null) {
        FileOutputStream(dest).use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry(ZIP_ENTRY_NAME))
                dbFile.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                if (settingsJson != null) {
                    zip.putNextEntry(ZipEntry(SETTINGS_ENTRY_NAME))
                    zip.write(settingsJson.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }

    /** True when [file] begins with the SQLite 3 magic. Pure; used by [importFrom] and the tests. */
    fun isValidSqliteHeader(file: File): Boolean {
        val buf = ByteArray(SQLITE_MAGIC.size)
        return runCatching {
            val read = file.inputStream().use { readFully(it, buf) }
            read >= SQLITE_MAGIC.size && buf.contentEquals(SQLITE_MAGIC)
        }.getOrDefault(false)
    }

    /** First [n] bytes of [file] (or fewer at EOF): the header peek the import does on the raw file. */
    fun peekHeader(file: File, n: Int = 16): ByteArray {
        val buf = ByteArray(n)
        val read = runCatching { file.inputStream().use { readFully(it, buf) } }.getOrDefault(0)
        return buf.copyOf(read)
    }

    /** Read up to [buffer].size bytes from [input], looping over short reads. Returns bytes read. */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val n = input.read(buffer, offset, buffer.size - offset)
            if (n < 0) break
            offset += n
        }
        return offset
    }

    /** True when [this] begins with every byte in [prefix]. */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }

    // ── Origin validation (parity with the Apple GRDB-origin rejection) ─────────

    /** Which platform produced a NOOP backup, judged by its migrator's bookkeeping table. */
    enum class BackupOrigin { MAC, ANDROID, UNKNOWN }

    /**
     * Pure classification over a backup's `sqlite_master` table names: Room (this app) writes
     * `room_master_table`; GRDB (the Mac/iOS app) writes `grdb_migrations`. `.UNKNOWN` (neither, an
     * empty or pre-migration file) falls through to the normal import path, where Room's open-time
     * migrator decides. Mirrors the Apple `DataBackup.backupOrigin(of:)` so both platforms agree
     * byte-for-byte on what a foreign backup is.
     *
     * This platform's marker wins on the (degenerate) both-present case: restoring our own store here
     * is the less destructive read.
     */
    fun backupOriginOf(tableNames: Set<String>): BackupOrigin {
        if (tableNames.contains("room_master_table")) return BackupOrigin.ANDROID
        if (tableNames.contains("grdb_migrations")) return BackupOrigin.MAC
        // Older Room layouts didn't carry `room_master_table`; treat the Room/AndroidX pairing of
        // `android_metadata` + `sqlite_sequence` as one of ours too (mirrors the Apple side, which
        // reads that same duo as Android).
        if (tableNames.contains("android_metadata") && tableNames.contains("sqlite_sequence")) {
            return BackupOrigin.ANDROID
        }
        return BackupOrigin.UNKNOWN
    }

    /**
     * Does this backup actually hold app data (vs an empty/fresh file)? True when it carries any
     * user-content table beyond the SQLite/Android housekeeping ones. An `.UNKNOWN` file with no
     * content is harmless to restore; one WITH content but no recognised bookkeeping is some other
     * app's database and is rejected.
     */
    fun holdsData(tableNames: Set<String>): Boolean {
        val housekeeping = setOf("android_metadata", "sqlite_sequence", "room_master_table", "grdb_migrations")
        return tableNames.any { it !in housekeeping && !it.startsWith("sqlite_") }
    }

    /** Every table name in [file], opened READ-ONLY so the probed file is never mutated. Empty on
     *  failure. Carries [PRESERVE_ON_CORRUPTION] (#1014): without an explicit handler the framework
     *  default would DELETE the staged file when the open reports SQLITE_NOTADB/CORRUPT. */
    private fun sqliteTableNames(file: File): Set<String> {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.getOrNull() ?: return emptySet()
        return try {
            val names = LinkedHashSet<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(names::add)
            }
            names
        } catch (e: Exception) {
            emptySet()
        } finally {
            runCatching { db.close() }
        }
    }

    // ── Integrity gate (#1014 defence-in-depth; twin of the Apple DatabaseIntegrity) ─────

    /**
     * Pure classification of the rows `PRAGMA quick_check` returned: null = healthy (the single
     * canonical "ok" row), otherwise the first complaint row VERBATIM — never a fabricated summary.
     * An EMPTY result set is a failure too: quick_check always answers, so silence means the query
     * was swallowed and the file must not be trusted. Mirrors the Apple side's
     * `DatabaseIntegrity.verdict(fromRows:)` byte-for-byte — the same golden vectors are pinned in
     * [DataBackupIntegrityTest] here and `DatabaseIntegrityTests` there, so both platforms agree on
     * what "healthy" means. Pure + public so the plain-JVM test can drive it without Robolectric.
     */
    fun quickCheckVerdict(rows: List<String>): String? {
        if (rows.size == 1 && rows[0].equals("ok", ignoreCase = true)) return null
        return rows.firstOrNull { !it.equals("ok", ignoreCase = true) }
            ?: "quick_check returned no verdict"
    }

    /**
     * SQLite's banner line, which names the database being reported on and says nothing about what is
     * wrong with it. quick_check emits it ahead of the real diagnosis, joined into the SAME row.
     */
    private fun isBanner(line: String): Boolean =
        line.startsWith("*** in database") && line.endsWith("***")

    /**
     * The part of a [quickCheckVerdict] worth showing a person, as one line.
     *
     * The verdict is kept VERBATIM on purpose: it is the forensic value, and a reporter pasting it into
     * an issue should paste what SQLite actually said rather than a summary of it. (It reaches no log
     * today: the only copy is the one shown, which is why the dialog can copy it.) What SQLite says
     * begins with a banner and a newline:
     *
     *     *** in database main ***
     *     Page 5 is never used
     *
     * Rendered into a sentence, that spends the whole line on "*** in database main ***" and pushes the
     * only informative half ("Page 5 is never used") out of sight, which is exactly what a truncating
     * Toast showed. This drops the banner, joins what is left onto one line, and caps the length, so the
     * detail a person sees is the diagnosis.
     *
     * Falls back to the raw verdict, newlines flattened, whenever stripping would leave nothing: a
     * verdict made ENTIRELY of banner is still better shown than shown as an empty parenthesis.
     * Mirrors the Apple `DatabaseIntegrity.readableComplaint` on the same golden vectors.
     */
    fun readableComplaint(verdict: String, limit: Int = 140): String {
        val kept = verdict.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isBanner(it) }
        val joined = if (kept.isEmpty()) {
            verdict.replace('\n', ' ').trim()
        } else {
            kept.joinToString("; ")
        }
        val cap = limit.coerceAtLeast(1)
        return if (joined.length <= cap) joined else joined.take(cap - 1).trimEnd() + "\u2026"
    }

    /**
     * A [android.database.DatabaseErrorHandler] that closes the handle and PRESERVES the file. The
     * framework default ([android.database.DefaultDatabaseErrorHandler]) DELETES the file it was
     * probing on SQLITE_CORRUPT/SQLITE_NOTADB — every `openDatabase` overload without an explicit
     * handler inherits that. For the integrity probes below that would be catastrophic: the export
     * probe opens the LIVE database, so a corrupt store would be silently destroyed by the very
     * check meant to protect it (#1014). Also used by the origin probe for the same reason.
     */
    private val PRESERVE_ON_CORRUPTION = android.database.DatabaseErrorHandler { dbObj ->
        runCatching { dbObj.close() }
    }

    /**
     * Run `PRAGMA quick_check(1)` on [file]. Returns null when the file is healthy, otherwise a
     * short human-readable complaint for the caller's honest failure message. `quick_check(1)`
     * stops at the first error, so a damaged 100 MB library still answers quickly.
     *
     * Opens READ-ONLY first (never mutates the probed file; sits safely beside an open Room
     * connection — WAL allows concurrent readers). If the read-only open itself fails, falls back
     * to a read-write open: pre-3.22 SQLite (API 26/27, minSdk 26) cannot read-only-open a
     * WAL-header file without an initialized `-shm`, which is exactly what a checkpointed staged
     * backup looks like — refusing those would break valid restores on Android 8.x. Every probed
     * file is ours to touch (the staged temp copy, the just-swapped live file, or the live store
     * the export is about to archive), and a read-write open only performs standard SQLite
     * recovery, never a content change. Both opens carry [PRESERVE_ON_CORRUPTION] so no probe can
     * ever delete what it probes.
     */
    private fun sqliteQuickCheckFailure(file: File, requireCorruptionEvidence: Boolean = false): String? {
        val db = runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, PRESERVE_ON_CORRUPTION)
        }.recoverCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, PRESERVE_ON_CORRUPTION)
        }.getOrElse {
            if (requireCorruptionEvidence && it !is android.database.sqlite.SQLiteDatabaseCorruptException) {
                throw IOException("Could not verify the live database", it)
            }
            return "could not open the database: ${it.message}"
        }
        return try {
            val rows = ArrayList<String>()
            db.rawQuery("PRAGMA quick_check(1)", null).use { c ->
                while (c.moveToNext()) c.getString(0)?.let(rows::add)
            }
            quickCheckVerdict(rows)
        } catch (e: Exception) {
            // The query failed outright (SQLITE_NOTADB on garbage behind a valid magic header,
            // a malformed page 1, …). That IS the verdict: the file is not a usable database.
            if (requireCorruptionEvidence && e !is android.database.sqlite.SQLiteDatabaseCorruptException) throw e
            "quick_check failed: ${e.message}"
        } finally {
            runCatching { db.close() }
        }
    }
}

/**
 * #1014 defence-in-depth: a Room open-helper factory whose ONLY behavioural change is corruption
 * handling. The platform DEFAULT — androidx.sqlite routes SQLITE_CORRUPT to
 * [SupportSQLiteOpenHelper.Callback.onCorruption], whose base implementation mirrors Android's
 * `DefaultDatabaseErrorHandler` — silently DELETES the corrupt database file. For NOOP that means
 * permanently destroying already-acked strap history the strap will never re-send, without the user
 * ever seeing a byte of it. Confirmed absent in this app before #1014: nothing overrode
 * onCorruption, so the delete-on-corruption default applied.
 *
 * The factory wraps the stock [FrameworkSQLiteOpenHelperFactory] and delegates every lifecycle
 * callback (configure/create/migrate/open) to Room's real callback UNCHANGED, so migrations behave
 * exactly as before. Only `onCorruption` is replaced: it logs loudly, closes the handle, and moves
 * the corrupt file aside to a TIMESTAMPED `.corrupt.<epochMillis>` quarantine (best-effort), keeping
 * the newest [MAX_CORRUPT_QUARANTINES] and pruning older ones so a crash loop can't multiply 100 MB
 * files (#661). It never silently discards the newest corrupt copy — repeated corruption preserves
 * the latest (most recovery-worthy) data, not just the first. The trade-off is deliberate and matches
 * [WhoopDatabase]'s no-destructive-fallback doctrine: the app may then fail to open the store (the
 * user sees an error instead of a silently empty app), but the corrupt file survives for
 * backup/recovery instead of vanishing.
 *
 * `allowDataLossOnRecovery` is pinned FALSE for the same reason: androidx's recovery path deletes
 * the file when an open fails, which is exactly the destruction this factory exists to prevent.
 */
class CorruptionPreservingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val preserving = SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
            .name(configuration.name)
            .callback(PreservingCallback(configuration.callback))
            .noBackupDirectory(configuration.useNoBackupDirectory)
            .allowDataLossOnRecovery(false)
            .build()
        return delegate.create(preserving)
    }

    /** Delegates everything to Room's callback except the destructive corruption default. */
    private class PreservingCallback(
        private val roomCallback: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(roomCallback.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = roomCallback.onConfigure(db)
        override fun onCreate(db: SupportSQLiteDatabase) = roomCallback.onCreate(db)
        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onUpgrade(db, oldVersion, newVersion)
        override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
            roomCallback.onDowngrade(db, oldVersion, newVersion)
        override fun onOpen(db: SupportSQLiteDatabase) = roomCallback.onOpen(db)

        override fun onCorruption(db: SupportSQLiteDatabase) {
            // Do NOT call super — the base implementation DELETES the file outright (non-resendable strap
            // history gone without a trace). Instead QUARANTINE the corrupt file aside and let the store
            // recreate a fresh one, so the app OPENS instead of crash-looping on every launch. #1014
            // (wanxorg): 8.2.0 preserved the file but left it in place, so the very next open re-hit the
            // same corruption and the app crashed on startup until a reinstall. Moving the original out of
            // the way keeps the crash-recovery the platform default gives (next open finds no file → clean
            // rebuild) WITHOUT the silent data loss — the corrupt copy stays as `*.corrupt` for recovery.
            val path = runCatching { db.path }.getOrNull()
            Log.e(
                "WhoopDatabase",
                "SQLite reported corruption in $path — quarantining it to *.corrupt.<epoch> and recreating " +
                    "a fresh store. The corrupt copy is kept; restore from a backup to get your data back.",
            )
            runCatching { db.close() }
            if (path != null && path != ":memory:") {
                val original = File(path)
                if (original.exists()) {
                    // #661: quarantine under a TIMESTAMPED name and keep the newest few, instead of a
                    // single fixed `.corrupt` slot. The old single-slot logic kept the FIRST corrupt copy
                    // and deleted every later one — but a later corruption is a rebuilt store that has
                    // banked new non-resendable strap history since, so the copy it dropped was the most
                    // recovery-worthy. Keeping the newest N caps disk (a crash loop can't multiply files)
                    // AND preserves the latest data plus a couple of priors for diagnosing a recurring
                    // corruption path.
                    val dir = original.parentFile
                    val dbName = original.name
                    val existing = dir?.listFiles()?.map { it.name }
                        ?.filter {
                            it.startsWith("$dbName.corrupt.") &&
                                it.substringAfterLast(".corrupt.").toLongOrNull() != null
                        } ?: emptyList()
                    val plan = planCorruptQuarantine(dbName, existing, System.currentTimeMillis())
                    val preserved = File(dir, plan.quarantineName)
                    // Move (not copy) so the original is gone and the next open rebuilds clean; fall back
                    // to copy+delete if rename fails (e.g. across a storage boundary).
                    if (!runCatching { original.renameTo(preserved) }.getOrDefault(false)) {
                        // Cross-storage rename fallback. Copy best-effort (overwrite=true so a same-
                        // millisecond re-entry of the same corrupt lineage still lands), then drop the
                        // original EITHER WAY: leaving a corrupt file on the live path re-hits corruption
                        // and crash-loops the app on every launch (#1014). Preservation is best-effort.
                        runCatching { original.copyTo(preserved, overwrite = true) }
                        runCatching { original.delete() }
                    }
                    // Move the WAL/SHM sidecars ALONGSIDE the quarantine rather than deleting them: the WAL
                    // can hold the most recent un-checkpointed writes (the newest strap data). The move
                    // still clears the live path, so the fresh rebuild can't inherit a stale write-ahead log.
                    moveSidecarBesideQuarantine("$path-wal", "${preserved.path}-wal")
                    moveSidecarBesideQuarantine("$path-shm", "${preserved.path}-shm")
                    // Prune the oldest quarantines beyond the cap (+ their moved sidecars).
                    plan.evict.forEach { name ->
                        val stale = File(dir, name)
                        runCatching { stale.delete() }
                        runCatching { File("${stale.path}-wal").delete() }
                        runCatching { File("${stale.path}-shm").delete() }
                    }
                } else {
                    // No original to quarantine — clear any orphan sidecars so the fresh rebuild is clean.
                    runCatching { File("$path-wal").delete() }
                    runCatching { File("$path-shm").delete() }
                }
            }
        }
    }
}

/** #661: at most this many `.corrupt.<epoch>` quarantines survive (newest kept), bounding disk on a
 *  repeated-corruption crash loop while preserving the latest copies for recovery/diagnosis. */
internal const val MAX_CORRUPT_QUARANTINES = 3

internal data class CorruptQuarantinePlan(
    val quarantineName: String,
    val evict: List<String>,
)

/**
 * #661: name the new corrupt-DB quarantine (`<dbName>.corrupt.<epochMillis>`) and decide which OLD
 * quarantines to evict so at most [keep] survive (newest by embedded epoch). Pure / filesystem-free so
 * it is unit-tested directly. [existing] must already be filtered to `<dbName>.corrupt.<digits>` names.
 * The just-created quarantine is never evicted, even if [keep] is 0.
 */
internal fun planCorruptQuarantine(
    dbName: String,
    existing: List<String>,
    nowMillis: Long,
    keep: Int = MAX_CORRUPT_QUARANTINES,
): CorruptQuarantinePlan {
    val quarantineName = "$dbName.corrupt.$nowMillis"
    fun epochOf(name: String): Long = name.substringAfterLast(".corrupt.").toLongOrNull() ?: 0L
    val evict = (existing + quarantineName)
        .distinct()
        .sortedByDescending { epochOf(it) }
        .drop(keep.coerceAtLeast(0))
        .filter { it != quarantineName }
    return CorruptQuarantinePlan(quarantineName, evict)
}

/** Best-effort move of a WAL/SHM sidecar next to its quarantined DB (rename, else copy+delete). */
private fun moveSidecarBesideQuarantine(fromPath: String, toPath: String) {
    val src = File(fromPath)
    if (!src.exists()) return
    val dst = File(toPath)
    if (!runCatching { src.renameTo(dst) }.getOrDefault(false)) {
        // Best-effort preserve, then ALWAYS clear the live sidecar: a stale WAL left on the live path
        // can be applied to the freshly-rebuilt DB (WAL identity is salt-based only), re-injecting
        // corrupt pages. Keeping the rebuild clean wins over preserving an un-movable sidecar.
        runCatching { src.copyTo(dst, overwrite = true) }
        runCatching { src.delete() }
    }
}
