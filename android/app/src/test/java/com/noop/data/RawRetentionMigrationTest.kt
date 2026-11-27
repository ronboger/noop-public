package com.noop.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * A real v44 file upgraded by the app's own builder (all migrations, the corruption-preserving open
 * helper, onOpen's trigger install). There is no destructive fallback, so a broken 44 -> 45 would stop
 * the app opening at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RawRetentionMigrationTest {

    @Test
    fun `a v44 store upgrades to v45 with the retention tables and keeps its rows`() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = File(ctx.cacheDir, "v44-upgrade.db").also { it.delete() }

        // Make a v45 file, then turn it back into a v44 one: the v45 tables gone, version 44.
        Room.databaseBuilder(ctx, WhoopDatabase::class.java, file.absolutePath).build().apply {
            openHelper.writableDatabase.execSQL("INSERT INTO hrSample(deviceId, ts, bpm, synced) VALUES ('my-whoop', 1, 60, 0)")
            close()
        }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE hrMinute")
            raw.execSQL("DROP TABLE rawRetention")
            raw.version = 44
        }

        // The production builder: migrates, validates the schema against the entities, installs triggers.
        WhoopDatabase.validateRestore(ctx, file)

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(45, raw.version)
            fun has(type: String, name: String) =
                raw.rawQuery("SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name))
                    .use { it.moveToFirst() }
            assertTrue(has("table", "hrMinute"))
            assertTrue(has("table", "rawRetention"))
            assertTrue(has("trigger", "streamRev_hrSample_del"))
            raw.rawQuery("SELECT COUNT(*) FROM hrSample", null).use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        }
    }
}
