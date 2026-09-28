package com.cloudimage.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The 1 → 2 migration (v1.0.22) must create the `downloads` table with the
 * exact schema Room expects, and seed it from the history feed so wallpapers
 * downloaded under v1.0.21 and earlier appear in the new Downloaded tab
 * without re-downloading.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class Migration1To2Test {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun migrationSeedsDownloadsFromHistory() {
        withLegacyDatabase { db ->
            insertHistory(db, providerId = "wallhaven", wallpaperId = "one", action = "VIEWED", atMillis = 100)
            insertHistory(db, providerId = "wallhaven", wallpaperId = "one", action = "DOWNLOADED", atMillis = 200)
            insertHistory(db, providerId = "wallhaven", wallpaperId = "one", action = "DOWNLOADED", atMillis = 300)
            insertHistory(db, providerId = "hdqwalls", wallpaperId = "two", action = "DOWNLOADED", atMillis = 250)
            insertHistory(db, providerId = "hdqwalls", wallpaperId = "three", action = "VIEWED", atMillis = 400)
            insertHistory(db, providerId = "hdqwalls", wallpaperId = "four", action = "APPLIED", atMillis = 500)

            Migrations.MIGRATION_1_2.migrate(db)

            // Newest DOWNLOADED timestamp per wallpaper; never a viewed/applied row.
            assertEquals(
                listOf("one" to 300L, "two" to 250L),
                queryDownloads(db).map { it.wallpaperId to it.downloadedAtMillis }.sortedBy { it.first },
            )
            // The seeded snapshot comes from the winning row (SQLite's MAX()
            // picks the whole row it compares).
            assertEquals("title-at-300", queryDownloads(db).single { it.wallpaperId == "one" }.title)
        }
    }

    @Test
    fun roomAcceptsTheMigratedSchemaAndKeepsExistingData() =
        runTest {
            val dbFile = context.getDatabasePath("migration-test-${System.nanoTime()}.db")
            dbFile.parentFile?.mkdirs()

            buildLegacyDatabaseFile(dbFile.absolutePath) { db ->
                insertHistory(db, providerId = "wallhaven", wallpaperId = "one", action = "DOWNLOADED", atMillis = 200)
                db.execSQL(
                    """
                    INSERT INTO favorites
                        (`providerId`, `wallpaperId`, `thumbUrl`, `fullUrl`, `title`, `width`, `height`, `sourceUrl`, `contentRating`, `addedAtMillis`)
                    VALUES ('wallhaven', 'kept', 't', 'f', 'Kept', 10, 20, NULL, 'SFW', 50)
                    """.trimIndent(),
                )
            }

            // Opening the v1 file with the v2 database runs the migration for
            // real — Room validates the migrated schema against the entity
            // (any mismatch throws "Migration didn't properly handle") and
            // must leave favorites intact.
            val database =
                Room
                    .databaseBuilder(context, CloudimageDatabase::class.java, dbFile.absolutePath)
                    .addMigrations(*Migrations.ALL)
                    .allowMainThreadQueries()
                    .build()

            val downloads = database.downloadedDao().observeAll().first()
            val favorites = database.favoriteDao().observeAll().first()

            assertEquals(listOf("one"), downloads.map { it.wallpaperId })
            assertEquals(200L, downloads.single().downloadedAtMillis)
            assertEquals(listOf("kept"), favorites.map { it.wallpaperId })
            database.close()
            assertTrue(dbFile.delete())
        }

    /** Creates an in-memory v1 database (favorites + history, no downloads). */
    private fun withLegacyDatabase(block: (SupportSQLiteDatabase) -> Unit) {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(null) // in-memory
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createLegacySchema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {}
                    },
                ).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            helper.writableDatabase.let(block)
        }
    }

    /** Builds a v1 database file on disk, then closes it for Room to reopen. */
    private fun buildLegacyDatabaseFile(
        path: String,
        seed: (SupportSQLiteDatabase) -> Unit,
    ) {
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(path)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createLegacySchema(db)
                            // Room's identity table, with a placeholder hash —
                            // the version bump forces the migration path, and
                            // Room rewrites the hash once it validates.
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER NOT NULL, identity_hash TEXT NOT NULL, PRIMARY KEY(id))",
                            )
                            db.execSQL("INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, 'legacy-v1-placeholder')")
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {}
                    },
                ).build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            seed(helper.writableDatabase)
        }
    }

    /** The v1 schema exactly as Room generated it at version 1. */
    private fun createLegacySchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `favorites` (
                `providerId` TEXT NOT NULL, `wallpaperId` TEXT NOT NULL,
                `thumbUrl` TEXT NOT NULL, `fullUrl` TEXT NOT NULL,
                `title` TEXT, `width` INTEGER, `height` INTEGER, `sourceUrl` TEXT,
                `contentRating` TEXT NOT NULL, `addedAtMillis` INTEGER NOT NULL,
                PRIMARY KEY(`providerId`, `wallpaperId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `history` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `providerId` TEXT NOT NULL, `wallpaperId` TEXT NOT NULL,
                `thumbUrl` TEXT NOT NULL, `fullUrl` TEXT NOT NULL,
                `title` TEXT, `width` INTEGER, `height` INTEGER, `sourceUrl` TEXT,
                `contentRating` TEXT NOT NULL, `action` TEXT NOT NULL, `atMillis` INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_atMillis` ON `history` (`atMillis`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_providerId_wallpaperId` ON `history` (`providerId`, `wallpaperId`)")
    }

    private fun insertHistory(
        db: SupportSQLiteDatabase,
        providerId: String,
        wallpaperId: String,
        action: String,
        atMillis: Long,
    ) {
        db.execSQL(
            """
            INSERT INTO history
                (`providerId`, `wallpaperId`, `thumbUrl`, `fullUrl`, `title`, `width`, `height`, `sourceUrl`, `contentRating`, `action`, `atMillis`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                providerId,
                wallpaperId,
                "thumb",
                "full",
                "title-at-$atMillis",
                10,
                20,
                null,
                "SFW",
                action,
                atMillis,
            ),
        )
    }

    private class DownloadedRow(
        val wallpaperId: String,
        val title: String?,
        val downloadedAtMillis: Long,
    )

    private fun queryDownloads(db: SupportSQLiteDatabase): List<DownloadedRow> {
        val rows = mutableListOf<DownloadedRow>()
        db.query("SELECT `wallpaperId`, `title`, `downloadedAtMillis` FROM downloads").use { cursor ->
            while (cursor.moveToNext()) {
                rows +=
                    DownloadedRow(
                        wallpaperId = cursor.getString(0),
                        title = cursor.getString(1),
                        downloadedAtMillis = cursor.getLong(2),
                    )
            }
        }
        return rows
    }
}
