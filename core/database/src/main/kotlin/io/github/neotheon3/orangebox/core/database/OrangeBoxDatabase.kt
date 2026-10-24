package io.github.neotheon3.orangebox.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [VehicleEntity::class, TripEntity::class, TripSampleEntity::class, TripEventEntity::class],
    version = OrangeBoxDatabase.VERSION,
    exportSchema = true,
    // 1 -> 2 only adds the trip tables (M4), so Room generates it.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class OrangeBoxDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao
    abstract fun tripDao(): TripDao

    companion object {
        const val VERSION = 2
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
