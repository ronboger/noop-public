package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawRetentionTest {

    @Test
    fun `cutoff is the window back, floored to a minute`() {
        val now = 1_800_000_037L
        assertEquals(RawRetention.floorMinute(now - 90L * 86_400L), RawRetention.cutoff(now))
        assertEquals(0L, RawRetention.cutoff(now) % 60L)
    }

    @Test
    fun `archived buckets are whole minutes`() {
        assertEquals(60L, RawRetention.archivedBucketSeconds(15L))
        assertEquals(60L, RawRetention.archivedBucketSeconds(60L))
        assertEquals(120L, RawRetention.archivedBucketSeconds(90L))
        assertEquals(300L, RawRetention.archivedBucketSeconds(300L))
    }

    @Test
    fun `analysis span is unchanged with no boundary or a short pass`() {
        val now = 1_800_000_000L
        assertEquals(4000, RawRetention.clampAnalysisDays(4000, null, now))
        assertEquals(21, RawRetention.clampAnalysisDays(21, RawRetention.cutoff(now), now))
    }

    @Test
    fun `a full-history pass never reaches the archive`() {
        val now = 1_800_000_000L
        val days = RawRetention.clampAnalysisDays(4000, RawRetention.cutoff(now), now)
        assertEquals(RawRetention.RAW_WINDOW_DAYS - 2, days)
        assertEquals(1, RawRetention.clampAnalysisDays(4000, now, now))
    }

    @Test
    fun `join keeps both halves and merges only the straddling bucket`() {
        val archived = listOf(HrBucket(0, 60.0, 50.0, 70.0), HrBucket(300, 62.0, 55.0, 80.0))
        val raw = listOf(HrBucket(300, 70.0, 40.0, 75.0), HrBucket(600, 65.0, 60.0, 70.0))
        val joined = RawRetention.joinBuckets(archived, raw)
        assertEquals(listOf(0L, 300L, 600L), joined.map { it.bucket })
        assertEquals(HrBucket(300, 66.0, 40.0, 80.0), joined[1])
        assertEquals(raw, RawRetention.joinBuckets(emptyList(), raw))
    }

    @Test
    fun `batch size adapts and stays bounded`() {
        assertEquals(2_000, RawRetention.nextBatchSize(1_000, 10))
        assertEquals(500, RawRetention.nextBatchSize(1_000, 400))
        assertEquals(1_000, RawRetention.nextBatchSize(1_000, 100))
        assertEquals(RawRetention.MAX_BATCH, RawRetention.nextBatchSize(RawRetention.MAX_BATCH, 1))
        assertEquals(RawRetention.MIN_BATCH, RawRetention.nextBatchSize(RawRetention.MIN_BATCH, 10_000))
    }

    @Test
    fun `pruned tables never include the summaries or the journal`() {
        val kept = listOf("event", "battery", "streamRevision", "dailyMetric", "sleepSession", "hourlyMetric",
            "workout", "journal", "hrMinute", "rawRetention", "liveSession")
        kept.forEach { assertFalse(it, it in RawRetention.PRUNED_TABLES) }
        assertEquals("hrSample", RawRetention.PRUNED_TABLES.first())
    }
}
