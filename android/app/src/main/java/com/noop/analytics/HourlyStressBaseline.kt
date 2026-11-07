package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.RrInterval
import kotlin.math.sqrt

/*
 * HourlyStressBaseline.kt — the 30-day HOURLY baseline the intraday stress timeline can score
 * against, so the timeline and the daily Stress score speak the same language.
 *
 * The daily Stress score (the Stress screen's hero) z-scores today's overnight resting HR +
 * daily HRV against their trailing 30-day means. The intraday timeline (DaytimeStress)
 * originally z-scored each waking hour against TODAY's own typical hour — an honest "when did
 * the day run tense" shape, but one whose zero point is pinned at 1.5 no matter how calm the
 * day is versus the 30-day baseline, so a day the hero calls 1.1 LOW still showed a
 * ~1.5-centred timeline that read "higher than my stress".
 *
 * This helper closes that gap with a STORED baseline series: for each of the trailing
 * [windowDays] complete days, the bank holds one aggregate per waking hour (mean HR + RMSSD,
 * computed with the SAME bucketing and the SAME beat cleaning the live timeline uses). The
 * bank accrues PROSPECTIVELY — once a day completes, it can be banked only under the zone
 * journaled while that day was in progress, because the raw streams carry no capture timezone
 * and a day lived in another zone must never be bucketed under today's. Scoring an hour against
 * "my typical hour AT THAT TIME over the last 30 days" makes the timeline comparable to the hero
 * in both
 * directions: a calm day reads LOW on the line, a tense afternoon still reads HIGH.
 *
 * Two pure entry points:
 *   • [aggregateDay] — reduce a span of raw HR + R-R to bankable (day, hour) rows. The data
 *     layer persists these (Room `hourlyMetric` table); nothing here touches Room.
 *   • [build] — fold banked rows into a [Baseline] of per-hour-of-day reference stats. Returns
 *     null below [minBaselineDays] distinct days; the caller then keeps the within-day
 *     reference.
 *
 * APPROXIMATE and non-clinical, like the score it feeds.
 */
object HourlyStressBaseline {

    // MARK: - Tunables

    /** Complete days the baseline window spans (mirrors the daily score's 30-day baseline). */
    const val windowDays: Int = 30
    /**
     * Minimum distinct banked days before the hourly baseline is trusted at all. Below it the
     * timeline keeps its within-day reference: a brand-new install has nothing personal to
     * compare an hour against, and a 2-day "baseline" would re-create the noise problem the
     * spread floors exist to prevent.
     */
    const val minBaselineDays: Int = 7
    /**
     * Minimum banked observations of one hour-of-day before that hour uses the personal
     * reference for a signal. Below it the signal's z term drops out (the hour is scored on
     * whatever signal DOES have enough history; an hour with neither is skipped, never
     * invented).
     */
    const val minHourObservations: Int = 4

    // MARK: - Types

    /**
     * One banked (day, waking hour) aggregate — the pure twin of the Room `hourlyMetric` row.
     * Present only for hours that cleared [DaytimeStress.minHourHrSamples]; [rmssd] is null
     * when the hour's clean beats were too few (the same gate the live timeline applies).
     */
    data class DayHour(
        /** Local yyyy-MM-dd day key. */
        val day: String,
        /** Local hour-of-day (0–23); banked rows cover the waking window only. */
        val hour: Int,
        /** Mean HR over the hour (bpm). */
        val meanHr: Double,
        /** RMSSD over the hour's clean R-R (ms), or null. */
        val rmssd: Double?,
        /** HR samples behind [meanHr] (>= the scoring gate by construction). */
        val hrSamples: Int,
        /** Raw R-R beats seen in the hour (diagnostic; the cleaned set backs [rmssd]). */
        val rrSamples: Int,
    )

    /**
     * Per-hour-of-day reference stats across the baseline window's days. A null mean (with a 0
     * spread) marks "not enough history for this signal at this hour" — the scorer drops that
     * z term rather than inventing a reference.
     */
    data class HourStats(
        val meanHr: Double?,
        val sdHr: Double,
        val hrDays: Int,
        val meanRmssd: Double?,
        val sdRmssd: Double,
        val rmssdDays: Int,
    )

    /**
     * The built baseline: per-hour-of-day reference stats over the trailing [windowDays]
     * complete days. [days] is the number of distinct banked days that fed it
     * (>= [minBaselineDays] by construction).
     */
    class Baseline internal constructor(
        val days: Int,
        private val byHour: Map<Int, HourStats>,
    ) {
        /** Reference stats for one local hour-of-day, or null when that hour was never banked. */
        fun hour(hourOfDay: Int): HourStats? = byHour[hourOfDay]
    }

    // MARK: - Banking (raw span → rows)

    /**
     * Reduce a span of raw HR + R-R to bankable (day, hour) aggregates, bucketing with a FIXED
     * UTC offset — correct for a span that never crosses an offset change (the within-day read).
     * The banking pass uses the [offsetAt] overload so historical days straddling a DST
     * transition still bucket by their own wall clock.
     */
    fun aggregateDay(hr: List<HrSample>, rr: List<RrInterval>, tzOffsetSeconds: Long): List<DayHour> =
        aggregateDay(hr, rr) { tzOffsetSeconds }

    /**
     * Reduce a span of raw HR + R-R to bankable (day, hour) aggregates. Buckets on the LOCAL
     * clock exactly like [DaytimeStress.analyze], but with each sample's offset resolved at ITS
     * instant ([offsetAt]: unix seconds → seconds east of UTC), so a span crossing a
     * daylight-saving transition (or a day banked long after the fact) lands every sample in
     * the hour-of-day it was actually lived in — the hour-of-day alignment the baseline exists
     * for. Only WAKING hours produce rows, and only when they clear the same
     * [DaytimeStress.minHourHrSamples] gate, so a banked hour is byte-comparable to the hour
     * the live timeline will score. RMSSD comes from the SAME [HrvAnalyzer] cleaning — ectopic
     * beats can't fabricate baseline variability either.
     *
     * The span may cover one day or many; rows carry their own local day key, so a partial span
     * simply yields fewer rows. Bank COMPLETE days only — a half day's hours are not a
     * reference for anything (the data-layer banking pass enforces this).
     */
    fun aggregateDay(
        hr: List<HrSample>,
        rr: List<RrInterval>,
        offsetAt: (unixSeconds: Long) -> Long,
    ): List<DayHour> {
        val hrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in hr) {
            val bucket = DaytimeStress.floorDiv(s.ts + offsetAt(s.ts), DaytimeStress.bucketSeconds) *
                DaytimeStress.bucketSeconds
            hrByBucket.getOrPut(bucket) { ArrayList() }.add(s.bpm.toDouble())
        }
        val rrByBucket = HashMap<Long, MutableList<Double>>()
        for (s in rr) {
            val bucket = DaytimeStress.floorDiv(s.ts + offsetAt(s.ts), DaytimeStress.bucketSeconds) *
                DaytimeStress.bucketSeconds
            rrByBucket.getOrPut(bucket) { ArrayList() }.add(s.rrMs.toDouble())
        }

        val rows = ArrayList<DayHour>(hrByBucket.size)
        for (b in hrByBucket.keys.sorted()) {
            if (!DaytimeStress.isWakingHour(b)) continue
            val samples = hrByBucket[b] ?: continue
            if (samples.size < DaytimeStress.minHourHrSamples) continue
            val hourOfDay = (DaytimeStress.floorDiv(b, DaytimeStress.bucketSeconds) % 24).toInt()
            val beats = rrByBucket[b] ?: emptyList()
            rows.add(
                DayHour(
                    day = localDayKey(b),
                    hour = hourOfDay,
                    meanHr = samples.sum() / samples.size,
                    rmssd = HrvAnalyzer.analyzeRaw(beats).rmssd,
                    hrSamples = samples.size,
                    rrSamples = beats.size,
                ),
            )
        }
        return rows
    }

    // MARK: - Baseline (rows → reference)

    /**
     * Fold banked rows into the hourly baseline. Only COMPLETE days count: [todayDay] (the
     * local day still in progress) is excluded, and the window keeps the most recent
     * [windowDays] distinct banked days before it — the same "up to 30 days ending yesterday"
     * window the daily score's baseline uses. Returns null when fewer than [minBaselineDays]
     * distinct days are banked (the caller then falls back to the within-day reference).
     *
     * Spreads are population SDs ACROSS DAYS for one hour-of-day, delivered raw: the scorer
     * applies the same physiological floors ([DaytimeStress.minHrSpreadBpm] /
     * [DaytimeStress.minRmssdSpreadMs]) the within-day reference uses, so a signal whose
     * personal history is tighter than the floor can't have its noise stretched across the
     * scale.
     */
    fun build(rows: List<DayHour>, todayDay: String): Baseline? {
        val days = rows.map { it.day }.filter { it < todayDay }
            .distinct().sorted().takeLast(windowDays)
        if (days.size < minBaselineDays) return null
        val daySet = days.toHashSet()

        val byHour = HashMap<Int, MutableList<DayHour>>()
        for (r in rows) {
            if (r.day !in daySet) continue
            byHour.getOrPut(r.hour) { ArrayList() }.add(r)
        }

        val stats = HashMap<Int, HourStats>(byHour.size)
        for ((hour, rs) in byHour) {
            val hrVals = rs.map { it.meanHr }
            val rrVals = rs.mapNotNull { it.rmssd }
            stats[hour] = HourStats(
                meanHr = if (hrVals.size >= minHourObservations) mean(hrVals) else null,
                sdHr = if (hrVals.size >= minHourObservations) std(hrVals) else 0.0,
                hrDays = hrVals.size,
                meanRmssd = if (rrVals.size >= minHourObservations) mean(rrVals) else null,
                sdRmssd = if (rrVals.size >= minHourObservations) std(rrVals) else 0.0,
                rmssdDays = rrVals.size,
            )
        }
        // A baseline that can only reference a couple of hours would leave the rest of the
        // waking day unscored — worse than the within-day fallback it replaces (see
        // [minUsableHours]). Sparse coverage → unavailable, not partial.
        if (stats.values.count { it.meanHr != null } < minUsableHours) return null
        return Baseline(days.size, stats)
    }

    // MARK: - Banking coverage (which days need (re-)banking)

    /**
     * Minimum hours-of-day with a usable personal HR reference before the baseline is used at
     * all (at least half the waking window). A baseline built from days that only ever covered
     * a couple of waking hours would leave every OTHER hour unscored — strictly worse than the
     * within-day reference, which scores them. Below this the build returns null and the
     * timeline keeps the within-day fallback.
     */
    const val minUsableHours: Int = 8

    /**
     * A day's raw-data fingerprint: how many HR and R-R rows the day-window held. Compared
     * against the fingerprint captured when the day was last banked — a MAX(ts) watermark does
     * NOT work here, because late-arriving history (strap backfill, imports) carries OLD
     * timestamps and would never pass the watermark, while a row count always moves.
     *
     * Deliberately NOT zone-aware: a timezone change must not re-bank history. Banked rows
     * carry LIVED wall-clock hours (bucketed under the zone in effect when the day was
     * current), and reinterpreting them under the new zone would corrupt the hour alignment —
     * the raw streams carry no capture timezone, so preservation is the only honest option.
     * New days simply bank under the new zone and the window turns over.
     */
    data class BankFingerprint(val rawHrRows: Long, val rawRrRows: Long)

    /**
     * The window days that need (re-)banking: days with raw data that were never banked, plus
     * days whose raw fingerprint changed since the bank (a partial bank caught mid-sync, then
     * the rest of the day arrived). A day with no raw rows is skipped (nothing to bank); a day
     * whose fingerprint matches its bank row is fresh. Pure so the data-layer pass is
     * unit-testable without Room.
     */
    fun daysToBank(
        windowDays: List<String>,
        banked: Map<String, BankFingerprint>,
        raw: Map<String, BankFingerprint>,
    ): List<String> = windowDays.filter { day ->
        val r = raw[day] ?: return@filter false
        val hasRaw = r.rawHrRows > 0L || r.rawRrRows > 0L
        val wasBanked = banked.containsKey(day)
        // A never-banked empty day has nothing to do. A previously banked day whose raw data
        // was deleted MUST still re-bank to empty: otherwise its derived stress aggregates
        // survive after their source rows are gone.
        (hasRaw || wasBanked) && banked[day] != r
    }

    /**
     * The zone a window day is fingerprinted and (re-)banked under: its PERSISTED bank zone
     * when the day was banked before, else the current zone. A banked day's UTC bounds must
     * come from the zone that minted them — resolving them under a NEW current zone after
     * travel would shift the window onto different samples, change the counts, and trigger
     * exactly the reinterpret-history re-bank the counts-only fingerprint exists to prevent.
     * Pure so the pass's travel behaviour is pinned by a unit test.
     */
    fun bankZoneFor(bankedZone: String?, currentZone: String): String = bankedZone ?: currentZone

    /** The pure shape of a zone-journal row (the data layer maps its Room entity to this). */
    data class ZoneJournalEntry(val firstZone: String, val lastZone: String, val zoneChanged: Boolean)

    /**
     * The zone a completed day may be banked under: the journal's record of the zone the day
     * was LIVED in, or null when the day touched more than one zone or was never journaled.
     * Trust requires BOTH: the sticky [ZoneJournalEntry.zoneChanged] flag clear (any observed
     * or unobserved transition sets it forever — an A→B→A same-day round trip can't hide
     * behind matching endpoints) AND matching first/last zones. An untrusted day is skipped,
     * never banked under a guessed zone: the raw streams carry no capture timezone, so a day
     * that crossed zones has no single honest wall clock.
     *
     * The journal earns this trust app-wide, not opportunistically: it is stamped on every
     * banking call (process start and a periodic tick included), on every
     * ACTION_TIMEZONE_CHANGED broadcast, and a discontinuity check poisons the previous day
     * whenever the zone moved since the last stamp (an unobserved transition mid-gap). What
     * it fundamentally cannot see is a round trip completed ENTIRELY while unobserved — the
     * honest residue of tz-less raw data; such a day is at worst bucketed under its (correct)
     * endpoints' zone.
     */
    fun journalZoneFor(entry: ZoneJournalEntry?): String? =
        entry?.takeIf { !it.zoneChanged && it.firstZone == it.lastZone }?.firstZone

    /**
     * Which stale days the pass may actually (re-)bank. Two classes:
     *   • ALREADY-banked days whose fingerprint moved (late-arriving history) — always
     *     re-banked, under their persisted zone.
     *   • NEVER-banked days — only days the ZONE JOURNAL proves single-zone
     *     ([journaledZones]: day → the zone it was lived in). The journal is written while
     *     each day is in progress (app-wide stamps, timezone-change events, and a
     *     discontinuity check that poisons days spanning an unobserved change), so a
     *     journaled day may be banked any time later, under its journaled zone — a missed
     *     day is retried, not lost. Unjournaled days are never banked: the raw streams carry
     *     no capture timezone, so an unproven day would be bucketed under today's zone and
     *     persisted as wrong. Holes are honest; misbucketed history is not.
     */
    fun eligibleDaysToBank(
        staleDays: List<String>,
        bankedDays: Set<String>,
        journaledZones: Map<String, String>,
    ): List<String> = staleDays.filter { it in bankedDays || journaledZones.containsKey(it) }

    // MARK: - Helpers

    private fun mean(xs: List<Double>): Double = xs.sum() / xs.size

    /** Population standard deviation; 0 when there's no spread. (Matches DaytimeStress.std.) */
    private fun std(xs: List<Double>): Double {
        if (xs.size <= 1) return 0.0
        val m = mean(xs)
        val v = xs.sumOf { (it - m) * (it - m) } / xs.size
        return sqrt(v)
    }

    /**
     * Local yyyy-MM-dd key for a LOCAL-shifted bucket (seconds since epoch PLUS the tz offset):
     * formatting the shifted instant as UTC yields the local calendar day — the same trick the
     * daily pipeline's dayString(ts, offset) uses, so banked day keys match dailyMetric rows.
     */
    private fun localDayKey(localBucket: Long): String =
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE.format(
            java.time.Instant.ofEpochSecond(localBucket).atOffset(java.time.ZoneOffset.UTC),
        )
}
