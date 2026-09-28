package com.cloudimage.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Real migrations between app database versions. Registered in
 * [DatabaseModule] — the pre-1.0 destructive fallback is gone, schema churn
 * must now travel as explicit SQL.
 */
object Migrations {
    /**
     * v1 → v2 (v1.0.22): the `downloads` table for the Library's Downloaded
     * tab. New installs get the table from Room's create path; existing
     * installs seed it from their history so wallpapers downloaded under
     * v1.0.21 and earlier show up without re-downloading — the newest
     * DOWNLOADED row per wallpaper wins (SQLite's MAX() picks the whole row
     * it compares), and non-download actions never seed.
     */
    val MIGRATION_1_2 =
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `downloads` (
                        `providerId` TEXT NOT NULL,
                        `wallpaperId` TEXT NOT NULL,
                        `thumbUrl` TEXT NOT NULL,
                        `fullUrl` TEXT NOT NULL,
                        `title` TEXT,
                        `width` INTEGER,
                        `height` INTEGER,
                        `sourceUrl` TEXT,
                        `contentRating` TEXT NOT NULL,
                        `downloadedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY (`providerId`, `wallpaperId`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `downloads`
                        (`providerId`, `wallpaperId`, `thumbUrl`, `fullUrl`, `title`,
                         `width`, `height`, `sourceUrl`, `contentRating`, `downloadedAtMillis`)
                    SELECT `providerId`, `wallpaperId`, `thumbUrl`, `fullUrl`, `title`,
                           `width`, `height`, `sourceUrl`, `contentRating`, MAX(`atMillis`)
                    FROM `history`
                    WHERE `action` = 'DOWNLOADED'
                    GROUP BY `providerId`, `wallpaperId`
                    """.trimIndent(),
                )
            }
        }

    /** Every version step, in order — what [DatabaseModule] registers. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
