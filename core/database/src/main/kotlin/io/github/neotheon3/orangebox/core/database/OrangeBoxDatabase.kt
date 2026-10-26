package io.github.neotheon3.orangebox.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VehicleEntity::class, TripEntity::class, TripSampleEntity::class, TripEventEntity::class, DtcRecordEntity::class,
        TripSyncEntity::class, VehicleSyncEntity::class, LocalTripDeletionEntity::class, SyncStateEntity::class,
    ],
    version = OrangeBoxDatabase.VERSION,
    exportSchema = true,
    // 1 -> 2 only adds the trip tables (M4); 2 -> 3 adds trip_events.durationS (#164); 3 -> 4 adds
    // dtc_records (#51). Room generates all three. 4 -> 5 (M9 sync) is manual: it backfills UUIDs. 5 -> 6 (M9)
    // adds nullable columns only.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 5, to = 6),
    ],
)
abstract class OrangeBoxDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun tripDao(): TripDao
    abstract fun dtcRecordDao(): DtcRecordDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val VERSION = 6
        const val NAME = "orangebox.db"
    }
}

/**
 * Every manual schema migration, oldest first (additive changes use `autoMigrations` above). Each
 * version bump needs its exported schema (`core/database/schemas`) and a case in `MigrationTest`.
 * Never fall back to destructive migration.
 */
object Migrations {
    /** A random UUIDv4 per row, in SQL (SQLite has no uuid function). */
    private const val UUID_SQL = "lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || substr('89ab', 1 + (abs(random()) % 4), 1) || " +
        "substr(lower(hex(randomblob(2))), 2) || '-' || lower(hex(randomblob(6)))"

    /**
     * 4 → 5 (M9 backend sync): client UUIDs on vehicles, trips and events (backfilled, unique), and the sync
     * tables. Nothing is uploaded by this; sync only runs in Remote / Fake mode.
     */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `vehicles` ADD COLUMN `clientVehicleId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `vehicles` SET `clientVehicleId` = $UUID_SQL")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vehicles_clientVehicleId` ON `vehicles` (`clientVehicleId`)")
            db.execSQL("ALTER TABLE `trips` ADD COLUMN `clientTripId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `trips` SET `clientTripId` = $UUID_SQL")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trips_clientTripId` ON `trips` (`clientTripId`)")
            db.execSQL("ALTER TABLE `trip_events` ADD COLUMN `clientEventId` TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE `trip_events` SET `clientEventId` = $UUID_SQL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `trip_sync` (`tripId` INTEGER NOT NULL, `serverId` TEXT, `serverUpdatedAt` TEXT, " +
                    "`uploadedBefore` INTEGER NOT NULL, `acceptedSnapshotAtMillis` INTEGER, `acceptedCompleted` INTEGER NOT NULL, " +
                    "`rejectedCode` TEXT, `forceUpload` INTEGER NOT NULL, `remoteOrigin` INTEGER NOT NULL, PRIMARY KEY(`tripId`), " +
                    "FOREIGN KEY(`tripId`) REFERENCES `trips`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_trip_sync_serverId` ON `trip_sync` (`serverId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `vehicle_sync` (`vehicleId` INTEGER NOT NULL, `serverId` TEXT, `goneOnServer` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`vehicleId`), FOREIGN KEY(`vehicleId`) REFERENCES `vehicles`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_vehicle_sync_serverId` ON `vehicle_sync` (`serverId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_trip_deletions` (`clientTripId` TEXT NOT NULL, `serverId` TEXT, " +
                    "`deletedAtMillis` INTEGER NOT NULL, `pushed` INTEGER NOT NULL, PRIMARY KEY(`clientTripId`))",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS `sync_state` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_4_5)
}
