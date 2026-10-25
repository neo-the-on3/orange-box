package io.github.neotheon3.orangebox.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [VehicleEntity::class, TripEntity::class, TripSampleEntity::class, TripEventEntity::class, DtcRecordEntity::class],
    version = OrangeBoxDatabase.VERSION,
    exportSchema = true,
    // 1 -> 2 only adds the trip tables (M4); 2 -> 3 adds trip_events.durationS (#164); 3 -> 4 adds
    // dtc_records (#51). Room generates all three.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class OrangeBoxDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun tripDao(): TripDao
    abstract fun dtcRecordDao(): DtcRecordDao

    companion object {
        const val VERSION = 4
        const val NAME = "orangebox.db"
    }
}

/**
 * Every manual schema migration, oldest first (additive changes use `autoMigrations` above). Each
 * version bump needs its exported schema (`core/database/schemas`) and a case in `MigrationTest`.
 * Never fall back to destructive migration.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
