package com.noop.ui

import com.noop.R
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentCompositeKeyHash
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

// MARK: - NoopMotion — the "Design Reset" motion set (WHOOP design language, 2026-06-22)
//
// Compose port of StrandDesign/NoopMotion.swift. The house motion language for the
// WHOOP-flavoured redesign: smooth, snappy, almost no bounce. Beauty is in the restraint —
// type, spacing and a single confident settle, NOT effects. NO glow here, nothing that
// pulses or loops. Three things screens reach for constantly:
//
//   • a refined spring set (screen / card / value)
//   • `CountUpText` — big scores/metrics tick up to their new value
//   • `Modifier.staggeredAppear(index)` — list/grid items fade + rise in, once, in sequence
//   • `softCardEnter` / `softCardExit` — card insert/remove (asymmetric; exit softer than enter)
//
// Every helper is public, GPU-cheap (alpha / translation / scale only), and honours Reduce
// Motion: under it, animations collapse to their final frame instantly with no offset, scale
// or counting. Android has no per-app accessibility flag equivalent to iOS
// `accessibilityReduceMotion`, so we read the system animator scale
// (`Settings.Global.ANIMATOR_DURATION_SCALE` == 0 → "Remove animations" / animations off),
// the canonical Android signal that the user has opted out of motion.

// MARK: - Reduce-motion detection (Android system signal)

/**
 * True when the user has disabled system animations (Settings → Accessibility → "Remove
 * animations", or Developer options → Animator duration scale = Off). The closest Android
 * analogue to iOS `accessibilityReduceMotion`: when on, every NOOP motion helper degrades to
 * its final frame instantly.
 *
 * Read once per composition from `Settings.Global.ANIMATOR_DURATION_SCALE`; previews
 * (inspection mode) always report `false` so design tooling shows the animated state.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        }.getOrDefault(1f)
        scale == 0f
    }
}

/**
 * Process-wide battery-saver state, registered once and read by every animating surface.
 *
 * One receiver, not one per composable: the liquid primitives alone have 26 call sites, and a
 * `DisposableEffect` inside each would register and unregister a `BroadcastReceiver` as gauges scroll in
 * and out of composition — churn, in the change that exists to remove per-frame work. The Apple twin is a
 * singleton (`LiquidPowerMonitor.shared`) for the same reason, and like it this is never unregistered:
 * the state is process-lifetime and so is the observer.
 *
 * Backed by Compose snapshot state, so a write from the receiver recomposes exactly the surfaces that
 * read it.
 */
private object PowerSaveMonitor {
    val isSaving = mutableStateOf(false)
    private var registered = false

    /** Idempotent; safe to call from every composition. Called on the main thread by construction. */
    fun ensureStarted(context: Context) {
        if (registered) return
        val app = context.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        isSaving.value = pm.isPowerSaveMode
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(unused: Context?, intent: Intent?) { isSaving.value = pm.isPowerSaveMode }
        }
        // ACTION_POWER_SAVE_MODE_CHANGED is a protected system broadcast; RECEIVER_NOT_EXPORTED stops
        // other apps sending it while the system still delivers. Same shape as the Bluetooth state
        // receiver in WhoopConnectionService.
        val ok = runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.isSuccess
        if (ok) registered = true
    }
}

/**
 * True while the system is in battery saver. The Android analogue of iOS `isLowPowerModeEnabled`, which
 * [rememberPoseStill] pairs with reduce-motion so the continuously-animating surfaces pose still (#909).
 *
 * Live, not read-once: toggling battery saver takes effect without leaving the screen, matching the Apple
 * side's `NSProcessInfoPowerStateDidChange` observer. Previews (inspection mode) always report `false`,
 * like [rememberReduceMotion], so design tooling shows the animated state.
 */
@Composable
fun rememberPowerSaveMode(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    // `SideEffect`, not `remember`: registering the receiver is a side effect, and composition may be
    // restarted or abandoned, so it does not belong in a slot. `ensureStarted` is guarded by its own
    // `registered` flag, making the per-composition repeat a single boolean check — and it publishes
    // `isSaving` synchronously, so the state settles on the frame after first composition.
    SideEffect { PowerSaveMonitor.ensureStarted(context) }
    return PowerSaveMonitor.isSaving.value
}

/**
 * Process-wide live mirror of the in-app "Reduce motion in NOOP" preference. SharedPreferences keeps
 * listeners weakly, so this object retains both the preferences and listener for the process lifetime.
 * One observer feeds Compose snapshot state to every looping surface, avoiding a listener per composable.
 */
private object QuietMotionMonitor {
    val enabled = mutableStateOf(false)
    private var preferences: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Idempotent; called on the main thread from composition. */
    fun ensureStarted(context: Context) {
        if (preferences != null) return
        val prefs = NoopPrefs.of(context.applicationContext)
        enabled.value = prefs.getBoolean(NoopPrefs.KEY_QUIET_MOTION, false)
        val observer = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == NoopPrefs.KEY_QUIET_MOTION) {
                enabled.value = changed.getBoolean(NoopPrefs.KEY_QUIET_MOTION, false)
            }
        }
        preferences = prefs
        listener = observer
        prefs.registerOnSharedPreferenceChangeListener(observer)
    }
}

/** The live in-app quiet-motion preference. Previews stay animated. */
@Composable
fun rememberQuietMotion(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    // `remember` may not return Unit (lint RememberReturnType). The throwaway Boolean keeps the
    // start running exactly ONCE per context, during composition, before the read below — so the
    // first frame already reflects the stored preference.
    remember(context) { QuietMotionMonitor.ensureStarted(context); true }
    return QuietMotionMonitor.enabled.value
}

/**
 * True when a continuously-animating surface should render its single posed frame instead of running
 * a frame loop — the user turned system animations off, the system is in battery saver, or the in-app
 * quiet-motion preference is enabled.
 *
 * **For frame loops only.** The one-shot helpers in this file (`CountUpText`, `staggeredAppear`, the
 * 160 ms tweens in `LiquidPrimitives`) keep asking [rememberReduceMotion] on its own: those settle and
 * stop, so they are not the cost battery saver is asking to avoid, and the Apple change this mirrors
 * (#909) gated only its `TimelineView`s for the same reason.
 *
 * The measured cost on the Apple side was ~18% of a CPU core for the idle Today screen, identical in
 * Debug and Release because the per-frame work is canvas drawing rather than arithmetic. Android runs
 * the same picture through `withFrameNanos` / `rememberInfiniteTransition`, so the shape holds even
 * though the number is unmeasured here.
 */
@Composable
fun rememberPoseStill(): Boolean =
    rememberReduceMotion() || rememberPowerSaveMode() || rememberQuietMotion()

// MARK: - NoopMotion springs / tokens

object NoopMotion {

    // MARK: Springs — smooth, snappy, minimal bounce.
    // SwiftUI `spring(response:dampingFraction:)` maps to Compose `spring(dampingRatio, stiffness)`
    // where stiffness ≈ (2π / response)² and dampingRatio == dampingFraction.

    /** Screen-level spring — page pushes, sheet/tab swaps. response 0.46 / damping 0.88. */
    fun <T> screen(): AnimationSpec<T> =
        spring(dampingRatio = 0.88f, stiffness = stiffnessFor(0.46f))

    /** Card-level spring — card insert/remove, row reflow, expand/collapse. response 0.40 / damping 0.85. */
    fun <T> card(): AnimationSpec<T> =
        spring(dampingRatio = 0.85f, stiffness = stiffnessFor(0.40f))

    /** Value-level spring — number ticks, gauge fraction, chip/state changes. response 0.34 / damping 0.90. */
    fun <T> value(): AnimationSpec<T> =
        spring(dampingRatio = 0.90f, stiffness = stiffnessFor(0.34f))

    /** Convert a SwiftUI spring `response` (perceptual duration, seconds) to a Compose `stiffness`. */
    private fun stiffnessFor(response: Float): Float {
        val omega = (2.0 * Math.PI) / response.toDouble()
        return (omega * omega).toFloat()
    }

    // MARK: Stagger

    /** Per-item delay (ms) for a staggered list/grid reveal. Index 0 fires immediately; each
     *  subsequent item waits `min(index, maxStaggerIndex) * staggerMs`. Mirrors iOS 0.10s. */
    const val staggerMs: Int = 100

    /**
     * Cap on the stagger index multiplier so long lists never wait more than
     * `maxStaggerIndex * staggerMs` (400ms) before the last item starts. Mirrors iOS
     * `NoopMotion.maxStaggerIndex`.
     */
    const val maxStaggerIndex: Int = 4

    /** Ceiling delay (ms) for a staggered appear item. */
    val maxStaggerDelayMs: Int get() = maxStaggerIndex * staggerMs

    /** Delay (ms) for a given stagger index, clamped so long lists share the tail beat. */
    fun staggerDelayMs(index: Int): Int =
        index.coerceAtLeast(0).coerceAtMost(maxStaggerIndex) * staggerMs

    /** The pre-reveal vertical offset (dp) for a staggered/appear item (rises UP into place). */
    val riseOffset: Dp = 12.dp

    /** Soft-card enter rise (dp) — matches iOS softCard insertion offset. */
    val softCardEnterOffset: Dp = 12.dp

    /** Soft-card exit rise (dp, negative direction) — matches iOS softCard removal offset. */
    val softCardExitOffset: Dp = 8.dp

    /** Soft-card enter scale — hair of scale on insert only (exit has no scale drama). */
    const val softCardEnterScale: Float = 0.98f

    /** Returns [spec] normally, or `null` when [reduced] (so the call site can snap instantly). */
    fun <T> gated(spec: AnimationSpec<T>, reduced: Boolean): AnimationSpec<T>? =
        if (reduced) null else spec
}

// MARK: - CountUpText
//
// Animates a numeric value counting up (or down) to its latest value whenever `value`
// changes, and on first appear (from 0 → value). Reduce Motion → final value shown instantly.

/**
 * A text view whose number animates from its previous value to the new one. Use for the big
 * scores / hero metric read-outs. Mirrors iOS `CountUpText`.
 *
 * ```
 * CountUpText(
 *     value = score,
 *     format = { "${it.roundToInt()}" },
 *     style = NoopType.display(72f),
 *     color = Palette.textPrimary,
 * )
 * ```
 *
 * @param value the number to display / animate to.
 * @param format maps the (interpolated) number to its display string — round, clamp, add units here.
 * @param style the text style (e.g. `NoopType.display(72f)`).
 * @param color the text colour (e.g. `Palette.textPrimary`).
 * @param spec the count-up curve. Defaults to `NoopMotion.value()`.
 */
@Composable
fun CountUpText(
    value: Double,
    format: (Double) -> String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    spec: AnimationSpec<Float> = NoopMotion.value(),
) {
    val reduced = rememberReduceMotion()

    // First composition starts the run from 0 → value (animated) or snaps (reduced); after that
    // the target tracks `value` and `animateFloatAsState` interpolates between commits.
    var target by remember { mutableStateOf(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value, reduced) { target = value.toFloat() }

    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduced) tween(durationMillis = 0) else spec,
        label = uiString(R.string.l10n_noop_motion_countuptext_749c73fb),
    )
    val shown = if (reduced) value.toFloat() else animated

    Text(
        text = format(shown.toDouble()),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

// MARK: - Stagger session store
//
// Per-instance rememberSaveable dies when a tab/screen leaves the composition, so
// returning to Settings re-ran the whole slideshow. This process-scoped set records
// call-site keys that have already played so re-entry is instant for the rest of the
// process lifetime. Mirrors iOS `StaggerSession`.

object StaggerSession {
    private val played = ConcurrentHashMap.newKeySet<String>()

    fun hasPlayed(key: String): Boolean = played.contains(key)

    fun markPlayed(key: String) {
        played.add(key)
    }

    /** Clears the session store — for DEBUG / instrumentation that needs to re-run a reveal. */
    fun reset() {
        played.clear()
    }
}

// MARK: - Staggered appear
//
// Fade-in + 12dp rise, sequenced by `index` (capped). Runs once per call-site key for the
// process lifetime (session-scoped), so tab re-entry / navigation pops don't re-slideshow.
// rememberSaveable still guards recomposition / config change within a living view.
// Reduce Motion → instant, no offset.

/**
 * Fade-in + 12dp rise on first appearance, delayed by
 * `min(index, maxStaggerIndex) * 100ms` (≤400ms) for a sequenced list/grid reveal.
 *
 * **Session-scoped by default:** each call site (`currentCompositeKeyHash` + `index`) plays
 * once per process. Returning to a tab/screen does not re-run the stagger. Pass
 * [sessionScoped] = false (or call [StaggerSession.reset]) to force a replay.
 *
 * **Lazy lists:** the default key is Compose's [currentCompositeKeyHash], which can change
 * when a `LazyColumn`/`LazyRow` reuses slots. Recycled rows may therefore re-stagger on
 * scroll-in (often desirable). To suppress that and treat a row as "already played" after
 * the first reveal, pass an explicit stable [sessionKey] (e.g. `"settings-row-$id"`).
 * iOS keys off `#fileID:#line` instead, so lazy-list semantics intentionally differ.
 *
 * @param index position in the sequence (0 = first / no delay; indices above
 *  [NoopMotion.maxStaggerIndex] share the same delay as the cap).
 * @param isVisible set `false` to opt an element out (it stays fully shown).
 * @param sessionScoped when true, skip if this call site already played this process.
 * @param sessionKey optional override for the call-site identity (defaults to composite key hash).
 */
fun Modifier.staggeredAppear(
    index: Int,
    isVisible: Boolean = true,
    sessionScoped: Boolean = true,
    sessionKey: String? = null,
): Modifier = composed {
    val reduced = rememberReduceMotion()
    val compositeKey = currentCompositeKeyHash
    val fullKey = "${sessionKey ?: "ck$compositeKey"}#$index"
    val sessionDone = sessionScoped && StaggerSession.hasPlayed(fullKey)

    // Flips true once we've appeared (or immediately under Reduce Motion / session skip).
    // rememberSaveable so a recompose / config change never replays the entrance.
    var hasAppeared by rememberSaveable(fullKey) { mutableStateOf(sessionDone) }

    val progress by animateFloatAsState(
        targetValue = if (!isVisible || hasAppeared || reduced || sessionDone) 1f else 0f,
        animationSpec = if (reduced || sessionDone) tween(0) else NoopMotion.card(),
        label = uiString(R.string.l10n_noop_motion_staggeredappear_7e1a3335),
    )

    LaunchedEffect(isVisible, reduced, fullKey) {
        if (!isVisible || hasAppeared) return@LaunchedEffect
        if (sessionDone) {
            hasAppeared = true
            return@LaunchedEffect
        }
        if (reduced) {
            hasAppeared = true
            if (sessionScoped) StaggerSession.markPlayed(fullKey)
        } else {
            // Mark as soon as the reveal starts so a mid-animation remount doesn't re-fire.
            if (sessionScoped) StaggerSession.markPlayed(fullKey)
            delay(NoopMotion.staggerDelayMs(index).toLong())
            hasAppeared = true
        }
    }

    val rise = with(LocalDensity.current) { NoopMotion.riseOffset.toPx() }
    this
        .alpha(if (!isVisible) 1f else progress)
        .graphicsLayer { translationY = if (!isVisible) 0f else (1f - progress) * rise }
}

// MARK: - Soft card enter / exit
//
// Mirrors iOS `AnyTransition.softCard(reduced:)`: enter is opacity + scale(0.98) + rise 12dp;
// exit is softer (opacity + fixed −8dp translateY only — no scale drama). Pass [reduced] from
// [rememberReduceMotion] so Reduce Motion degrades to a plain fade (or instant).

/**
 * House card insert transition for [androidx.compose.animation.AnimatedVisibility] /
 * [androidx.compose.animation.AnimatedContent]. Mirrors iOS softCard insertion.
 */
@Composable
fun softCardEnter(reduced: Boolean = rememberReduceMotion()): EnterTransition {
    if (reduced) return fadeIn(animationSpec = tween(0))
    val risePx = with(LocalDensity.current) { NoopMotion.softCardEnterOffset.roundToPx() }
    return fadeIn(animationSpec = tween(durationMillis = 220)) +
        scaleIn(
            initialScale = NoopMotion.softCardEnterScale,
            animationSpec = tween(durationMillis = 220),
        ) +
        slideInVertically(
            animationSpec = tween(durationMillis = 220),
            initialOffsetY = { risePx },
        )
}

/**
 * House card remove transition — softer than enter (no scale). Mirrors iOS softCard removal.
 */
@Composable
fun softCardExit(reduced: Boolean = rememberReduceMotion()): ExitTransition {
    if (reduced) return fadeOut(animationSpec = tween(0))
    val risePx = with(LocalDensity.current) { NoopMotion.softCardExitOffset.roundToPx() }
    return fadeOut(animationSpec = tween(durationMillis = 160)) +
        slideOutVertically(
            animationSpec = tween(durationMillis = 160),
            targetOffsetY = { -risePx },
        )
}

/**
 * Show/hide a card-like surface with the house soft-card enter/exit. Drop-in for
 * dismissible notes and conditional suggestion cards. Mirrors iOS
 * `.softCardTransition()` driven by an `if`/`withAnimation` block.
 *
 * Prefer this over raw [AnimatedVisibility] so enter/exit stay locked to the design tokens.
 *
 * @param animate when false, appear/disappear with no motion (use for day-swipe / data
 *  reload visibility flips that shouldn't feel busy). Dismiss/restore keeps [animate] true.
 */
@Composable
fun SoftCardVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (animate) softCardEnter() else EnterTransition.None,
        exit = if (animate) softCardExit() else ExitTransition.None,
    ) {
        // Box keeps a single measurable child for the transition layout.
        Box { content() }
    }
}

/**
 * Soft-card visibility that freezes [value] while exiting so the animating card never
 * flashes a newer state mid-exit (e.g. Calibrating → CarriedLastNight while sliding out).
 *
 * While [visible] is true, [value] tracks live. On the frame [visible] flips false, the
 * last value is held for the duration of the exit transition.
 */
@Composable
fun <T> SoftCardVisibility(
    visible: Boolean,
    value: T,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    var held by remember { mutableStateOf(value) }
    SideEffect {
        if (visible) held = value
    }
    SoftCardVisibility(visible = visible, modifier = modifier, animate = animate) {
        content(held)
    }
}
