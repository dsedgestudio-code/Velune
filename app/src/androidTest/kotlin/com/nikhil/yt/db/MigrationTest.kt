package com.nikhil.yt.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a device (debug app, separate data). Proves an upgrade keeps the user's library. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), InternalDatabase::class.java)

    @After
    fun cleanUp() {
        context.deleteDatabase(InternalDatabase.DB_NAME)
    }

    @Test
    fun upgradeFrom27KeepsSongsLikesAndPlaylists() {
        context.deleteDatabase(InternalDatabase.DB_NAME)
        helper.createDatabase(InternalDatabase.DB_NAME, 27).apply {
            execSQL(
                "INSERT INTO song (id, title, duration, explicit, liked, totalPlayTime, isLocal) " +
                    "VALUES ('song1', 'Kho Gaya', 222, 0, 1, 5000, 0)",
            )
            execSQL("INSERT INTO playlist (id, name) VALUES ('pl1', 'Road trip')")
            close()
        }

        // The real production open path (migrations, repair, no destructive fallback).
        val db = InternalDatabase.newInstance(context)
        try {
            val sql = db.openHelper.readableDatabase
            sql.query("SELECT title, liked, totalPlayTime FROM song WHERE id = 'song1'").use {
                assertEquals(1, it.count)
                it.moveToFirst()
                assertEquals("Kho Gaya", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(5000L, it.getLong(2))
            }
            sql.query("SELECT name FROM playlist WHERE id = 'pl1'").use {
                assertEquals(1, it.count)
            }
            assertEquals(28, sql.version)
        } finally {
            db.close()
        }
    }
}
