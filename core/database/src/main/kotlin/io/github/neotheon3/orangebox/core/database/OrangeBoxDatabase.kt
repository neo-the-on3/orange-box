package io.github.neotheon3.orangebox.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [VehicleEntity::class, TripEntity::class, TripSampleEntity::class, TripEventEntity::class],
    version = OrangeBoxDatabase.VERSION,
    exportSchema = true,
    // 1 -> 2 only adds the trip tables (M4); 2 -> 3 adds trip_events.durationS (#164). Room generates both.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class OrangeBoxDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun tripDao(): TripDao

    companion object {
        const val VERSION = 3
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
