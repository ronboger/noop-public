package com.noop.ui

import androidx.compose.foundation.Canvas
import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.BuildConfig
import com.noop.analytics.Baselines
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.HRVReadiness
import com.noop.analytics.ReadinessTier
import com.noop.analytics.Whoop4BreathingSignalResearch
import com.noop.analytics.Whoop4DisturbanceResearch
import com.noop.ble.LiveState
import com.noop.ble.PuffinExperiment
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import com.noop.data.DailyMetric
import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.ingest.RawSensorExport
import com.noop.polar.PolarModel
import com.noop.testcentre.CaptureAccumulator
import com.noop.testcentre.CaptureKind
import com.noop.testcentre.DisplayPerformanceMonitor
import com.noop.testcentre.ReportReviewGate
import com.noop.testcentre.TestBundleAssembler
import com.noop.testcentre.TestCentre
import com.noop.testcentre.TestCentreLayout
import com.noop.testcentre.TestDomain
import com.noop.testcentre.TestMode
import com.noop.testcentre.TestModeRegistry
import com.noop.testcentre.TestReportFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.max
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings -> Test Centre (spec section 7), the Android twin of TestCentreView. Four sections: domain
 * test modes (rendered from the registry projection), diagnostic tools, export and auto-export, and
 * advanced/experimental. A NEW file because SettingsScreen.kt (132 KB) cannot grow. Section 1 renders
 * from TestCentreLayout.visibleModes; sections 2 to 4 re-host the same strap-log / recalibrate /
 * scheduled-export / experimental controls on the same bindings the Settings cards use. No em-dash.
 */
@Composable
fun TestCentreScreen(
    vm: AppViewModel,
    onOpenGroundTruthCollector: () -> Unit = {},
    onOpenViatomRing: () -> Unit = {},
) {
    val context = LocalContext.current
    val testCentre = remember { TestCentre.from(context) }
    // CAPTURE-D: a UI scope to emit the data-volume line off the toggle-on path (a store read, so it can't
    // run inline in the non-suspend onToggle).
    val scope = rememberCoroutineScope()

    // The strap model the Settings #22 gate reads, mirrored here so the 5/MG block shows for a 5/MG only.
    val live by vm.live.collectAsStateWithLifecycle()
    val publishedActiveStrapId by vm.activeStrapIdFlow.collectAsStateWithLifecycle()
    val activeStrapId = publishedActiveStrapId ?: vm.activeStrapId
    val selectedModelName = remember {
        NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
    }
    // Match the Settings `showFiveMGControls` gate exactly: pref OR a live-detected 5/MG this session, so a
    // 5/MG connected before its pref is written still sees the experimental block. (SettingsScreen.kt:346.)
    val is5MG = selectedModelName == WhoopModel.WHOOP5_MG.name || live.whoop5Detected
    val puffinExperiment = remember { PuffinExperiment.from(context) }
    var protocolProbes by remember { mutableStateOf(puffinExperiment.isEnabled) }
    var passiveRawCapture by remember { mutableStateOf(puffinExperiment.isCaptureEnabled) }
    var deepData by remember { mutableStateOf(puffinExperiment.isDeepDataEnabled) }
    var broadcastHr by remember { mutableStateOf(puffinExperiment.broadcastHr) }
    var explicitBond by remember { mutableStateOf(puffinExperiment.explicitBond) }
    var unbondedOffload by remember { mutableStateOf(puffinExperiment.unbondedOffload) }
    var clearStaleBond by remember { mutableStateOf(puffinExperiment.clearStaleBond) }
    var ecgRawData by remember { mutableStateOf(puffinExperiment.ecgRawData) }
    val r22DisableReport by vm.ble.r22DisableReport.collectAsStateWithLifecycle()
    val ecgGateReport by vm.ble.ecgRawDataGate.collectAsStateWithLifecycle()
    val ecgVariant by vm.ble.whoop5VariantFlow.collectAsStateWithLifecycle()
    var rawCaptureBusy by remember { mutableStateOf(false) }
    var rawAndLogBusy by remember { mutableStateOf(false) }

    // A report awaiting the mandatory review-before-share gate (spec section 12). Non-null shows the
    // review dialog; confirming runs TestReportFlow.run.
    var pendingReport by remember { mutableStateOf<PendingReport?>(null) }
    // WHOOP 4 breathing research card (fork): latest-session analysis + recent-night repeatability.
    var breathingResearch by remember { mutableStateOf<BreathingResearchReport?>(null) }
    var breathingResearchLoading by remember { mutableStateOf(false) }

    // #646/#651: TestReportFlow.run now awaits LogExport.exportBundle's off-main zip build instead of
    // blocking the caller, so a re-entrancy guard is needed on the dialog's Share button — without it a
    // fast double-tap before the dialog dismisses fires two zips / two chooser intents.
    var reportShareBusy by remember { mutableStateOf(false) }

    // The Display frame monitor follows the screen: if the Display mode was already on when the screen
    // appears, (re)start it; always tear it down when the screen leaves so no Choreographer callback
    // survives a navigation away. The mode flag stays on (the user's test is still active); the monitor
    // resumes next time this screen is shown. This keeps the perpetual-callback contract: a callback
    // exists only while the Test Centre is on screen with the Display mode on.
    DisposableEffect(Unit) {
        if (testCentre.active(TestDomain.DISPLAY)) {
            // CAPTURE-D (#797): wire the data-volume provider so the monitor can emit ONE `dataVolume` line
            // read STRAIGHT from the store (not the reactive caches), against the registry's active strap id.
            DisplayPerformanceMonitor.dataVolumeProvider = { vm.repo.dataVolumeSnapshot(vm.activeStrapId) }
            DisplayPerformanceMonitor.start(context) { line ->
                vm.ble.externalLog(line, TestDomain.DISPLAY)
            }
        }
        onDispose { DisplayPerformanceMonitor.stop() }
    }
    // CAPTURE-D: emit the data-volume line once when the Display mode is active on entry. Kept off the
    // (non-suspend) DisposableEffect: the read hits the store, so it runs from a coroutine.
    LaunchedEffect(Unit) {
        if (testCentre.active(TestDomain.DISPLAY)) DisplayPerformanceMonitor.emitDataVolume()
        if (!is5MG) {
            breathingResearchLoading = true
            breathingResearch = loadBreathingResearch(vm)
            breathingResearchLoading = false
        }
    }

    ScreenScaffold(
        title = uiString(R.string.l10n_test_centre_screen_test_centre_37b36828),
        subtitle = "Turn on a test for the thing that's wrong, wear the strap, then tap Report.",
    ) {
        // --- Section 1: Domain test modes ---
        SettingsSectionTC(
            icon = Icons.Filled.BugReport,
            title = uiString(R.string.l10n_test_centre_screen_test_modes_e21f1d3c),
            blurb = "Each test logs extra detail for one part of the app while you wear the strap, then bundles it for a bug report.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TestCentreLayout.visibleModes(is5MG).forEach { mode ->
                    TestModeRow(
                        mode = mode,
                        active = testCentre.active(mode.domain),
                        startedAtSeconds = testCentre.startedAt(mode.domain),
                        live = live,
                        activeStrapId = activeStrapId,
                        is5MG = is5MG,
                        vm = vm,
                        onToggle = { on ->
                            if (on) testCentre.activate(mode.domain) else testCentre.deactivate(mode.domain)
                            // Display & Performance owns a live frame monitor. It must run ONLY while the
                            // mode is on: start it on toggle-on (wiring its sink to the redacting DISPLAY
                            // log), tear it down on toggle-off so no Choreographer callback survives.
                            // Zero-cost when off.
                            if (mode.domain == TestDomain.DISPLAY) {
                                if (on) {
                                    // CAPTURE-D (#797): wire the data-volume provider (store-read, active id),
                                    // start the monitor, then emit the upfront dataVolume line off a scope.
                                    DisplayPerformanceMonitor.dataVolumeProvider =
                                        { vm.repo.dataVolumeSnapshot(vm.activeStrapId) }
                                    DisplayPerformanceMonitor.start(context) { line ->
                                        vm.ble.externalLog(line, TestDomain.DISPLAY)
                                    }
                                    scope.launch { DisplayPerformanceMonitor.emitDataVolume() }
                                } else {
                                    DisplayPerformanceMonitor.stop()
                                }
                            }
                        },
                        onReport = {
                            // Launched (#1002): buildPending is now suspend (storage probe reads the store).
                            scope.launch { pendingReport = buildPending(context, mode, vm.ble.exportLogText(), vm) }
                        },
                    )
                }
            }
        }

        // --- Section 2: Diagnostic tools ---
        DiagnosticToolsCard(vm)

        SettingsSectionTC(
            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
            title = stringResource(R.string.ground_truth_title),
            blurb = stringResource(R.string.ground_truth_test_centre_desc),
        ) {
            NoopButton(
                text = stringResource(R.string.ground_truth_open),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onOpenGroundTruthCollector,
            )
        }

        SettingsSectionTC(
            icon = Icons.Filled.Sensors,
            title = stringResource(R.string.viatom_ring_title),
            blurb = stringResource(R.string.viatom_ring_test_centre_blurb),
        ) {
            NoopButton(
                text = stringResource(R.string.viatom_ring_open),
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = onOpenViatomRing,
            )
        }

        if (is5MG) {
            SettingsSectionTC(
                icon = Icons.Filled.Science,
                title = stringResource(R.string.raw_diag_title),
                blurb = "Developer tools for protocol research. These are separate from the bounded Raw Data Collector above.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_protocol_probes),
                        detail = stringResource(R.string.raw_diag_protocol_probes_detail),
                        checked = protocolProbes,
                        onCheckedChange = {
                            protocolProbes = it
                            puffinExperiment.isEnabled = it
                        },
                    )
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_broadcast_hr),
                        detail = "Writes the reversible WHOOP 5/MG advertising flag for Garmin, Zwift, and gym equipment.",
                        checked = broadcastHr,
                        onCheckedChange = {
                            broadcastHr = it
                            puffinExperiment.broadcastHr = it
                            vm.ble.setBroadcastHr(it)
                        },
                    )
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_pair),
                        detail = "Experimental explicit Android bonding. Normal 5/MG support does not " +
                            "require this switch. A strap that refuses pairing defers its handshake for one " +
                            "connect while this is on, so leave it off unless you are testing #1635.",
                        checked = explicitBond,
                        onCheckedChange = {
                            explicitBond = it
                            puffinExperiment.explicitBond = it
                        },
                    )
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_unbonded_offload),
                        detail = "Subscribes the puffin notify characteristics on a link with no " +
                            "CLIENT_HELLO, then asks the strap a read-only GET_CLOCK. If it answers, the " +
                            "clock is set and history is requested. Once per link, and never again on a " +
                            "strap that refuses. Takes effect on the next connect, not this one. " +
                            "Leave it off unless you are testing #1635.",
                        checked = unbondedOffload,
                        onCheckedChange = {
                            unbondedOffload = it
                            puffinExperiment.unbondedOffload = it
                        },
                    )
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_clear_stale_bond),
                        detail = "When a bonded fast-path connect keeps dropping before it reaches a " +
                            "session, the phone is holding a pairing the strap no longer honours. NOOP " +
                            "already shows the forget-and-re-pair guide at two failures; with this on it " +
                            "does that step for you at five, once, and only until the strap bonds again. " +
                            "It cannot make a strap that refuses pairing pair. Leave it off unless you " +
                            "are testing #1635.",
                        checked = clearStaleBond,
                        onCheckedChange = {
                            clearStaleBond = it
                            puffinExperiment.clearStaleBond = it
                        },
                    )
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_r22),
                        detail = "Accepted writes have not been shown to enable a separate live stream. Not required for normal sync or raw capture.",
                        checked = deepData,
                        onCheckedChange = {
                            deepData = it
                            puffinExperiment.isDeepDataEnabled = it
                        },
                    )
                    if (deepData) {
                        NoopButton(
                            text = stringResource(R.string.raw_diag_r22_enable),
                            kind = NoopButtonKind.Secondary,
                            fullWidth = true,
                            enabled = live.encryptedBond && live.worn,
                            onClick = { vm.ble.enableWhoop5DeepData() },
                        )
                    }
                    NoopButton(
                        text = stringResource(R.string.raw_diag_r22_clear),
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        enabled = live.encryptedBond && r22DisableReport != WhoopBleClient.WAITING_DEVICE_CONFIG_PROBE,
                        onClick = { vm.ble.disableWhoop5DeepData() },
                    )
                    r22DisableReport?.let {
                        Text(it, style = NoopType.caption, color = Palette.textSecondary)
                    }
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_ecg),
                        detail = "MG-only protocol research. This is instrumentation, not a medical ECG feature.",
                        checked = ecgRawData,
                        onCheckedChange = {
                            ecgRawData = it
                            puffinExperiment.ecgRawData = it
                        },
                    )
                    if (ecgRawData) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NoopButton(
                                text = stringResource(R.string.raw_diag_ecg_on),
                                kind = NoopButtonKind.Secondary,
                                enabled = live.bonded && ecgVariant.isMG,
                                onClick = { vm.ble.setEcgRawDataGate(true) },
                            )
                            NoopButton(
                                text = stringResource(R.string.raw_diag_ecg_off),
                                kind = NoopButtonKind.Secondary,
                                enabled = live.bonded && ecgVariant.isMG,
                                onClick = { vm.ble.setEcgRawDataGate(false) },
                            )
                        }
                        ecgGateReport?.let {
                            Text(it.summary, style = NoopType.caption, color = Palette.textSecondary)
                        }
                    }
                    DeveloperToggleRow(
                        title = stringResource(R.string.raw_diag_passive),
                        detail = "Records frames that already arrive during history sync. It does not start IMU or any other sensor and may create large files.",
                        checked = passiveRawCapture,
                        onCheckedChange = {
                            passiveRawCapture = it
                            puffinExperiment.isCaptureEnabled = it
                        },
                    )
                    NoopButton(
                        text = stringResource(R.string.raw_diag_share),
                        leadingIcon = Icons.Filled.Upload,
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        enabled = !rawCaptureBusy,
                        onClick = {
                            rawCaptureBusy = true
                            scope.launch {
                                try {
                                    LogExport.shareWhoop5Capture(context, live.whoop5Detected, live.encryptedBond)
                                } finally {
                                    rawCaptureBusy = false
                                }
                            }
                        },
                    )
                    NoopButton(
                        text = stringResource(R.string.raw_diag_export_log),
                        leadingIcon = Icons.Filled.Upload,
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        enabled = !rawAndLogBusy,
                        onClick = {
                            rawAndLogBusy = true
                            scope.launch {
                                try {
                                    LogExport.shareRawAndLog(
                                        context, vm.ble.exportLogText(), live.whoop5Detected, live.encryptedBond,
                                    )
                                } finally {
                                    rawAndLogBusy = false
                                }
                            }
                        },
                    )
                }
            }
        }

        // --- Section 3: Export and auto-export ---
        ExportCard(
            vm = vm,
            onReport = {
                // Launched (#1002): buildPending is now suspend (storage probe reads the store).
                scope.launch { pendingReport = buildPending(context, MASTER_REPORT_MODE, vm.ble.exportLogText(), vm) }
            },
        )

        // --- Section 4: Experimental algorithms ---
        ExperimentalAlgorithmsCard(vm)
        // WHOOP 4 breathing research + versioned validation bundle (v6 ZIP with fail-soft v7
        // supplements, accel-bd-v0 index). Research-only; the Drive folder upload lives in Backup & Sync.
        if (!is5MG) {
            BreathingResearchCard(
                result = breathingResearch,
                loading = breathingResearchLoading,
                onRefresh = {
                    scope.launch {
                        breathingResearchLoading = true
                        breathingResearch = loadBreathingResearch(vm)
                        breathingResearchLoading = false
                    }
                },
                onExport = {
                    scope.launch {
                        RawSensorExport.exportWhoop4Research(context, vm.repo, vm.activeStrapId)
                    }
                },
            )
        }
    }

    pendingReport?.let { p ->
        ReportReviewDialog(
            previewText = p.gate.previewText,
            modeInactive = p.modeInactive,
            onCancel = { pendingReport = null },
            onShare = {
                // Guard against a fast double-tap firing TestReportFlow.run twice before the dialog
                // dismisses (#646/#651 — run() now awaits the off-main zip build instead of blocking).
                if (!reportShareBusy) {
                    reportShareBusy = true
                    p.gate.confirm()
                    scope.launch {
                        // try/finally: the flag must clear on any exit, not just the happy path (#961 follow-up).
                        try {
                            TestReportFlow.run(
                                context = context,
                                profile = p.profile,
                                title = p.title,
                                version = BuildConfig.VERSION_NAME,
                                platform = "Android",
                                osVersion = android.os.Build.VERSION.RELEASE ?: "?",
                                gate = p.gate,
                                entries = p.entries,
                            )
                        } finally {
                            reportShareBusy = false
                        }
                    }
                    pendingReport = null
                }
            },
        )
    }
}

@Composable
private fun DeveloperToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary)
            Text(detail, style = NoopType.caption, color = Palette.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private data class BreathingResearchReport(
    val rhythm: Whoop4BreathingSignalResearch.Result,
    val disturbances: Whoop4DisturbanceResearch.Result,
    val recentNights: List<BreathingNightSummary>,
    /** accel-bd-v0 accelerometer-only research index for the latest session; null when unavailable. */
    val accel: com.noop.ingest.AccelBreathingDisturbanceExport.Output? = null,
)

private data class BreathingNightSummary(
    val endTs: Long,
    val status: Whoop4DisturbanceResearch.Status,
    val candidates: Int,
    val evidenceSupported: Int,
    val scoredSleepRate: Double?,
    val rhythmicScoredSleepRate: Double?,
    val allValidRate: Double?,
)

private data class BreathingSessionInput(
    val endTs: Long,
    val rows: List<com.noop.data.Whoop4ResearchSampleEntity>,
    val heartRate: List<com.noop.data.HrSample>,
    val rrIntervals: List<com.noop.data.RrInterval>,
    val sleepIntervals: List<LongRange>,
    val startTs: Long,
)

private suspend fun loadBreathingResearch(vm: AppViewModel): BreathingResearchReport {
    val now = System.currentTimeMillis() / 1000L
    val sessions = vm.repo.sleepSessionsMerged(vm.activeStrapId, now - 14 * 86_400, now + 86_400)
        .sortedByDescending { it.endTs }
        .take(5)
    if (sessions.isEmpty()) {
        return BreathingResearchReport(
            rhythm = Whoop4BreathingSignalResearch.analyze(emptyList()),
            disturbances = Whoop4DisturbanceResearch.analyze(emptyList()),
            recentNights = emptyList(),
        )
    }
    val inputs = sessions.map { session ->
        val from = session.effectiveStartTs
        val to = session.endTs
        BreathingSessionInput(
            endTs = to,
            rows = vm.repo.whoop4ResearchSamples(vm.activeStrapId, from, to, 100_000),
            heartRate = vm.repo.hrSamplesForDevice(vm.activeStrapId, from, to, 100_000),
            rrIntervals = vm.repo.rrIntervalsForDevice(vm.activeStrapId, from, to, 100_000),
            sleepIntervals = Whoop4DisturbanceResearch.scoredSleepIntervals(session.stagesJSON),
            startTs = from,
        )
    }
    return withContext(Dispatchers.Default) {
        val analyses = inputs.map { input ->
            Whoop4DisturbanceResearch.analyze(
                input = input.rows,
                heartRate = input.heartRate,
                rrIntervals = input.rrIntervals,
                scoredSleepIntervals = input.sleepIntervals,
                sessionStartTs = input.startTs,
                sessionEndTs = input.endTs,
            )
        }
        val latest = inputs.first()
        val latestAnalysis = analyses.first()
        val accel = runCatching {
            com.noop.ingest.AccelBreathingDisturbanceExport.compute(latest.rows, latest.sleepIntervals)
        }.getOrNull()
        BreathingResearchReport(
            rhythm = Whoop4BreathingSignalResearch.analyze(latest.rows),
            disturbances = latestAnalysis,
            accel = accel,
            recentNights = inputs.zip(analyses).map { (input, analysis) ->
                val scoredSleepRate = analysis.scoredSleepRate?.takeIf { it.ratePerHour != null }
                BreathingNightSummary(
                    endTs = input.endTs,
                    status = analysis.status,
                    candidates = scoredSleepRate?.candidateEvents
                        ?: analysis.allValidRate.candidateEvents,
                    evidenceSupported = analysis.highConfidenceEvents,
                    scoredSleepRate = scoredSleepRate?.ratePerHour,
                    rhythmicScoredSleepRate = analysis.rhythmicScoredSleepRate?.ratePerHour,
                    allValidRate = analysis.allValidRate.ratePerHour,
                )
            },
        )
    }
}

@Composable
private fun BreathingResearchCard(
    result: BreathingResearchReport?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onExport: () -> Unit,
) {
    SettingsSectionTC(
        icon = Icons.Filled.Info,
        title = "Breathing signal · research",
        blurb = "Two explicitly separate analyzers: a motion capability scan and a versioned event-candidate experiment. Neither output is AHI/RDI or a diagnosis.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) {
                Text("Analyzing latest sleep...", style = NoopType.footnote, color = Palette.textSecondary)
            } else if (result != null) {
                val rhythm = result.rhythm
                val disturbances = result.disturbances
                Text("Motion capability scan", style = NoopType.body, color = Palette.textPrimary)
                val status = when (rhythm.status) {
                    Whoop4BreathingSignalResearch.Status.UNAVAILABLE -> "No data"
                    Whoop4BreathingSignalResearch.Status.INSUFFICIENT_DATA -> "Need more verified on-wrist data"
                    Whoop4BreathingSignalResearch.Status.INCONCLUSIVE -> "Signal inconclusive"
                    Whoop4BreathingSignalResearch.Status.BREATHING_LIKE_MOTION_OBSERVED ->
                        "Breathing-like rhythm observed"
                }
                ResearchValueRow("Status", status)
                ResearchValueRow("Layout", rhythm.capability.layoutVersions.joinToString())
                ResearchValueRow("Time coverage", percent(rhythm.capability.timeCoverage))
                ResearchValueRow("Motion candidate 1", percent(rhythm.capability.primaryVectorCoverage))
                ResearchValueRow("Motion candidate 2", percent(rhythm.capability.secondaryVectorCoverage))
                ResearchValueRow("Contact field", percent(rhythm.capability.contactCoverage))
                ResearchValueRow("Quality field", percent(rhythm.capability.signalQualityCoverage))
                rhythm.capability.duplicateVectorFraction?.let {
                    ResearchValueRow("Vectors identical", percent(it))
                }
                ResearchValueRow("Usable windows", "${rhythm.usableWindows} / ${rhythm.evaluatedWindows}")
                ResearchValueRow("Rhythmic windows", percent(rhythm.rhythmicWindowFraction))
                rhythm.medianBandPowerFraction?.let {
                    ResearchValueRow("Breathing-band power", percent(it))
                }
                rhythm.medianRhythmConcentration?.let {
                    ResearchValueRow("Rhythm concentration", percent(it))
                }
                rhythm.medianDominantCyclesPerMinute?.let {
                    ResearchValueRow("Dominant motion rhythm", String.format(Locale.US, "%.1f cycles/min", it))
                }
                rhythm.largeVectorChangeFraction?.let {
                    ResearchValueRow("Large vector changes", percent(it))
                }
                ResearchValueRow("Optical fields captured", if (rhythm.capability.hasOpticalContext) "Present" else "Missing")
                Text(rhythm.reason, style = NoopType.footnote, color = Palette.textTertiary)

                Text("Event candidates · ${disturbances.provenance.algorithmVersion}", style = NoopType.body, color = Palette.textPrimary)
                val candidateStatus = when (disturbances.status) {
                    Whoop4DisturbanceResearch.Status.UNAVAILABLE -> "No session data"
                    Whoop4DisturbanceResearch.Status.INSUFFICIENT_SIGNAL -> "Insufficient valid signal"
                    Whoop4DisturbanceResearch.Status.LOW_CONFIDENCE -> "Candidates detected · low confidence"
                    Whoop4DisturbanceResearch.Status.EXPLORATORY_ESTIMATE -> "Candidates detected · exploratory"
                }
                ResearchValueRow("Candidate-detection status", candidateStatus)
                disturbances.primarySignal?.let { ResearchValueRow("Selected channel", it) }
                ResearchValueRow("Input rows", disturbances.provenance.inputRows.toString())
                ResearchValueRow("Input fingerprint", disturbances.provenance.inputFingerprint.take(12))
                ResearchValueRow("Session duration", hours(disturbances.provenance.sessionHours))
                disturbances.provenance.scoredSleepHours?.let {
                    ResearchValueRow("Timestamped scored sleep", hours(it))
                }
                ResearchValueRow("Sleep mask", disturbances.provenance.sleepMaskSource)
                ResearchValueRow("Motion source", "${disturbances.provenance.motionSourceHz} Hz historical DSP")
                ResearchValueRow(
                    "Higher-rate motion",
                    if (disturbances.provenance.higherRateMotionAvailable) "Available" else "Not available",
                )
                result.accel?.result?.let { accel ->
                    ResearchValueRow(
                        "Accelerometer index · ${accel.algorithmVersion}",
                        accel.indexPerAsleepHour?.let { String.format(Locale.US, "%.1f epochs / asleep h", it) }
                            ?: "no asleep time",
                    )
                    accel.calibratedSahi3Like?.let {
                        ResearchValueRow(
                            "Accelerometer index · comparator scale",
                            String.format(Locale.US, "≈ %.1f / h · one-person fit, not AHI", it),
                        )
                    }
                    accel.burstsPerAsleepHour?.let {
                        ResearchValueRow("Accelerometer still→burst rate", String.format(Locale.US, "%.1f / asleep h", it))
                    }
                    result.accel.grid?.let { grid ->
                        ResearchValueRow(
                            "Accelerometer grid quality",
                            "${grid.missingSeconds} missing · ${grid.duplicateSeconds} duplicate · ${grid.nonFiniteSamples} non-finite s",
                        )
                    }
                }
                rateRow("All-valid monitoring rate", disturbances.allValidRate)
                rateRow("Rhythmic-valid monitoring rate", disturbances.rhythmicValidRate)
                disturbances.scoredSleepRate?.let { rateRow("Scored-sleep rate", it) }
                disturbances.rhythmicScoredSleepRate?.let { rateRow("Rhythmic scored-sleep rate", it) }
                if (disturbances.provenance.sessionHours > 0.0) {
                    ResearchValueRow(
                        "All-valid share of session",
                        percent(disturbances.allValidRate.denominatorHours /
                            disturbances.provenance.sessionHours),
                    )
                }
                val scoredHours = disturbances.provenance.scoredSleepHours
                val scoredRate = disturbances.scoredSleepRate
                if (scoredHours != null && scoredHours > 0.0 && scoredRate != null) {
                    ResearchValueRow(
                        "Valid share of scored sleep",
                        percent(scoredRate.denominatorHours / scoredHours),
                    )
                    ResearchValueRow(
                        "Observed-candidate floor",
                        String.format(
                            Locale.US,
                            "%.1f/h · %d events / %.2f h total scored sleep",
                            scoredRate.candidateEvents / scoredHours,
                            scoredRate.candidateEvents,
                            scoredHours,
                        ),
                    )
                }
                ResearchValueRow("Total event candidates", disturbances.events.size.toString())
                ResearchValueRow(
                    "Evidence-supported candidates",
                    "${disturbances.highConfidenceEvents} · stricter research tier",
                )
                rateRow(
                    "Evidence-supported all-valid rate",
                    disturbances.highConfidenceAllValidRate,
                )
                disturbances.highConfidenceScoredSleepRate?.let {
                    rateRow("Evidence-supported scored-sleep rate", it)
                }
                ResearchValueRow("With autonomic support", disturbances.autonomicSupportedEvents.toString())
                rateRow("Autonomic-confirmed all-valid rate", disturbances.autonomicAllValidRate)
                disturbances.autonomicScoredSleepRate?.let {
                    rateRow("Autonomic-confirmed scored-sleep rate", it)
                }
                ResearchValueRow(
                    "Guarded fallback candidates",
                    disturbances.guardedFallbackEvents.toString(),
                )
                ResearchValueRow(
                    "Unsupported split candidates suppressed",
                    disturbances.suppressedSplitEvents.size.toString(),
                )
                EventTimeline(disturbances)
                ResearchValueRow(
                    "Sensitivity candidate counts",
                    disturbances.sensitivitySweep.joinToString(" · ") {
                        "${(it.amplitudeReduction * 100).roundToInt()}%: ${it.candidateEvents}"
                    },
                )
                ResearchValueRow(
                    "Sensitivity all-valid rates",
                    disturbances.sensitivitySweep.joinToString(" · ") {
                        val threshold = (it.amplitudeReduction * 100).roundToInt()
                        val rate = it.ratePerAllValidHour?.let { value ->
                            String.format(Locale.US, "%.1f/h", value)
                        } ?: "n/a"
                        "$threshold%: $rate"
                    },
                )
                ResearchValueRow("Respiration-field coverage", percent(disturbances.coverage.respirationRaw))
                ResearchValueRow("Motion-vector coverage", percent(disturbances.coverage.motionVector))
                ResearchValueRow("Measured-HR coverage", percent(disturbances.coverage.heartRate))
                ResearchValueRow("R-R coverage", percent(disturbances.coverage.rrIntervals))
                disturbances.channelDiagnostics.forEach { diagnostic ->
                    ResearchValueRow(
                        diagnostic.label,
                        "${percent(diagnostic.sourceCoverage)} · ${diagnostic.availability} · " +
                            "${diagnostic.rhythmicWindows}/${diagnostic.usableWindows} rhythmic",
                    )
                    if (diagnostic.longestSourceGapSeconds > 2 || diagnostic.continuityRejectedWindows > 0) {
                        ResearchValueRow(
                            "${diagnostic.label} continuity",
                            "longest gap ${diagnostic.longestSourceGapSeconds}s · " +
                                "${diagnostic.continuityRejectedWindows} windows rejected",
                        )
                    }
                }
                Text(disturbances.reason, style = NoopType.footnote, color = Palette.textTertiary)

                RecentNightSummary(result.recentNights)

                Text("Wrist-orientation proxy", style = NoopType.body, color = Palette.textPrimary)
                val position = disturbances.positionProxy
                val positionStatus = when (position.status) {
                    Whoop4DisturbanceResearch.PositionStatus.UNAVAILABLE -> "Unavailable"
                    Whoop4DisturbanceResearch.PositionStatus.ASSUMPTION_FAILED -> "Magnitude assumption failed"
                    Whoop4DisturbanceResearch.PositionStatus.LOW_CONFIDENCE -> "Low confidence"
                    Whoop4DisturbanceResearch.PositionStatus.WRIST_ORIENTATION_AVAILABLE -> "Orientation episodes available"
                }
                ResearchValueRow("Position-proxy status", positionStatus)
                ResearchValueRow("Stable orientation coverage", percent(position.stableCoverage))
                ResearchValueRow("Stable orientation time", hours(position.stableHours))
                ResearchValueRow("Orientation episodes", position.orientationEpisodes.toString())
                ResearchValueRow("Large rotations", position.largeRotations.toString())
                position.rotationsPerHour?.let {
                    ResearchValueRow("Large rotations / h", String.format(Locale.US, "%.1f", it))
                }
                ResearchValueRow("Candidates near rotation", position.candidatesNearRotation.toString())
                val magnitudes = listOf(position.magnitudeP10, position.magnitudeMedian, position.magnitudeP90)
                if (magnitudes.all { it != null }) {
                    ResearchValueRow(
                        "Vector magnitude p10/50/90",
                        magnitudes.joinToString(" / ") { String.format(Locale.US, "%.2f", it) },
                    )
                }
                Text(position.reason, style = NoopType.footnote, color = Palette.textTertiary)
                Text(
                    "Research-only. Valid-hour rates normalize analyzable seconds; the observed-candidate floor divides only observed events by all timestamped sleep. Neither is clinical AHI/RDI. Optical fields are coverage, not calibrated SpO₂/PAT. Wrist orientation is not body position.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            } else {
                Text(
                    "No stored WHOOP 4 research rows yet. Complete a history sync, then refresh.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) { Text("Refresh", color = Palette.accent) }
                TextButton(onClick = onExport) { Text("Export validation bundle", color = Palette.accent) }
            }
        }
    }
}

private fun percent(value: Double): String = "${(value * 100).roundToInt()}%"

private fun hours(value: Double): String = String.format(Locale.US, "%.2f h", value)

@Composable
private fun EventTimeline(result: Whoop4DisturbanceResearch.Result) {
    val startTs = result.provenance.sessionStartTs ?: result.provenance.inputStartTs ?: return
    val endTs = result.provenance.sessionEndTs ?: result.provenance.inputEndTs ?: return
    if (endTs <= startTs || result.events.isEmpty()) return
    var showDetails by remember(result.provenance.inputFingerprint) { mutableStateOf(false) }
    val description =
        "${result.events.size} event candidates across ${hours((endTs - startTs) / 3600.0)}; " +
            "${result.highConfidenceEvents} pass the evidence-supported tier"
    Text("Candidate timeline", style = NoopType.body, color = Palette.textPrimary)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .semantics { contentDescription = description },
    ) {
        val inset = 8.dp.toPx()
        val trackTop = size.height * 0.38f
        val trackHeight = size.height * 0.24f
        drawRoundRect(
            color = Palette.surfaceInset,
            topLeft = androidx.compose.ui.geometry.Offset(inset, trackTop),
            size = androidx.compose.ui.geometry.Size(max(1f, size.width - inset * 2), trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f),
        )
        val span = (endTs - startTs).toFloat()
        result.events.forEach { event ->
            val fraction = ((event.startTs - startTs) / span).coerceIn(0f, 1f)
            val x = inset + fraction * (size.width - inset * 2)
            drawLine(
                color = if (event.highConfidence) Palette.accent else Palette.textTertiary.copy(alpha = 0.62f),
                start = androidx.compose.ui.geometry.Offset(x, trackTop - 7.dp.toPx()),
                end = androidx.compose.ui.geometry.Offset(x, trackTop + trackHeight + 7.dp.toPx()),
                strokeWidth = if (event.highConfidence) 2.5.dp.toPx() else 1.25.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
    Text(
        "Each tick is one broad candidate; highlighted ticks pass the stricter evidence-supported tier.",
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
    TextButton(onClick = { showDetails = !showDetails }) {
        Text(
            if (showDetails) "Hide strongest candidates" else "Inspect strongest candidates",
            color = Palette.accent,
        )
    }
    if (showDetails) {
        result.events.sortedByDescending { it.evidenceScore }.take(12).sortedBy { it.startTs }
            .forEach { event ->
                val flags = buildList {
                    if (event.autonomicSupport) add("HR/R-R")
                    if (event.recoveryMotion) add("recovery motion")
                    if (event.nearLargeWristRotation) add("near rotation")
                    if (event.inScoredSleep == true) add("scored sleep")
                    if (event.independentModalitySupport > 0) {
                        add("${event.independentModalitySupport} independent modality")
                    }
                    if (event.fallbackUsed) add("guarded fallback")
                    if (event.splitFromOverlong) add("split long reduction")
                }
                ResearchValueRow(
                    eventClock(event.startTs),
                    "${event.durationSeconds}s · ${percent(event.amplitudeReduction)} · " +
                        "evidence ${percent(event.evidenceScore)}" +
                        if (flags.isEmpty()) "" else " · ${flags.joinToString(", ")}",
                )
            }
        if (result.events.size > 12) {
            Text(
                "Showing the 12 highest evidence scores; the validation ZIP contains every event.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun RecentNightSummary(nights: List<BreathingNightSummary>) {
    val analyzableNights = nights.filter { it.scoredSleepRate != null || it.allValidRate != null }
    if (analyzableNights.isEmpty()) return
    Text("Recent-night repeatability", style = NoopType.body, color = Palette.textPrimary)
    if (analyzableNights.size < 2) {
        Text(
            "One analyzable night is available. Another night with WHOOP 4 research rows is needed " +
                "before repeatability can be compared.",
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }
    val comparableRates = analyzableNights.mapNotNull { it.scoredSleepRate }
    if (comparableRates.size >= 2) {
        ResearchValueRow(
            "Scored-sleep range",
            String.format(
                Locale.US,
                "%.1f–%.1f/h across %d nights",
                comparableRates.min(),
                comparableRates.max(),
                comparableRates.size,
            ),
        )
    }
    analyzableNights.forEach { night ->
        val primary = night.scoredSleepRate ?: night.allValidRate
        val denominator = if (night.scoredSleepRate != null) "scored sleep" else "all valid"
        val status = when (night.status) {
            Whoop4DisturbanceResearch.Status.UNAVAILABLE -> "unavailable"
            Whoop4DisturbanceResearch.Status.INSUFFICIENT_SIGNAL -> "insufficient"
            Whoop4DisturbanceResearch.Status.LOW_CONFIDENCE -> "low confidence"
            Whoop4DisturbanceResearch.Status.EXPLORATORY_ESTIMATE -> "exploratory"
        }
        val primaryText = primary?.let { String.format(Locale.US, "%.1f/h", it) } ?: "n/a"
        val rhythmic = night.rhythmicScoredSleepRate?.let {
            " · ${String.format(Locale.US, "%.1f/h", it)} rhythmic"
        } ?: ""
        ResearchValueRow(
            nightLabel(night.endTs),
            "$primaryText $denominator · ${night.candidates} broad / " +
                "${night.evidenceSupported} supported$rhythmic · $status",
        )
    }
    Text(
        "Recent nights use the same locked algorithm; this is repeatability context, not clinical severity.",
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
}

private val EVENT_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val NIGHT_LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.US)

private fun eventClock(ts: Long): String =
    EVENT_TIME_FORMAT.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))

private fun nightLabel(ts: Long): String =
    NIGHT_LABEL_FORMAT.format(Instant.ofEpochSecond(ts).atZone(ZoneId.systemDefault()))

@Composable
private fun rateRow(label: String, rate: Whoop4DisturbanceResearch.RateEstimate) {
    val value = rate.ratePerHour?.let { String.format(Locale.US, "%.1f/h", it) } ?: "Unavailable"
    ResearchValueRow(
        label,
        "$value · ${rate.candidateEvents} events / ${hours(rate.denominatorHours)}",
    )
}

@Composable
private fun ResearchValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.weight(0.48f),
            style = NoopType.footnote,
            color = Palette.textSecondary,
        )
        Text(
            value,
            modifier = Modifier.weight(0.52f),
            style = NoopType.footnote.copy(fontFeatureSettings = "tnum"),
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
        )
    }
}
/** A report staged for the mandatory review gate: the profile, its title, the already-redacted entries
 *  and the gate built over them. The Kotlin gate keeps its entries private, so we hold them here too to
 *  hand TestReportFlow.run the same list it reviews. [modeInactive] (#1002): the selected profile's test
 *  mode is not on at report time, so the bundle carries no capture for the very thing being reported -
 *  the review dialog warns off it (the #812 capture_check only grades ACTIVE modes, so it can't). */
private class PendingReport(
    val profile: TestDomain,
    val title: String,
    val entries: List<Pair<String, ByteArray>>,
    val gate: ReportReviewGate,
    val modeInactive: Boolean = false,
)

/** The "whole app" report profile for the section-3 manual Report button. MASTER is not a registry mode
 *  (it has no wear-and-capture flow), so the deep-link self-applies the test:all label via this. */
private val MASTER_REPORT_MODE = TestMode(
    domain = TestDomain.MASTER, title = uiString(R.string.l10n_test_centre_screen_bug_report_5a7ee5ac), blurb = "", icon = "ic_bug",
    priority = com.noop.testcentre.TestPriority.HIGH, captures = emptyList(),
    questionnaire = emptyList(), liveReadout = emptyList(),
    capture = com.noop.testcentre.CaptureKind.Toggle, includesScreenshot = false, requires5MG = false,
)

/** Assemble the redacted, capped bundle for a profile and wrap it in the review gate. Suspend (#1002):
 *  the storage probe reads the store, so the callers launch it on the UI scope; the dialog presents off
 *  the same `pendingReport` state a beat after the tap. */
private suspend fun buildPending(
    context: android.content.Context,
    mode: TestMode,
    // Stays a STRING: TestBundleAssembler renders a report file, the one consumer that genuinely wants the
    // joined text. Its two callers are user taps, not the 250 ms refresh.
    logText: String,
    vm: AppViewModel,
): PendingReport {
    // #1002 REAL storage probe, replacing the Phase-1 zeros in meta.json:
    //  - db_bytes: the Room store's on-disk footprint (noop_whoop.db + its -wal/-shm sidecars);
    //  - rows: per-table row counts via the store (WhoopRepository.storageRowCounts);
    //  - raw_capture_bytes: the 5/MG frame-recorder JSONL on disk (both rotation generations).
    // Everything read, never guessed; when nothing was readable the probe stays null and meta keeps the
    // honest zeroed block. Mirrors the Swift TestCentreReport.storageProbe.
    val dbPath = context.getDatabasePath(com.noop.data.WhoopDatabase.DB_NAME)
    var dbBytes = 0L
    for (suffix in listOf("", "-wal", "-shm")) {
        val f = java.io.File(dbPath.path + suffix)
        if (f.exists()) dbBytes += f.length()
    }
    val rows = vm.repo.storageRowCounts()
    // #1911: bytes beside the counts, reusing the counts just read rather than a second COUNT(*) pass over
    // thirteen tables - each is a full scan on the large stores this report is pulled from.
    val rowBytes = com.noop.data.StorageFootprint(
        com.noop.data.WhoopDatabase.get(context)).byteEstimates(rows)
    var rawBytes = 0L
    for (name in listOf(
        com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE,
        com.noop.ble.WhoopBleClient.WHOOP5_CAPTURE_FILE + ".1",
    )) {
        val f = java.io.File(context.filesDir, name)
        if (f.exists()) rawBytes += f.length()
    }
    val storage = if (dbBytes > 0L || rows.isNotEmpty() || rawBytes > 0L) {
        com.noop.testcentre.TestBundleMeta.Storage(
            dbBytes = dbBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rows = rows,
            rawCaptureBytes = rawBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            rowBytes = rowBytes,
        )
    } else {
        null
    }
    // #1002: the connected model - the scan/connect path persists the DETECTED family to this pref, so
    // it reflects the strap that actually linked; the display name matches the Swift wire value.
    val strapModel = NoopPrefs.of(context).getString("noop.selectedWhoopModel", null)
        ?.let { name -> runCatching { WhoopModel.valueOf(name).displayName }.getOrNull() }
    val entries = TestBundleAssembler.assemble(context, mode.domain, logText, storage, strapModel)
    val modeInactive = mode.domain != TestDomain.MASTER && !TestCentre.from(context).active(mode.domain)
    return PendingReport(mode.domain, mode.title, entries, ReportReviewGate(entries), modeInactive)
}

@Composable
private fun TestModeRow(
    mode: TestMode,
    active: Boolean,
    startedAtSeconds: Long?,
    live: LiveState,
    activeStrapId: String,
    is5MG: Boolean,
    vm: AppViewModel,
    onToggle: (Boolean) -> Unit,
    onReport: () -> Unit,
) {
    var on by remember { mutableStateOf(active) }
    val elapsed = startedAtSeconds?.let { (System.currentTimeMillis() / 1000.0) - it }
    val refreshSources = TestCentreLiveRefreshPolicy.sources(mode, on)
    // An active row observes the log's own revision, coalesced during bursty offloads. An inactive row
    // creates no collector and never snapshots/export-formats the log.
    // #1468 follow-up: LINES, not a joined string. Both consumers below immediately work line-wise, so the
    // string this replaced was built (and re-split) on every 250 ms tick for nothing.
    val logLines = if (refreshSources.observeLogRevision) rememberActiveLogLines(vm.ble) else emptyList()
    // #965: HONEST per-mode captured-day count for a guided row (distinct days THIS mode produced its own
    // trace on), read from the same log the report exports, so each active mode accumulates its OWN count
    // instead of every guided row sharing one elapsed number. null for a toggle mode (no "K of N") / when off.
    val capturedUnits: Int? =
        if (on && mode.capture is CaptureKind.Guided) {
            CaptureAccumulator.capturedDays(
                domain = mode.domain,
                reportLines = logLines,
                tzOffsetSeconds =
                    (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000).toLong(),
            )
        } else {
            null
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mode.title, style = NoopType.body, color = Palette.textPrimary)
                Text(
                    TestCentreLayout.statusText(mode, on, elapsed, capturedUnits),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
            Switch(
                checked = on,
                onCheckedChange = { on = it; onToggle(it) },
                colors = settingsSwitchColors(),
            )
        }
        Text(mode.blurb, style = NoopType.footnote, color = Palette.textTertiary)
        if (on) {
            TestCentreLiveReadoutPanel(
                mode = mode,
                logLines = logLines,
                live = live,
                is5MG = is5MG,
                activeStrapId = activeStrapId,
                vm = vm,
            )
        }
        Row {
            Spacer(Modifier.weight(1f))
            // "Share", not "Report", to match the strap-log button below and the preview sheet this
            // opens, whose own copy already reads "Nothing leaves this phone until you tap Share".
            // Reuses the Share string this screen already carries, so no new copy and no new locales.
            TextButton(onClick = onReport) {
                Text(uiString(R.string.l10n_test_centre_screen_share_09ca55ca), color = Palette.accent, style = NoopType.body)
            }
        }
    }
}

/** Registry-driven live diagnostics for an active test row. The common presentation mapping is pure and
 * JVM-tested; only Sleep and Battery need a small repository snapshot, loaded here while the row is on.
 * Since this composable does not exist for an inactive row, inactive modes do no parsing or store reads. */
@Composable
private fun TestCentreLiveReadoutPanel(
    mode: TestMode,
    logLines: List<String>,
    live: LiveState,
    is5MG: Boolean,
    activeStrapId: String,
    vm: AppViewModel,
) {
    var hrSamples by remember(mode.id) { mutableStateOf(emptyList<HrSample>()) }
    var gravitySamples by remember(mode.id) { mutableStateOf(emptyList<GravitySample>()) }
    var batteryEstimate by remember(mode.id) { mutableStateOf<BatteryEstimator.Estimate?>(null) }
    var nowUnix by remember(mode.id) { mutableStateOf(System.currentTimeMillis() / 1_000) }
    val sources = TestCentreLiveRefreshPolicy.sources(mode, active = true)
    val sleepRevision = if (sources.observeSleepSampleRevision) {
        boundedRevision(vm.repo.sleepSampleRevision, coalesceMs = 250)
    } else {
        0L
    }
    val batteryRevision = if (sources.observeBatteryRevision) {
        boundedRevision(vm.repo.batteryRevision, coalesceMs = 500)
    } else {
        0L
    }

    // Sleep reloads only after Room reports a successful HR/gravity insert or the active strap changes.
    // Same-BPM HR and gravity-only inserts therefore refresh even when LiveState itself stays equal.
    LaunchedEffect(mode.domain, activeStrapId, sleepRevision) {
        if (mode.domain != TestDomain.SLEEP) return@LaunchedEffect
        val now = System.currentTimeMillis() / 1_000
        val from = now - 60 * 60
        hrSamples = runCatching {
            vm.repo.hrSamplesForDevice(activeStrapId, from, now, limit = 10_000)
        }.getOrDefault(emptyList())
        gravitySamples = runCatching {
            vm.repo.gravitySamplesForDevice(activeStrapId, from, now, limit = 10_000)
        }.getOrDefault(emptyList())
    }

    // Battery has its own event source and keys. It is deliberately not keyed on HR or Sleep revisions.
    LaunchedEffect(mode.domain, activeStrapId, batteryRevision, live.batteryPct, is5MG, live.charging) {
        if (mode.domain != TestDomain.BATTERY) return@LaunchedEffect
        val now = System.currentTimeMillis() / 1_000
        val from = now - 14L * 86_400
        val samples = runCatching {
            vm.repo.batterySamples(activeStrapId, from, now, limit = 2_000)
                .mapNotNull { sample -> sample.soc?.let { sample.ts to it } }
        }.getOrDefault(emptyList())
        val rated = if (is5MG) BatteryEstimator.ratedLifeHoursWhoop5
            else BatteryEstimator.ratedLifeHoursWhoop4
        batteryEstimate = BatteryEstimator.estimate(samples, rated)
    }

    // Only Connection uptime needs wall-clock movement. Other modes create no timer.
    LaunchedEffect(sources.connectionClockEveryMs) {
        val everyMs = sources.connectionClockEveryMs ?: return@LaunchedEffect
        while (isActive) {
            nowUnix = System.currentTimeMillis() / 1_000
            delay(everyMs)
        }
    }

    val rows = TestCentreLiveReadouts.rows(
        mode = mode,
        active = true,
        snapshot = TestCentreLiveSnapshot(
            logLines = logLines,
            nowUnix = nowUnix,
            connected = live.connected,
            batteryPct = live.batteryPct,
            batteryEstimate = batteryEstimate,
            hrSamples = hrSamples,
            gravitySamples = gravitySamples,
        ),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Text(row.label, style = NoopType.footnote, color = Palette.textTertiary)
                Spacer(Modifier.weight(1f))
                Text(row.value, style = NoopType.mono, color = Palette.textSecondary)
            }
        }
    }
}

/** Collect a monotonic source revision while this call is in composition. During an offload burst the
 * collector emits at most once per [coalesceMs], always converging on the newest revision. No source event,
 * no wake-up; this is event-driven throttling, not polling. */
@Composable
private fun boundedRevision(flow: StateFlow<Long>, coalesceMs: Long): Long {
    val revision by produceState(initialValue = flow.value, flow, coalesceMs) {
        flow.collect { next ->
            value = next
            delay(coalesceMs)
        }
    }
    return revision
}

@Composable
private fun rememberActiveLogLines(ble: WhoopBleClient): List<String> {
    val revision = boundedRevision(ble.logRevision, coalesceMs = 250)
    return remember(ble, revision) { ble.exportLogLines() }
}

@Composable
private fun DiagnosticToolsCard(vm: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRecalibrate by remember { mutableStateOf(false) }
    // "Debug logging" moved here from Settings: dev-only, mirrors the strap log to logcat over adb.
    var debugLogging by remember { mutableStateOf(NoopPrefs.debugLogging(context)) }
    // #polar-debug: the model NOOP auto-detects for a PAIRED Polar strap, from its stored advertised name
    // (no live connection needed). null when no Polar strap is paired → the whole toggle stays hidden, so a
    // non-Polar user never sees Polar debug. Loaded once from the registry.
    var polarIdentity by remember { mutableStateOf<String?>(null) }
    var polarDebugLogging by remember { mutableStateOf(NoopPrefs.polarDebugLogging(context)) }
    // #1284 residual 3: the experimental Oura onset-keying toggle, shown only when an Oura ring is paired.
    var ouraPaired by remember { mutableStateOf(false) }
    var ouraOnsetKeying by remember { mutableStateOf(NoopPrefs.ouraOnsetKeying(context)) }
    var ouraNotifyMaskFull by remember { mutableStateOf(NoopPrefs.ouraNotifyMaskFull(context)) }
    LaunchedEffect(Unit) {
        val paired = runCatching { vm.pairedDevices() }.getOrDefault(emptyList())
        polarIdentity = PolarModel.debugIdentification(paired.firstOrNull { PolarModel.isPolar(it.model) }?.model)
        ouraPaired = paired.any { it.brand.equals("Oura", ignoreCase = true) }
    }
    var detailedCapture by remember { mutableStateOf(NoopPrefs.detailedCapture(context)) }
    var captureShareBusy by remember { mutableStateOf(false) }
    // #646/#651: LogExport.shareStrapLog's file write now runs on Dispatchers.IO instead of blocking the
    // caller, so nothing else stops a second tap mid-share. Same disable-while-busy + spinner shape as
    // Settings' backupBusy pattern — this screen has its own local flag, this button being a separate
    // instance from the Settings "Share strap log" button.
    var strapLogBusy by remember { mutableStateOf(false) }
    SettingsSectionTC(
        icon = Icons.Filled.Info,
        title = uiString(R.string.l10n_test_centre_screen_diagnostic_tools_04ba4d3f),
        blurb = "Your strap log, a Charge recalibrate, and the device environment.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Strap log, the same exportLogText share the Settings Diagnostics button uses.
            NoopButton(
                text = uiString(R.string.l10n_test_centre_screen_share_strap_log_for_bug_reports_b9802500),
                leadingIcon = Icons.Filled.Upload,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = !strapLogBusy,
                onClick = {
                    strapLogBusy = true
                    scope.launch {
                        // try/finally: the flag must clear on any exit, not just the happy path (#961 follow-up).
                        try {
                            LogExport.shareStrapLog(context, vm.ble.exportLogText())
                        } finally {
                            strapLogBusy = false
                        }
                    }
                },
            )
            if (strapLogBusy) {
                NoopBusyRow()
            }
            // Recalibrate Charge baseline, the same Baselines.recalibrateRecoveryBaselines call.
            NoopButton(
                text = uiString(R.string.l10n_test_centre_screen_recalibrate_charge_baseline_52a05a26),
                leadingIcon = Icons.Filled.Autorenew,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { showRecalibrate = true },
            )
            // Debug logging (moved here from Settings): mirror the strap log to logcat for adb
            // development. Dev-only, off by default; the in-app log and "Share strap log" above work either way.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(uiString(R.string.l10n_test_centre_screen_debug_logging_daaa7d74), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_test_centre_screen_also_write_the_strap_log_to_655b55b2),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = debugLogging,
                    onCheckedChange = { debugLogging = it; vm.setDebugLogging(it) },
                    colors = settingsSwitchColors(),
                )
            }
            // #polar-debug: only shown when a Polar strap is paired (polarIdentity != null). The subtitle is
            // the model auto-detected from the paired record; the toggle also writes it to the strap log on
            // each connect. Off by default. Diagnostic-only — nothing gates behaviour on it.
            polarIdentity?.let { identity ->
                ToggleRowTC(
                    title = "Polar debug logging",
                    description = "$identity.\nLogs this identification to the strap log on each connect, " +
                        "so a Polar bug report shows the model NOOP resolved your strap to.",
                    checked = polarDebugLogging,
                    onCheckedChange = { polarDebugLogging = it; vm.setPolarDebugLogging(it) },
                )
            }
            // #1284 residual 3: experimental Oura 0x49-onset keying, only when an Oura ring is paired.
            if (ouraPaired) {
                ToggleRowTC(
                    title = "Oura onset keying (experimental)",
                    description = "Keys each Oura sleep night on its stable 0x49 onset and suppresses " +
                        "duplicate re-serves at the source, instead of the shipped end-anchored persist " +
                        "(#1284). Off by default — a hardware-validation toggle. Watch the strap log for " +
                        "'onset-key(#1284)' lines.",
                    checked = ouraOnsetKeying,
                    onCheckedChange = { ouraOnsetKeying = it; vm.setOuraOnsetKeying(it) },
                )
                // Packed-notification A/B (OURA_PROTOCOL.md s2.3). Takes effect at the NEXT connect only;
                // nothing is written until then and nothing persists on the ring.
                ToggleRowTC(
                    title = stringResource(R.string.oura_notify_mask_full_title),
                    description = stringResource(R.string.oura_notify_mask_full_desc),
                    checked = ouraNotifyMaskFull,
                    onCheckedChange = { ouraNotifyMaskFull = it; vm.setOuraNotifyMaskFull(it) },
                )
            }
            // #1121 Detailed capture: an adb-like rolling on-device log, no computer needed. Off by default.
            ToggleRowTC(
                title = "Detailed capture to file",
                description = "Continuously append the strap log to a rolling on-device file (≤8 MB, one " +
                    "previous generation kept) so a long-running issue — battery drain, an overnight " +
                    "offload — is captured for hours instead of the ~50 minutes the in-memory share holds. " +
                    "Keeps going if the app is killed and resumes on next launch. The file stays on the " +
                    "phone unless you share it below.",
                checked = detailedCapture,
                onCheckedChange = { detailedCapture = it; vm.setDetailedCapture(it) },
            )
            if (detailedCapture) {
                Text(
                    "Capturing… reproduce the issue, then share the log below.",
                    style = NoopType.footnote,
                    color = Palette.accent,
                )
            }
            NoopButton(
                text = "Share captured log",
                leadingIcon = Icons.Filled.Upload,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                enabled = !captureShareBusy,
                onClick = {
                    captureShareBusy = true
                    scope.launch {
                        try {
                            LogExport.shareCaptureLog(context)
                        } finally {
                            captureShareBusy = false
                        }
                    }
                },
            )
            if (captureShareBusy) {
                NoopBusyRow()
            }
        }
    }
    if (showRecalibrate) {
        AlertDialog(
            onDismissRequest = { showRecalibrate = false },
            containerColor = Palette.surfaceOverlay,
            title = { Text(uiString(R.string.l10n_test_centre_screen_recalibrate_your_charge_baseline_018e3846), style = NoopType.title2, color = Palette.textPrimary) },
            text = {
                Text(
                    uiString(R.string.l10n_test_centre_screen_this_restarts_the_roughly_4_night_33cce377),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nowSeconds = System.currentTimeMillis() / 1000L
                    val editor = NoopPrefs.of(context).edit()
                    Baselines.recalibrateRecoveryBaselines(editor, nowSeconds)
                    editor.apply()
                    showRecalibrate = false
                    vm.syncNow()
                }) { Text(uiString(R.string.l10n_test_centre_screen_recalibrate_aaa989ea), style = NoopType.body, color = Palette.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showRecalibrate = false }) {
                    Text(uiString(R.string.l10n_test_centre_screen_cancel_77dfd213), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun ExportCard(vm: AppViewModel, onReport: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { DebugExportSettings.from(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var minutes by remember { mutableStateOf(settings.timeMinutes) }
    // Retention (#642): how many scheduled-export generations to keep before the next write prunes
    // older ones. Same shape as BackupSyncScreen's keep-count dropdown.
    var keep by remember { mutableStateOf(settings.keepCount) }
    var keepMenu by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    SettingsSectionTC(
        icon = Icons.Filled.Upload,
        title = uiString(R.string.l10n_test_centre_screen_export_f3e4fadb),
        blurb = "Report a bug with your log, or have NOOP drop a daily copy into its export folder.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NoopButton(
                text = uiString(R.string.l10n_test_centre_screen_report_a_bug_with_my_log_5101ee49),
                leadingIcon = Icons.Filled.BugReport,
                kind = NoopButtonKind.Primary,
                fullWidth = true,
                onClick = onReport,
            )
            // Daily auto-export, the same DebugExportSettings writes the Settings card uses.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiString(R.string.l10n_test_centre_screen_daily_auto_export_02ed9f75), style = NoopType.subhead, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_test_centre_screen_android_runs_this_via_workmanager_doze_875e499e),
                        style = NoopType.footnote, color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        settings.enabled = it
                        DebugExportScheduler.reschedule(context)
                    },
                    colors = settingsSwitchColors(),
                )
            }
            if (enabled) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiString(R.string.l10n_test_centre_screen_export_time_2ca90c2e), style = NoopType.subhead, color = Palette.textPrimary)
                    }
                    TimeChip(
                        minutes = minutes,
                        accessibilityLabel = "Daily export time",
                        onPicked = {
                            minutes = it
                            settings.timeMinutes = it
                            DebugExportScheduler.applyTimeChange(context)
                        },
                    )
                }
                // Retention: how many scheduled exports to keep. Wired to DebugExportSettings.keepCount;
                // the next scheduled write prunes the oldest generations beyond this count (#642).
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(uiString(R.string.l10n_test_centre_screen_keep_last_exports_44bc65f5), style = NoopType.subhead, color = Palette.textPrimary)
                        Text(
                            uiString(R.string.l10n_test_centre_screen_older_scheduled_exports_beyond_this_c75723fc),
                            style = NoopType.footnote, color = Palette.textTertiary,
                        )
                    }
                    Box {
                        TextButton(onClick = { keepMenu = true }) {
                            Text(uiString(R.string.l10n_backup_sync_screen_keep_1addd33c, keep), style = NoopType.body, color = Palette.accent)
                        }
                        DropdownMenu(expanded = keepMenu, onDismissRequest = { keepMenu = false }) {
                            EXPORT_KEEP_OPTIONS.forEach { n ->
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
                                        settings.keepCount = n
                                        keepMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
            // Manual clear (#642): always available, even with the toggle off, since files written
            // while it was on can outlive that toggle flip.
            NoopButton(
                text = uiString(R.string.l10n_test_centre_screen_clear_scheduled_exports_54123329),
                leadingIcon = Icons.Filled.DeleteOutline,
                kind = NoopButtonKind.Secondary,
                fullWidth = true,
                onClick = { showClearConfirm = true },
            )
        }
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text(
                    uiString(R.string.l10n_test_centre_screen_clear_scheduled_exports_36e93dd0),
                    style = NoopType.title2, color = Palette.textPrimary,
                )
            },
            text = {
                Text(
                    uiString(R.string.l10n_test_centre_screen_this_deletes_every_scheduled_strap_ea52c535),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    LogExport.clearScheduledExports(context)
                    showClearConfirm = false
                }) { Text(uiString(R.string.l10n_lab_book_screen_clear_719ea396), style = NoopType.body, color = Palette.statusCritical) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(uiString(R.string.l10n_test_centre_screen_cancel_77dfd213), style = NoopType.body, color = Palette.textSecondary)
                }
            },
        )
    }
}

/** Retention choices for the scheduled-debug-export keep-count dropdown (#642). These are lightweight
 *  text/JSONL files, not a whole-DB snapshot, so the range skews longer than BackupSyncScreen's
 *  KEEP_OPTIONS. */
private val EXPORT_KEEP_OPTIONS = listOf(3, 7, 14, 30, 60)

/**
 * Test Centre → Experimental algorithms. The single home for OPT-IN, off-by-default, non-clinical research
 * variants that swap which model computes a metric (never detection, never a stored WHOOP value). Each toggle
 * writes the SAME [PuffinExperiment] key its Swift twin reads, so the platforms stay in lockstep. Hosts the
 * HR-from-PPG sub-lag interpolation variant and the read-only HRV-readiness (Plews/Altini) tier readout.
 * Twin of the Swift TestCentreView experimentalAlgorithmsCard.
 */
@Composable
private fun ExperimentalAlgorithmsCard(vm: AppViewModel) {
    val context = LocalContext.current
    val puffin = remember { PuffinExperiment.from(context) }
    var ppgHrSubLag by remember { mutableStateOf(puffin.ppgHrSubLagInterp) }
    var hrvReadiness by remember { mutableStateOf(puffin.hrvReadiness) }
    // The SAME nightly HRV series the recovery UI reads (repo-merged DailyMetric.avgHrv, oldest-first), fed
    // into the pure HRVReadiness engine ONLY to render the toggle's own reading inline below — the default
    // Charge ring / analyzeDay path is never touched, and this feeds no downstream gate.
    val recentDays by vm.recentDays.collectAsStateWithLifecycle()
    SettingsSectionTC(
        icon = Icons.Filled.Science,
        title = uiString(R.string.l10n_test_centre_screen_experimental_algorithms_e09581e2),
        blurb = "Research-grade alternatives / precision tweaks. Opt-in, off by default, non-clinical.",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ToggleRowTC(
                title = uiString(R.string.l10n_test_centre_screen_hr_from_ppg_sub_lag_interpolation_a3ed1536),
                description = "When NOOP reconstructs heart rate from the WHOOP 5/MG v26 optical waveform (the " +
                    "seconds the strap stored no HR), refine the autocorrelation peak with a parabolic sub-lag " +
                    "fit so the estimate is not quantized to roughly 16 bpm steps near a high HR. It only fills " +
                    "seconds the strap never reported; it never overrides a stored HR. 5/MG only, off by default.",
                checked = ppgHrSubLag,
                onCheckedChange = { ppgHrSubLag = it; puffin.ppgHrSubLagInterp = it },
            )
            ToggleRowTC(
                title = uiString(R.string.l10n_test_centre_screen_hrv_readiness_plews_altini_bce6578f),
                description = "A read-only Plews/Altini smallest-worthwhile-change reading of your nightly HRV: " +
                    "it shows whether your 7-night HRV baseline sits above, inside, or below your personal " +
                    "normal band. It changes nothing else - the Charge ring is identical whether this is on or " +
                    "off. This is rough / early testing, not yet validated against varying real data (n=1).",
                checked = hrvReadiness,
                onCheckedChange = { hrvReadiness = it; puffin.hrvReadiness = it },
            )
            // The toggle's OWN effect, shown in place: when on, the live Plews/Altini reading. Nothing renders
            // when off, so the flag off is zero behaviour change and feeds no downstream gate.
            if (hrvReadiness) HrvReadinessReadoutTC(recentDays)
        }
    }
}

/**
 * Inline, opt-in HRV-readiness readout. Renders directly under the "HRV readiness (Plews/Altini)" toggle when
 * the flag is on, so the toggle's own effect is visible in place. Reads the SAME repo-merged nightly
 * [DailyMetric.avgHrv] series (oldest-first) the recovery UI has and runs it through the pure [HRVReadiness]
 * engine — it never touches the default Charge ring or analyzeDay. Below [HRVReadiness.MIN_NIGHTS] valid
 * nights it shows the honest calibrating count instead of a fabricated tier. Twin of the Swift
 * `hrvReadinessReadout`.
 */
@Composable
private fun HrvReadinessReadoutTC(days: List<DailyMetric>) {
    // Memoize the pure evaluate + valid-night count against the day-list identity so it only recomputes when
    // the series actually changes (not on every recomposition).
    val (result, validCount) = remember(days) {
        val cfg = Baselines.hrvCfg
        val series = days.map { it.avgHrv }
        val valid = series.count { v -> v != null && v >= cfg.minVal && v <= cfg.maxVal }
        HRVReadiness.evaluate(series) to valid
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (result != null) {
            val (word, color) = when (result.tier) {
                ReadinessTier.PRIMED -> "primed" to Palette.statusPositive
                ReadinessTier.NORMAL -> "normal" to Palette.textPrimary
                ReadinessTier.SUPPRESSED -> "suppressed" to Palette.statusWarning
            }
            Text(uiString(R.string.l10n_test_centre_screen_hrv_readiness_experimental_word_a471930e, word), style = NoopType.subhead, color = color)
            val base = result.baseline7Ms.roundToInt()
            val lo = result.normalLowMs.roundToInt()
            val hi = result.normalHighMs.roundToInt()
            val watch = if (result.overreachingWatch) ", overreaching watch" else ""
            Text(
                uiString(R.string.l10n_test_centre_screen_7_night_baseline_base_ms_normal_43d9cfa6, base, lo, hi, watch),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        } else {
            Text(uiString(R.string.l10n_test_centre_screen_hrv_readiness_experimental_924f61bc), style = NoopType.subhead, color = Palette.textTertiary)
            Text(
                uiString(R.string.l10n_test_centre_screen_calibrating_validcount_hrvreadiness_min_nights_nights_c5b21706, validCount, HRVReadiness.MIN_NIGHTS),
                style = NoopType.footnote, color = Palette.textTertiary,
            )
        }
    }
}

/** A titled toggle + caption row for the Experimental algorithms card (same NoopType/Palette tokens + switch
 *  colours as the other Test Centre rows). Local to Test Centre so it never reaches into SettingsScreen. */
@Composable
private fun ToggleRowTC(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary, modifier = Modifier.weight(1f))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = settingsSwitchColors(),
            )
        }
        Text(description, style = NoopType.footnote, color = Palette.textTertiary)
    }
}

@Composable
private fun ReportReviewDialog(
    previewText: String,
    modeInactive: Boolean,
    onCancel: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Palette.surfaceOverlay,
        title = { Text(uiString(R.string.l10n_test_centre_screen_review_before_sharing_d7050383), style = NoopType.title2, color = Palette.textPrimary) },
        text = {
            Column {
                if (modeInactive) {
                    // #1002: the selected profile's test mode is off, so this bundle carries no capture
                    // for the very thing being reported. Warn plainly, with the fix, BEFORE the user
                    // ships a report a maintainer can't act on. Twin of the Swift review-sheet warning.
                    Text(
                        uiString(R.string.l10n_test_centre_screen_heads_up_this_test_mode_is_8b82ed69),
                        style = NoopType.footnote, color = Palette.statusWarning,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Text(
                    uiString(R.string.l10n_test_centre_screen_this_is_exactly_what_your_report_77278bdd),
                    style = NoopType.subhead, color = Palette.textSecondary,
                )
                Text(
                    previewText.ifBlank { "(nothing to share yet)" },
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onShare) { Text(uiString(R.string.l10n_test_centre_screen_share_09ca55ca), style = NoopType.body, color = Palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(uiString(R.string.l10n_test_centre_screen_cancel_77dfd213), style = NoopType.body, color = Palette.textSecondary) }
        },
    )
}

// MARK: - Local section + toggle wrappers (Test Centre owns its own so it never reaches into the private
// SettingsScreen.kt helpers; same NoopCard idiom).

@Composable
private fun SettingsSectionTC(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    blurb: String,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Overline("Test Centre")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.Icon(
                        icon, contentDescription = null, tint = Palette.accent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(title, style = NoopType.title2, color = Palette.textPrimary)
                }
            }
            Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            content()
        }
    }
}

@Composable
private fun settingsSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Palette.surfaceBase,
    checkedTrackColor = Palette.accent,
    uncheckedThumbColor = Palette.textSecondary,
    uncheckedTrackColor = Palette.surfaceInset,
    uncheckedBorderColor = Palette.hairline,
)
