package com.noop.ui

import com.noop.data.DailyMetric
import java.time.LocalDate

// Fork-local (ronboger): extracted out of TodayScreen.kt on the upstream merge. Upstream rebuilt its
// Today screen around its own carry-over helpers (TodayMetricsLogic.kt); this resolver stays because
// ChargeDetailRoute — a fork-only screen — shares it with the Today hero so the ring and the
// breakdown page cannot desync.

/**
 * Day-key + Charge carry-over resolution shared by [TodayScreen] and [ChargeDetailRoute].
 * Pure + unit-tested ([ChargeDayContextTest]) so a one-sided edit cannot desync the hero ring from
 * its breakdown (#543/#547). [todayDate] is the logical-day anchor (rolls at 04:00);
 * [calendarToday] is the wall-clock calendar day used for the #547 carry-over upper bound.
 */
internal data class ChargeDayContext(
    val selectedDay: LocalDate,
    val selectedDayKey: String,
    val displayMetric: DailyMetric?,
    val recoveryCalibration: Int?,
    val carryOverTodayKey: String,
    val carriedDay: DailyMetric?,
    val showReadiness: Boolean,
)

/** See [ChargeDayContext]. */
internal fun chargeDayContext(
    today: DailyMetric?,
    days: List<DailyMetric>,
    dayOffset: Int,
    todayDate: LocalDate = logicalDayNow(),
    calendarToday: LocalDate = LocalDate.now(),
    // Threaded on the upstream merge: `recoveryCalibrationNights` now folds the persisted
    // "Recalibrate HRV baseline" epoch, so N counts the same nights the recovery engine does.
    // 0 = never recalibrated, which is what every existing caller/test meant before the change.
    hrvBaselineEpoch: Double = 0.0,
): ChargeDayContext {
    val selectedDay = todayDate.minusDays(dayOffset.toLong())
    // At offset 0 follow the resolver's today?.day (incl. pre-04:00 local-calendar case #304);
    // past offsets use the logical key directly.
    val selectedDayKey =
        if (dayOffset == 0) today?.day ?: selectedDay.toString() else selectedDay.toString()
    val historicalMetric = days.lastOrNull { it.day == selectedDayKey }
    val displayMetric = if (dayOffset == 0) today ?: historicalMetric else historicalMetric
    val recoveryCalibration = if (dayOffset == 0) {
        recoveryCalibrationNights(days, displayMetric?.recovery != null, hrvBaselineEpoch)
    } else {
        null
    }
    // #547: LATER of logical today and calendar today so just-after-midnight carry-over is kept
    // while future-dated strap-clock rows are still excluded.
    val carryOverTodayKey = maxOf(todayDate.toString(), calendarToday.toString())
    val carriedDay = lastScoredRecoveryDay(
        days = days,
        selectedDayKey = selectedDayKey,
        isToday = dayOffset == 0,
        todayScored = displayMetric?.recovery != null,
        isCalibrating = recoveryCalibration != null,
        today = carryOverTodayKey,
    )
    return ChargeDayContext(
        selectedDay = selectedDay,
        selectedDayKey = selectedDayKey,
        displayMetric = displayMetric,
        recoveryCalibration = recoveryCalibration,
        carryOverTodayKey = carryOverTodayKey,
        carriedDay = carriedDay,
        showReadiness = dayOffset == 0,
    )
}
