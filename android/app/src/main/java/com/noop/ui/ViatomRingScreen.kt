package com.noop.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.viatom.ViatomRingRecorder
import java.io.File

/**
 * SleepImage PO2 ring recorder — the Test Centre surface for [ViatomRingRecorder]. Experimental,
 * owner-credentials-only, on-device only: the recorder authenticates on the current A5 transport,
 * opens the vendor app's own notification stream and records locally. It runs an INDEPENDENT
 * scanner/GATT, so WHOOP keeps streaming beside it — and its own ViatomRingService foreground
 * slot keeps the process alive overnight even when no WHOOP link is up.
 */
@Composable
fun ViatomRingScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val recorder = remember { ViatomRingRecorder.shared(context) }
    val state by recorder.state.collectAsStateWithLifecycle()
    var confirmStart by remember { mutableStateOf(false) }
    var importFailed by remember { mutableStateOf(false) }

    // Staging/release builds aren't debuggable, so adb run-as can't push the credential file — the
    // picker is the install path there (validated + written by the recorder itself).
    val credLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importFailed = !recorder.installCredentials(uri)
    }

    // Hand the recorder the store once — it owns its own write scope so persistence survives
    // leaving this screen mid-recording (the whole point of an overnight capture).
    LaunchedEffect(Unit) { recorder.repository = vm.repo }

    // Raise the OS permission prompt before the recorder touches BLE — the shared helper asks for
    // the right set on this API level (granular Bluetooth on 12+, fine location below).
    val requestScanThenStart = rememberRequestScan { recorder.start() }

    val active = state.phase != ViatomRingRecorder.Phase.IDLE &&
        state.phase != ViatomRingRecorder.Phase.FAILED
    val credentialsReady = state.credentialsInstalled ||
        ViatomRingRecorder.credentialsInstalled(context)

    ScreenScaffold(
        title = stringResource(R.string.viatom_ring_title),
        subtitle = stringResource(R.string.viatom_ring_subtitle),
    ) {
        if (!credentialsReady) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.viatom_ring_credentials_missing),
                        style = NoopType.body, color = Palette.statusCritical,
                    )
                }
            }
        }

        // Import stays reachable while idle/failed even with a file installed — a file that parses
        // can still be the wrong pair, and non-debuggable users need a way to replace it without
        // clearing app data.
        if (!active) {
            NoopButton(
                text = stringResource(
                    if (credentialsReady) R.string.viatom_ring_replace_credentials
                    else R.string.viatom_ring_import_credentials,
                ),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { credLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
            )
            if (importFailed) {
                Text(
                    stringResource(R.string.viatom_ring_import_failed),
                    style = NoopType.caption, color = Palette.statusCritical,
                )
            }
        }

        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (state.phase) {
                        ViatomRingRecorder.Phase.IDLE -> stringResource(R.string.viatom_ring_phase_idle)
                        ViatomRingRecorder.Phase.SCANNING -> stringResource(R.string.viatom_ring_phase_scanning)
                        ViatomRingRecorder.Phase.CONNECTING -> stringResource(R.string.viatom_ring_phase_connecting)
                        ViatomRingRecorder.Phase.AUTHENTICATING -> stringResource(R.string.viatom_ring_phase_authenticating)
                        ViatomRingRecorder.Phase.RECORDING -> stringResource(R.string.viatom_ring_phase_recording)
                        ViatomRingRecorder.Phase.FAILED -> stringResource(R.string.viatom_ring_phase_failed)
                    },
                    style = NoopType.headline,
                    color = when (state.phase) {
                        ViatomRingRecorder.Phase.RECORDING -> Palette.statusPositive
                        ViatomRingRecorder.Phase.FAILED -> Palette.statusCritical
                        else -> Palette.textPrimary
                    },
                )
                state.deviceName?.let {
                    Text(it, style = NoopType.body, color = Palette.textSecondary)
                }
                state.message?.let {
                    Text(it, style = NoopType.body, color = Palette.textSecondary)
                }
                if (state.statusFrames + state.waveformFrames + state.accelFrames > 0) {
                    Text(
                        stringResource(
                            R.string.viatom_ring_counts,
                            state.statusFrames, state.waveformFrames, state.accelFrames,
                            state.gaps,
                        ),
                        style = NoopType.caption, color = Palette.textSecondary,
                    )
                }
                state.recordFile?.let {
                    Text(
                        stringResource(R.string.viatom_ring_file, it),
                        style = NoopType.caption, color = Palette.textSecondary,
                    )
                }
            }
        }

        state.reading?.let { reading ->
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        ViatomReadingValue(
                            label = stringResource(R.string.viatom_ring_spo2),
                            value = reading.spo2?.toString() ?: "—",
                            unit = "%",
                        )
                        ViatomReadingValue(
                            label = stringResource(R.string.viatom_ring_pulse),
                            value = reading.pulse?.toString() ?: "—",
                            unit = stringResource(R.string.viatom_ring_bpm_unit),
                        )
                        ViatomReadingValue(
                            label = stringResource(R.string.viatom_ring_battery),
                            value = reading.battery?.toString() ?: "—",
                            unit = "%",
                        )
                    }
                    Text(
                        if (reading.worn) stringResource(R.string.viatom_ring_measuring)
                        else stringResource(R.string.viatom_ring_not_measuring),
                        style = NoopType.caption,
                        color = if (reading.worn) Palette.statusPositive else Palette.textSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (active) {
            NoopButton(
                text = stringResource(R.string.viatom_ring_stop),
                kind = NoopButtonKind.Destructive,
                fullWidth = true,
                onClick = { recorder.stop() },
            )
        } else {
            NoopButton(
                text = stringResource(R.string.viatom_ring_start),
                fullWidth = true,
                onClick = { confirmStart = true },
            )
        }

        // The JSONL lives in app-private storage — invisible to adb on non-debuggable builds, so
        // the share sheet (via the existing FileProvider logs/ path) is how a recording leaves.
        if (!active && recorder.latestRecording() != null) {
            NoopButton(
                text = stringResource(R.string.viatom_ring_share_log),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = {
                    runCatching {
                        val src = recorder.latestRecording() ?: return@runCatching
                        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
                        val out = File(logsDir, src.name)
                        src.copyTo(out, overwrite = true)
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", out,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, null))
                    }
                },
            )
        }

        NoopCard {
            Text(
                stringResource(R.string.viatom_ring_note),
                style = NoopType.caption, color = Palette.textSecondary,
            )
        }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(stringResource(R.string.viatom_ring_confirm_title)) },
            text = { Text(stringResource(R.string.viatom_ring_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    requestScanThenStart()
                }) { Text(stringResource(R.string.viatom_ring_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) {
                    Text(stringResource(R.string.viatom_ring_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun ViatomReadingValue(label: String, value: String, unit: String) {
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(label, style = NoopType.caption, color = Palette.textSecondary)
        Text(
            value,
            style = NoopType.headline,
            color = Palette.textPrimary,
        )
        Text(unit, style = NoopType.caption, color = Palette.textSecondary)
    }
}
