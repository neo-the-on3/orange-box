package io.github.neotheon3.orangebox.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(entities = [VehicleEntity::class], version = OrangeBoxDatabase.VERSION, exportSchema = true)
abstract class OrangeBoxDatabase : RoomDatabase() {
    abstract fun vehicleDao(): VehicleDao

    companion object {
        const val VERSION = 1
        const val NAME = "orangebox.db"
    }
}

/**
 * Every schema migration, oldest first. Add one per version bump together with its exported schema
 * (`core/database/schemas`) and a case in `MigrationTest`. Never fall back to destructive migration.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
