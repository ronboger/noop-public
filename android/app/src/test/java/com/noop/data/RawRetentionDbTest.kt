package com.noop.data

import android.app.Application
import androidx.room.Room
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The retention SQL against a real Room store (Robolectric's native SQLite). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RawRetentionDbTest {

    private lateinit var db: WhoopDatabase
    private lateinit var dao: WhoopDao
    private val day = 86_400L
    private val now = 1_800_000_000L
    private val id = "my-whoop"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.whoopDao()
        // The real store installs the streamRevision triggers in onOpen; the in-memory builder does not.
        // Install them, so every delete below also runs the journal bump it runs on a phone.
        WhoopDatabase.STREAM_REVISION_TRIGGER_SQL.forEach { sql(it) }
    }

    @After
    fun close() = db.close()

    private fun sql(s: String) = db.openHelper.writableDatabase.execSQL(s)
    private fun count(table: String, where: String = "1"): Long =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getLong(0) }

    @Test
    fun `rollup uses measured HR first and PPG only for missing seconds`() = runBlocking {
        // Minute 0: measured 60 and 80 at t=0,1; PPG 200 at t=1 (shadowed) and 70 at t=2 (fills).
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', 0, 60, 0), ('$id', 1, 80, 0)")
        sql("INSERT INTO ppgHrSample(deviceId, ts, bpm, conf, synced) VALUES ('$id', 1, 200, 1.0, 0), ('$id', 2, 70, 1.0, 0)")
        dao.rollupHrMinutes(id, 0, 60)
        val b = dao.hrMinuteBuckets(id, 0, 59, 60).single()
        assertEquals(70.0, b.avgBpm, 1e-9)
        assertEquals(60.0, b.minBpm, 1e-9)
        assertEquals(80.0, b.maxBpm, 1e-9)
        // A second rollup over a now-partial minute keeps the full one.
        sql("DELETE FROM hrSample WHERE ts = 0")
        dao.rollupHrMinutes(id, 0, 60)
        assertEquals(70.0, dao.hrMinuteBuckets(id, 0, 59, 60).single().avgBpm, 1e-9)
    }

    @Test
    fun `wider archived buckets weight minutes by their sample count`() = runBlocking {
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', 0, 60, 0), ('$id', 1, 60, 0), ('$id', 2, 60, 0)")
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', 60, 100, 0)")
        dao.rollupHrMinutes(id, 0, 120)
        assertEquals(70.0, dao.hrMinuteBuckets(id, 0, 119, 120).single().avgBpm, 1e-9)
    }

    @Test
    fun `run archives old raw rows, keeps recent ones, summaries and other ids`() = runBlocking {
        val old = now - 100 * day
        val recent = now - 10 * day
        for (t in listOf(old, old + 1, recent)) {
            sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', $t, 70, 0)")
            sql("INSERT INTO gravitySample(deviceId, ts, x, y, z, synced) VALUES ('$id', $t, 0, 0, 1, 0)")
        }
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('imported-x', $old, 70, 0)")
        sql("INSERT INTO battery(deviceId, ts, soc, charging, synced) VALUES ('$id', $old, 50, 0, 0)")

        val report = RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE)

        assertTrue(report.finished)
        assertEquals(1L, count("hrSample", "deviceId = '$id'"))
        assertEquals(1L, count("gravitySample", "deviceId = '$id'"))
        assertEquals(1L, count("hrSample", "deviceId = 'imported-x'"))
        assertEquals(1L, count("battery"))
        // The deletes went through the journal triggers.
        assertTrue(count("streamRevision", "deviceId = '$id' AND hour = ${old / 3600}") > 0)
        assertEquals(RawRetention.cutoff(now), dao.rawArchivedBefore(id))
        assertNull(dao.rawArchivedBefore("imported-x"))
        // The archived HR is still chartable through the minute table.
        val m = dao.hrMinuteBuckets(id, old - 60, old + 60, 60).single()
        assertEquals(2.0 * 70, m.avgBpm * 2, 1e-9)

        // A second run is a no-op.
        assertEquals(0L, RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE).deleted)
    }

    @Test
    fun `run stops at the deadline without losing HR`() = runBlocking {
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', ${now - 100 * day}, 70, 0)")
        val report = RawRetention.run(db, nowSec = now, deadlineMillis = 0L)
        assertEquals(false, report.finished)
        assertEquals(1L, count("hrSample"))
    }

    @Test
    fun `switching retention off stops a pass before it deletes`() = runBlocking {
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', ${now - 100 * day}, 70, 0)")
        val report = RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE, keepGoing = { false })
        assertEquals(false, report.finished)
        assertEquals(1L, count("hrSample"))
    }

    @Test
    fun `a nearly full disk stops a pass`() = runBlocking {
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', ${now - 100 * day}, 70, 0)")
        val report = RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE, freeDiskBytes = { 0L })
        assertEquals(false, report.finished)
        assertEquals(1L, count("hrSample"))
    }

    @Test
    fun `an interrupted pass keeps its progress and the boundary only covers rolled-up minutes`() = runBlocking {
        val old = now - 100 * day
        for (d in 0 until 5) sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', ${old + d * day}, 70, 0)")
        var steps = 0
        // Stop after a few checks: some steps done, the rest left for the next run.
        val first = RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE, keepGoing = { steps++ < 12 })
        assertEquals(false, first.finished)
        val boundary = dao.rawArchivedBefore(id)!!
        // Every deleted second is below the boundary and has its minute.
        assertEquals(0L, count("hrSample", "deviceId = '$id' AND ts < $boundary"))
        for (d in 0 until 5) {
            val t = old + d * day
            if (t < boundary) assertEquals(1, dao.hrMinuteBuckets(id, t - t % 60, t - t % 60, 60).size)
        }
        assertTrue(RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE).finished)
        assertEquals(0L, count("hrSample", "deviceId = '$id'"))
        assertEquals(RawRetention.cutoff(now), dao.rawArchivedBefore(id))
    }

    @Test
    fun `a wider window later never moves the boundary back`() = runBlocking {
        sql("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('$id', ${now - 70 * day}, 70, 0)")
        RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE, windowDays = 60)
        val b60 = dao.rawArchivedBefore(id)!!
        RawRetention.run(db, nowSec = now, deadlineMillis = Long.MAX_VALUE, windowDays = 90)
        assertEquals(b60, dao.rawArchivedBefore(id))
    }
}
