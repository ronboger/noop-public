package com.noop.ui

import com.noop.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

// MARK: - IntervalsScreen (ported from Strand/Screens/IntervalTimerView.swift)
//
// Silent haptic HIIT interval timer. Train hands-free: the strap buzzes every
// transition so you never have to look at the screen. Strong triple-buzz at the
// start of each WORK block, a short single buzz into REST, a 3-2-1 tick on the last
// seconds of every phase, and a long 5-loop buzz when the whole session finishes.
// With no strap bonded it still works as a big glanceable visual timer (no haptics).

private enum class IntervalPhase(val label: String) {
    Work("WORK"),
    Rest("REST"),
    Done("DONE"),
}

/**
 * Silent haptic HIIT interval timer: configurable work/rest/rounds, a big glanceable
 * countdown ring, phase + round read-out, Start/Pause/Reset, a session overview, and
 * a strap buzz on every transition (triple into WORK, single into REST, a 3-2-1 tick,
 * a long 5-loop buzz on completion). Buzz cues are skipped entirely when no strap is
 * bonded, so it degrades cleanly to a pure visual timer.
 */
@Composable
fun IntervalsScreen(vm: AppViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()

    // Config (persisted only in-view), mirroring the macOS defaults.
    var workSeconds by remember { mutableIntStateOf(30) }
    var restSeconds by remember { mutableIntStateOf(15) }
    var rounds by remember { mutableIntStateOf(8) }

    val plan = remember(workSeconds, restSeconds, rounds) { IntervalPlan(workSeconds, restSeconds, rounds) }
    var timer by remember(plan) { mutableStateOf(IntervalTimer()) }
    val elapsed = timer.elapsed
    val totalPlanned = plan.duration
    val running = timer.mode == IntervalMode.Running
    val isFinished = timer.mode == IntervalMode.Finished
    val phase = when {
        isFinished -> IntervalPhase.Done
        plan.isRest(elapsed) -> IntervalPhase.Rest
        else -> IntervalPhase.Work
    }
    val currentRound = plan.round(elapsed)
    val remaining = plan.remaining(elapsed)
    val phaseDuration = if (phase == IntervalPhase.Rest) restSeconds else workSeconds
    val intervalProgress = 1.0 - remaining.toDouble() / phaseDuration
    val sessionProgress = elapsed.toDouble() / totalPlanned

    fun buzz(loops: Int) {
        if (live.bonded) vm.buzz(loops, HapticPrefs.INTERVALS)
    }

    LaunchedEffect(plan, running) {
        while (timer.mode == IntervalMode.Running) {
            delay(1000)
            if (timer.mode != IntervalMode.Running) break
            val before = timer.elapsed
            if (plan.remaining(before) in 1..3) buzz(1)
            timer = timer.tick(plan)
            when {
                timer.mode == IntervalMode.Finished -> buzz(5)
                plan.round(timer.elapsed) != plan.round(before) -> buzz(3)
                plan.isRest(timer.elapsed) != plan.isRest(before) -> buzz(1)
            }
        }
    }

    // The active phase's colour world: WORK → Effort (amber), REST → Rest (periwinkle), DONE → green.
    val phaseColor = when (phase) {
        IntervalPhase.Work -> Palette.effortColor
        IntervalPhase.Rest -> Palette.restColor
        IntervalPhase.Done -> Palette.statusPositive
    }
    // Deep→bright ramp for the active phase, fed to the hero progress BevelGauge's arc.
    val phaseStops = when (phase) {
        IntervalPhase.Work -> Palette.effortGradientStops
        IntervalPhase.Rest -> Palette.restGradientStops
        IntervalPhase.Done -> listOf(
            0f to Palette.statusPositive.copy(alpha = 0.6f),
            1f to Palette.statusPositive,
        )
    }
    // PERF (#707): lazy scaffold — each of the four cards is one `item { }`. The running timer ticks
    // `remaining` once a second at body scope; in a LazyColumn that tick only recomposes the VISIBLE items
    // (the heavy per-second BevelGauge hero) and off-screen cards (config) don't recompose or get
    // semantics-walked. Order/spacing unchanged (LazyColumn reproduces the eager `spacedBy(20.dp)`).
    LazyScreenScaffold(
        title = uiString(R.string.l10n_intervals_screen_interval_timer_1d703deb),
        subtitle = "Silent haptic HIIT - the strap buzzes the transitions",
    ) {
        // --- Status row ---
        item {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (live.bonded) {
                StatePill("Buzz cues on", tone = StrandTone.Positive)
            } else {
                StatePill("Connect strap for buzz cues", tone = StrandTone.Warning)
            }
            Spacer(Modifier.weight(1f))
            when {
                running -> StatePill("Running", tone = StrandTone.Accent, pulsing = true)
                isFinished -> StatePill("Complete", tone = StrandTone.Positive)
                timer.mode == IntervalMode.Ready -> StatePill("Ready", tone = StrandTone.Neutral, showsDot = false)
                else -> StatePill("Paused", tone = StrandTone.Neutral, showsDot = false)
            }
        }
        }

        // --- Stage hero: the immersive timer face over a scenic Effort backdrop ---
        item {
        // The running timer is the hero — a layered-ring BevelGauge of the phase progress, glowing
        // in the active phase's world (WORK → effort amber, REST → rest periwinkle), on a frosted
        // Effort-tinted card over a starfield. The countdown is the gauge's centred numeral.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.cardRadius)),
        ) {
            ScenicHeroBackground(modifier = Modifier.matchParentSize(), domain = DomainTheme.Effort)
            NoopCard(padding = 24.dp, tint = phaseColor) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    // Phase chip + round chip line — both frosted.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhaseChip(label = phase.label, color = phaseColor)
                        Spacer(Modifier.weight(1f))
                        RoundChip(currentRound = min(currentRound, rounds), rounds = rounds)
                    }

                    // The hero progress gauge with the countdown at its centre.
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        BevelGauge(
                            fraction = if (isFinished) 1.0 else intervalProgress,
                            stops = phaseStops,
                            tipColor = phaseColor,
                            numberText = if (isFinished) "✓" else remaining.toString(),
                            captionText = if (isFinished) "SESSION DONE" else "SECONDS",
                            diameter = 240.dp,
                            lineWidth = 18.dp,
                        )
                    }

                    // Controls.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = {
                                val opening = timer.mode == IntervalMode.Ready || timer.mode == IntervalMode.Finished
                                timer = timer.toggle()
                                if (opening) buzz(3)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Palette.accent,
                                contentColor = Palette.surfaceBase,
                            ),
                        ) {
                            Icon(
                                if (running) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(
                                if (running) "Pause" else if (isFinished) "Restart" else if (timer.mode == IntervalMode.Paused) uiString(R.string.workout_action_resume) else "Start",
                                style = NoopType.headline,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                timer = IntervalTimer()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = timer.mode != IntervalMode.Ready,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Palette.textSecondary,
                            ),
                        ) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                            Text(uiString(R.string.l10n_intervals_screen_reset_44c57abd), style = NoopType.headline)
                        }
                    }

                    if (!live.bonded) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Vibration,
                                contentDescription = null,
                                tint = Palette.textTertiary,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                uiString(R.string.l10n_intervals_screen_bond_your_strap_on_the_live_d799cbc2),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
        }

        // --- Overview card: elapsed / planned ---
        item {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Overline("Session")
                    Spacer(Modifier.weight(1f))
                    Text(
                        uiString(R.string.l10n_intervals_screen_timestring_elapsed_timestring_totalplanned_7b68f8d7, timeString(elapsed), timeString(totalPlanned)),
                        style = NoopType.bodyNumber,
                        color = Palette.textPrimary,
                    )
                }

                // Slim total-session progress bar — filled with the Effort world gradient.
                val animatedSession by animateFloatAsState(
                    targetValue = sessionProgress.toFloat(),
                    animationSpec = tween(900, easing = Motion.easeOut),
                    label = "session",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.surfaceInset),
                ) {
                    if (animatedSession > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedSession)
                                .height(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(*Palette.effortGradientStops.toTypedArray()),
                                ),
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    OverviewStat(Modifier.weight(1f), "Work", "${workSeconds}s", Palette.effortColor)
                    OverviewStat(Modifier.weight(1f), "Rest", "${restSeconds}s", Palette.restColor)
                    OverviewStat(Modifier.weight(1f), "Rounds", rounds.toString(), Palette.textPrimary)
                    OverviewStat(
                        Modifier.weight(1f), "Remaining",
                        timeString(max(0, totalPlanned - elapsed)), Palette.textSecondary,
                    )
                }
            }
        }
        }

        // --- Config card ---
        item {
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Overline("Configure")
                ConfigStepper(
                    title = uiString(R.string.l10n_intervals_screen_work_00040bab), unit = "sec", value = workSeconds,
                    range = 5..600, step = 5, tint = Palette.effortColor, enabled = !running,
                    onChange = { workSeconds = it },
                )
                Divider()
                ConfigStepper(
                    title = uiString(R.string.l10n_intervals_screen_rest_b79e5f48), unit = "sec", value = restSeconds,
                    range = 5..600, step = 5, tint = Palette.restColor, enabled = !running,
                    onChange = { restSeconds = it },
                )
                Divider()
                ConfigStepper(
                    title = uiString(R.string.l10n_intervals_screen_rounds_ceeac4ac), unit = null, value = rounds,
                    range = 1..30, step = 1, tint = Palette.textPrimary, enabled = !running,
                    onChange = { rounds = it },
                )
                if (running) {
                    Text(
                        uiString(R.string.l10n_intervals_screen_pause_to_change_work_rest_or_f54f9cc5),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
        }
    }
}

// MARK: - Phase + round chips (frosted pills, mirror IntervalTimerView.phaseChip / .roundChip)

/** Frosted phase pill (WORK / REST / DONE) tinted to the active world. */
@Composable
private fun PhaseChip(label: String, color: Color) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = NoopType.number(15f, weight = androidx.compose.ui.text.font.FontWeight.Bold)
            .copy(letterSpacing = 2.sp),
        color = color,
        modifier = Modifier
            .clip(shape)
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Frosted round chip — "ROUND n / N". */
@Composable
private fun RoundChip(currentRound: Int, rounds: Int) {
    val shape = RoundedCornerShape(50)
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .clip(shape)
            .background(Palette.surfaceInset)
            .border(1.dp, Palette.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Overline("Round")
        Spacer(Modifier.width(6.dp))
        Text(currentRound.toString(), style = NoopType.number(18f), color = Palette.textPrimary)
        Spacer(Modifier.width(2.dp))
        Text(uiString(R.string.l10n_intervals_screen_rounds_1c13358c, rounds), style = NoopType.number(18f), color = Palette.textTertiary)
    }
}

// MARK: - Overview stat cell (mirrors IntervalTimerView.overviewStat)

@Composable
private fun OverviewStat(
    modifier: Modifier,
    label: String,
    value: String,
    color: Color,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label.uppercase(), style = NoopType.footnote, color = Palette.textTertiary)
        Text(value, style = NoopType.number(18f), color = color, maxLines = 1)
    }
}

// MARK: - Config stepper (mirrors IntervalTimerView.configStepper)
//
// A titled row with the current value and -/+ steppers, clamped to `range`. Disabled
// (dimmed, non-interactive) while a session is running.

@Composable
private fun ConfigStepper(
    title: String,
    unit: String?,
    value: Int,
    range: IntRange,
    step: Int,
    tint: Color,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    val dim = if (enabled) 1f else Palette.disabledOpacity
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NoopType.headline, color = Palette.textPrimary.copy(alpha = dim))
            Text(
                uiString(
                    R.string.intervals_range_step,
                    range.first,
                    range.last,
                    unit?.let { " $it" } ?: "",
                    step,
                ),
                style = NoopType.footnote,
                color = Palette.textTertiary.copy(alpha = dim),
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.toString(),
                style = NoopType.number(24f),
                color = tint.copy(alpha = dim),
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(
                    unit,
                    style = NoopType.caption,
                    color = Palette.textTertiary.copy(alpha = dim),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        StepperButton(
            icon = Icons.Filled.Remove,
            description = "Decrease $title",
            enabled = enabled && value > range.first,
            tint = tint,
        ) { onChange((value - step).coerceIn(range.first, range.last)) }
        Spacer(Modifier.width(8.dp))
        StepperButton(
            icon = Icons.Filled.Add,
            description = "Increase $title",
            enabled = enabled && value < range.last,
            tint = tint,
        ) { onChange((value + step).coerceIn(range.first, range.last)) }
    }
}

@Composable
private fun StepperButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val content = if (enabled) tint else Palette.textTertiary.copy(alpha = Palette.disabledOpacity)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = content,
            disabledContentColor = Palette.textTertiary.copy(alpha = Palette.disabledOpacity),
        ),
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = description },
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

// MARK: - Divider hairline

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Palette.hairline),
    )
}

// MARK: - Formatting

private fun timeString(seconds: Int): String {
    val s = max(0, seconds)
    return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
