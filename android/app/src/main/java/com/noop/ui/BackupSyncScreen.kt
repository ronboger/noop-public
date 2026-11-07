package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.noop.data.DataBackup
import com.noop.ingest.ValidationDrivePrefs
import com.noop.ingest.ValidationDriveUpload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backup & Sync. The whole-database path mirrors Apple's `BackupSyncView`: pick a folder, turn on
 * opt-in daily auto-backup, back up now, or restore. The separate validation-bundle card uses an
 * Android system-folder grant; the selected DocumentsProvider (for example Google Drive) owns the
 * cloud account and sync.
 *
 * Must-fixes baked in here:
 *  1. Restore lists the snapshots in the CHOSEN folder (newest-first) and lets the user pick one,
 *     rather than re-prompting with an unrelated document picker. A tightened file fallback exists
 *     only for folders we can't enumerate / legacy files.
 *  2. An explicit in-app confirm dialog fires before any destructive restore call.
 *  3. The file-fallback picker is tightened off the all-files wildcard to the backup MIME types, and
 *     the live [DataBackup.importFrom] now also rejects a foreign-but-valid SQLite (Mac/GRDB or other-app DB).
 */
@Composable
fun BackupSyncScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf(BackupSyncPrefs.treeUri(context)) }
    var auto by remember { mutableStateOf(BackupSyncPrefs.autoEnabled(context)) }
    var lastMs by remember { mutableStateOf(BackupSyncPrefs.lastBackupMs(context)) }
    var busy by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf(ValidationDrivePrefs.snapshot(context)) }
    // #1014 family: these failures carry a next step in their LAST clause, which is exactly what a
    // Toast drops. Held here and shown in a dialog, the same way Settings does.
    var backupFailure by remember { mutableStateOf<String?>(null) }
    // How many dated snapshots to keep; pruning deletes the oldest beyond this (BackupSync.snapshotsToPrune).
    var keep by remember { mutableStateOf(BackupSyncPrefs.keepCount(context)) }
    var keepMenu by remember { mutableStateOf(false) }
    // Time-of-day the daily backup runs (minutes since midnight); default 01:00, user-adjustable.
    var backupMinute by remember { mutableStateOf(BackupSyncPrefs.backupMinute(context)) }

    // Restore-from-folder sheet state: the listed snapshots, and the one pending confirmation.
    var snapshots by remember { mutableStateOf<List<BackupSync.SnapshotDoc>>(emptyList()) }
    var showSnapshotPicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Pair<String, Uri>?>(null) }

    // Runs the actual destructive restore for a chosen backup Uri, off the main thread.
    fun runRestore(uri: Uri) {
        busy = true
        scope.launch {
            // Clear in a `finally` so it clears on ANY exit — the twin of the Apple `defer` in
            // `BackupSyncView.runRestore`. All five controls in the screen body are gated on `!busy`
            // — folder picker, auto toggle, keep-count, Back up now, Restore — so a flag left set is a
            // dead end rather than one stuck button. (The restore sheet and confirm dialog are not
            // gated, but they are transient overlays, not the way back to a working screen.)
            //
            try {
                when (val r = restoreBackup(context, uri)) {
                    is DataBackup.ImportResult.NeedsRestart -> Unit
                    is DataBackup.ImportResult.Failed -> backupFailure = r.message
                    // #1807: recoverable, but not from here — this screen restores a folder snapshot
                    // directly and has no confirm step to hang the override on. Settings → Backup & restore
                    // → Import does, and shows the same sentence with a way through. Which is the reason
                    // it has to be READABLE here: the dialog is where the reader learns where to go.
                    is DataBackup.ImportResult.TooLarge -> backupFailure = r.message
                }
            } finally {
                busy = false
            }
        }
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        BackupSyncPrefs.setTreeUri(context, uri)
        treeUri = uri
        runCatching { BackupSync.reschedule(context) }
    }

    // Must-fix #1 + #3: the FILE fallback is tightened to the backup MIME types (was `*/*`). Used only
    // when the chosen folder holds no snapshots, or to restore a one-off file from elsewhere. The chosen
    // file still passes through importFrom's full validation (magic + Room/GRDB-origin) and the same
    // confirm dialog before it overwrites anything.
    val pickRestoreFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingRestore = "the selected file" to uri
    }

    val pickValidationFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val result = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, grant)
            val label = DocumentFile.fromTreeUri(context, uri)?.name
            ValidationDrivePrefs.setDestination(context, uri, label)
            ValidationDrivePrefs.setAutoEnabled(context, true)
            ValidationDriveUpload.reschedule(context)
        }
        drive = ValidationDrivePrefs.snapshot(context)
        Toast.makeText(
            context,
            if (result.isSuccess) {
                "Validation folder selected. The latest bundle is queued."
            } else {
                result.exceptionOrNull()?.message ?: "Couldn't use that folder."
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    LazyScreenScaffold(
        title = uiString(R.string.l10n_backup_sync_screen_backup_sync_81758ffa),
        subtitle = "Save a full backup to a folder you choose - point it at Google Drive / Dropbox for off-device sync.",
    ) {
        // 1 · Destination folder
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(uiString(R.string.l10n_backup_sync_screen_backup_folder_2a33df93), style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        treeUri?.let { "Saving to: ${folderLabel(it)}" }
                            ?: "No folder chosen yet. Pick one your cloud app already syncs, or any local folder.",
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    Text(
                        uiString(R.string.l10n_backup_sync_screen_tip_a_desktop_drive_dropbox_app_2eaff1e3),
                        style = NoopType.caption, color = Palette.accent,
                    )
                    // #644: these .noopbak snapshots are a plain, unencrypted ZIP — pointing this folder
                    // at a cloud sync app (per the tip above) also uploads that readable file there.
                    // Say so plainly next to the folder picker, before anyone turns auto-backup on.
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Palette.statusWarning,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            uiString(R.string.l10n_backup_sync_screen_these_backups_are_unencrypted_too_if_53e70fe8),
                            style = NoopType.caption, color = Palette.statusWarning,
                        )
                    }
                    NoopButton(
                        text = if (treeUri == null) "Choose folder" else "Change folder",
                        leadingIcon = Icons.Filled.FolderOpen,
                        kind = NoopButtonKind.Secondary,
                        enabled = !busy,
                        onClick = { pickFolder.launch(null) },
                    )
                }
            }
        }

        // 2 · Auto-backup + back up now
        item {
            NoopCard(padding = 20.dp, tint = if (auto && treeUri != null) Palette.accent else null) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(uiString(R.string.l10n_backup_sync_screen_daily_auto_backup_e5627357), style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                uiString(R.string.l10n_backup_sync_screen_writes_a_fresh_dated_backup_to_bd964fc5, keep),
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = auto,
                            enabled = treeUri != null && !busy,
                            onCheckedChange = {
                                auto = it
                                BackupSyncPrefs.setAutoEnabled(context, it)
                                runCatching { BackupSync.reschedule(context) }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.surfaceBase,
                                checkedTrackColor = Palette.accent,
                                uncheckedThumbColor = Palette.textSecondary,
                                uncheckedTrackColor = Palette.surfaceInset,
                                uncheckedBorderColor = Palette.hairline,
                            ),
                        )
                    }
                    // Retention: how many dated snapshots to keep. Wired to the existing setKeepCount; the
                    // next backup (auto or "Back up now") prunes the oldest beyond this count.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(uiString(R.string.l10n_backup_sync_screen_keep_last_snapshots_cd5c9ea9), style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                uiString(R.string.l10n_backup_sync_screen_older_backups_beyond_this_many_are_00b7daa6),
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Box {
                            TextButton(
                                enabled = treeUri != null && !busy,
                                onClick = { keepMenu = true },
                            ) {
                                Text(uiString(R.string.l10n_backup_sync_screen_keep_1addd33c, keep), style = NoopType.body, color = Palette.accent)
                            }
                            DropdownMenu(
                                expanded = keepMenu,
                                onDismissRequest = { keepMenu = false },
                            ) {
                                KEEP_OPTIONS.forEach { n ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                uiString(R.string.l10n_backup_sync_screen_n_9e03569f, n),
                                                style = NoopType.body,
                                                color = if (n == keep) Palette.accent else Palette.textPrimary,
                                            )
                                        },
                                        onClick = {
                                            keep = n
                                            BackupSyncPrefs.setKeepCount(context, n)
                                            keepMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // Backup time-of-day. Picking a new time re-anchors the schedule immediately
                    // (BackupSync.applyTimeChange); WorkManager isn't exact so it's best-effort.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(uiString(R.string.l10n_backup_sync_screen_backup_time_81557aaa), style = NoopType.body, color = Palette.textPrimary)
                            Text(
                                uiString(R.string.l10n_backup_sync_screen_roughly_when_the_daily_backup_runs_71de04e1),
                                style = NoopType.footnote, color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        TimeChip(
                            minutes = backupMinute,
                            accessibilityLabel = "Daily backup time",
                            onPicked = { m ->
                                backupMinute = m
                                BackupSyncPrefs.setBackupMinute(context, m)
                                runCatching { BackupSync.applyTimeChange(context) }
                            },
                        )
                    }
                    Text(
                        if (lastMs > 0L) {
                            "Last backup: ${DateUtils.getRelativeTimeSpanString(lastMs)}"
                        } else {
                            "No backup yet."
                        },
                        style = NoopType.caption, color = Palette.textTertiary,
                    )
                    // Auto is ON but the last SUCCESSFUL backup is stale — the daily catch-up isn't landing
                    // (a lost folder grant, or the app hasn't been opened in a while). backupNow only stamps
                    // lastMs on success, so this surfaces a silently-failing auto-backup instead of it being
                    // discovered only at restore.
                    if (auto && treeUri != null && lastMs > 0L &&
                        BackupSync.isBackupStale(lastMs, System.currentTimeMillis())
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = Palette.statusWarning,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                uiString(R.string.l10n_backup_sync_screen_auto_backup_hasn_t_run_in_e3cd484e),
                                style = NoopType.caption, color = Palette.statusWarning,
                                // weight(1f) so the long warning wraps within the Row instead of overflowing
                                // the card on a narrow screen (parity with the iOS row's fixedSize wrap).
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    NoopButton(
                        text = if (busy) "Working…" else "Back up now",
                        leadingIcon = Icons.Filled.CloudUpload,
                        fullWidth = true,
                        enabled = treeUri != null && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                // Clear in a `finally` so it clears on ANY exit — the twin of the Apple `defer` in
                                // `BackupSyncView.backupNow`. The flag now clears after the toast rather than before it,
                                // which is the point: the screen stays disabled until the work is genuinely finished.
                                try {
                                    val ok = withContext(Dispatchers.IO) { BackupSync.backupNow(context) }
                                    lastMs = BackupSyncPrefs.lastBackupMs(context)
                                    Toast.makeText(
                                        context,
                                        if (ok) {
                                            "Backed up to your folder."
                                        } else {
                                            "Backup failed - re-pick the folder and try again."
                                        },
                                        Toast.LENGTH_LONG,
                                    ).show()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }

        // 3 · Private Google Drive folder for the small paired-validation research bundle.
        item {
            NoopCard(
                padding = 20.dp,
                tint = if (drive.destinationUri != null && drive.autoEnabled) Palette.accent else null,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Sleep validation bundles",
                        style = NoopType.headline,
                        color = Palette.textPrimary,
                    )
                    Text(
                        "Choose a Google Drive folder once in Android's system picker. NOOP can write " +
                            "only to that selected folder; Google Drive handles account sign-in and sync. " +
                            "The ZIP contains raw overnight sensor samples.",
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                    Text(
                        drive.destinationLabel?.let { "Folder: $it" }
                            ?: "No validation folder chosen.",
                        style = NoopType.caption,
                        color = if (drive.destinationUri == null) {
                            Palette.statusCritical
                        } else {
                            Palette.accent
                        },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                "Automatic Drive upload",
                                style = NoopType.body,
                                color = Palette.textPrimary,
                            )
                            Text(
                                "Uploads once a day on unmetered Wi-Fi, at least 3 hours after you unlock your phone or interact with NOOP after your main sleep.",
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                        Spacer(Modifier.width(16.dp))
                        Switch(
                            checked = drive.autoEnabled,
                            enabled = drive.destinationUri != null,
                            onCheckedChange = { enabled ->
                                ValidationDrivePrefs.setAutoEnabled(context, enabled)
                                if (enabled) {
                                    ValidationDrivePrefs.markReady(context)
                                    runCatching { ValidationDriveUpload.reschedule(context) }
                                } else {
                                    runCatching { ValidationDriveUpload.cancel(context) }
                                }
                                drive = ValidationDrivePrefs.snapshot(context)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Palette.surfaceBase,
                                checkedTrackColor = Palette.accent,
                                uncheckedThumbColor = Palette.textSecondary,
                                uncheckedTrackColor = Palette.surfaceInset,
                                uncheckedBorderColor = Palette.hairline,
                            ),
                        )
                    }
                    Text(
                        validationDriveStatus(drive),
                        style = NoopType.caption,
                        color = if (drive.state == ValidationDrivePrefs.State.ERROR) {
                            Palette.statusCritical
                        } else {
                            Palette.textTertiary
                        },
                    )
                    if (drive.destinationUri == null) {
                        NoopButton(
                            text = "Choose Google Drive folder",
                            leadingIcon = Icons.Filled.FolderOpen,
                            fullWidth = true,
                            onClick = { pickValidationFolder.launch(null) },
                        )
                    } else {
                        NoopButton(
                            text = "Upload latest now",
                            leadingIcon = Icons.Filled.CloudUpload,
                            fullWidth = true,
                            onClick = {
                                ValidationDrivePrefs.setAutoEnabled(context, true)
                                ValidationDrivePrefs.markReady(context)
                                runCatching { ValidationDriveUpload.reschedule(context) }
                                runCatching {
                                    ValidationDriveUpload.enqueueNow(
                                        context,
                                        replaceExisting = true,
                                    )
                                }
                                drive = ValidationDrivePrefs.snapshot(context)
                                Toast.makeText(
                                    context,
                                    "Validation upload queued for unmetered Wi-Fi.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                        NoopButton(
                            text = "Change folder",
                            kind = NoopButtonKind.Secondary,
                            fullWidth = true,
                            leadingIcon = Icons.Filled.FolderOpen,
                            onClick = { pickValidationFolder.launch(null) },
                        )
                        NoopButton(
                            text = "Remove folder access",
                            kind = NoopButtonKind.Secondary,
                            fullWidth = true,
                            onClick = {
                                runCatching { ValidationDriveUpload.cancel(context) }
                                val uri = drive.destinationUri?.let(Uri::parse)
                                if (uri != null) {
                                    val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    runCatching {
                                        context.contentResolver
                                            .releasePersistableUriPermission(uri, grant)
                                    }
                                }
                                ValidationDrivePrefs.clear(context)
                                drive = ValidationDrivePrefs.snapshot(context)
                                Toast.makeText(
                                    context,
                                    "Validation folder access removed.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                    }
                }
            }
        }

        // 4 · Restore (must-fix #1: from the chosen folder, newest-first)
        item {
            NoopCard(padding = 20.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(uiString(R.string.l10n_backup_sync_screen_restore_3cbe6d6b), style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_backup_sync_screen_replace_this_device_s_data_with_b8679c51),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                    NoopButton(
                        text = uiString(R.string.l10n_backup_sync_screen_restore_from_a_backup_c28917c6),
                        leadingIcon = Icons.Filled.Restore,
                        kind = NoopButtonKind.Secondary,
                        enabled = !busy,
                        onClick = {
                            val tree = treeUri
                            if (tree == null) {
                                // No folder set: fall back to the tightened file picker.
                                pickRestoreFile.launch(RESTORE_MIME_TYPES)
                            } else {
                                scope.launch {
                                    val found = withContext(Dispatchers.IO) {
                                        runCatching { BackupSync.listSnapshotDocs(context, tree) }
                                            .getOrDefault(emptyList())
                                    }
                                    if (found.isEmpty()) {
                                        // Folder has no snapshots we wrote - point at a file instead.
                                        pickRestoreFile.launch(RESTORE_MIME_TYPES)
                                    } else {
                                        snapshots = found
                                        showSnapshotPicker = true
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Must-fix #1: the snapshot picker - the folder's backups, newest-first.
    if (showSnapshotPicker) {
        AlertDialog(
            onDismissRequest = { showSnapshotPicker = false },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text(uiString(R.string.l10n_backup_sync_screen_choose_a_backup_2fbfb0d6), style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        uiString(R.string.l10n_backup_sync_screen_newest_first_restoring_replaces_this_device_97dbcd8b),
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                    snapshots.forEach { snap ->
                        // Label + confirmation come from the resolved timeMs carried through from
                        // listSnapshotDocs, so a hand-named / date-only backup still shows a friendly date
                        // (its file-modification date) instead of the raw filename - parity with Swift. Only
                        // when the date is genuinely unknown (timeMs == 0) do we fall back to the name.
                        val whenLabel = if (snap.timeMs > 0L) {
                            DateUtils.getRelativeTimeSpanString(snap.timeMs).toString()
                        } else {
                            snap.name
                        }
                        Text(
                            text = whenLabel,
                            style = NoopType.body,
                            color = Palette.textPrimary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSnapshotPicker = false
                                    pendingRestore = if (snap.timeMs > 0L) {
                                        "the backup from $whenLabel"
                                    } else {
                                        snap.name
                                    } to snap.uri
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSnapshotPicker = false }) {
                    Text(uiString(R.string.l10n_backup_sync_screen_cancel_77dfd213), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }

    // Must-fix #2: explicit in-app confirm BEFORE any destructive restore call, on every restore path.
    pendingRestore?.let { (label, uri) ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text(uiString(R.string.l10n_backup_sync_screen_replace_all_current_data_e9244799), style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Text(
                    uiString(R.string.l10n_backup_sync_screen_replace_all_current_data_with_label_b7799a16, label),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    runRestore(uri)
                }) {
                    Text(uiString(R.string.l10n_backup_sync_screen_replace_a7cf7b25), style = NoopType.body, color = Palette.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(uiString(R.string.l10n_backup_sync_screen_cancel_77dfd213), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }

    backupFailure?.let { failure ->
        BackupFailureDialog(message = failure, onDismiss = { backupFailure = null })
    }
}

/**
 * Must-fix #3: the restore file fallback is tightened off the all-files wildcard to the backup
 * container MIME types: the .noopbak ZIP (octet-stream / zip) and a legacy plain SQLite. Anything that
 * slips through still meets importFrom's magic-byte + Room/GRDB-origin validation before it can touch
 * the live DB.
 */
/** Retention choices for the "Keep last snapshots" menu. Each snapshot is a dated .noopbak; the daily
 *  job keeps this many and prunes the oldest. Kept modest — a few days of rollback without hoarding. */
private val KEEP_OPTIONS = listOf(1, 3, 5, 7, 10, 14)

private val RESTORE_MIME_TYPES = arrayOf(
    "application/octet-stream",
    "application/zip",
    "application/x-sqlite3",
)

/** A short, human label for a SAF tree Uri (the part after the volume colon). */
private fun folderLabel(treeUri: Uri): String {
    val seg = treeUri.lastPathSegment ?: return "selected folder"
    return seg.substringAfterLast(':').ifBlank { seg }
}

private fun validationDriveStatus(snapshot: ValidationDrivePrefs.Snapshot): String = when {
    snapshot.destinationUri == null -> "Select a Google Drive folder to enable uploads."
    snapshot.state == ValidationDrivePrefs.State.UPLOADING ->
        "Building and copying the latest bundle…"
    snapshot.state == ValidationDrivePrefs.State.ERROR ->
        snapshot.lastError ?: "The latest Google Drive upload failed and will retry."
    snapshot.lastUploadMs > 0L -> {
        val whenLabel = DateUtils.getRelativeTimeSpanString(snapshot.lastUploadMs)
        "Last upload: $whenLabel · ${snapshot.lastFileName ?: "validation bundle"}"
    }
    snapshot.autoEnabled -> "Ready. Waiting to upload the first validation bundle."
    else -> "Ready. Automatic uploads are off."
}
