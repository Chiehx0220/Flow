package io.github.aedev.flow.data.local.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds `serviceId` (org.schabi.newpipe.extractor.ServiceList id, 0 = YouTube) so rows can
 * record which extractor service they came from now that Bilibili is a second option.
 */
class Migration26To27 : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN serviceId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE playlists ADD COLUMN serviceId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE watch_history ADD COLUMN serviceId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN serviceId INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE home_feed_cache ADD COLUMN serviceId INTEGER NOT NULL DEFAULT 0")
    }
}
