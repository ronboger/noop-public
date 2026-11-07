package com.noop.ui

/**
 * Pure policy for Today hero ring label taps.
 *
 * When a navigation callback is wired (Charge → detail, Rest → Sleep, Effort → Workouts), the
 * UPPERCASE label under the ring opens that screen — not the "how scores work" guide. The guide
 * remains on the first-run card and Settings. Unbound callers (previews) fall back to the guide.
 */
internal enum class HeroRingLabelTarget {
    Navigate,
    ScoreGuide,
}

/**
 * @param hasNavigation true when AppRoot (or a test) bound onOpenCharge / onOpenSleep / onOpenEffort.
 */
internal fun heroRingLabelTarget(hasNavigation: Boolean): HeroRingLabelTarget =
    if (hasNavigation) HeroRingLabelTarget.Navigate else HeroRingLabelTarget.ScoreGuide
