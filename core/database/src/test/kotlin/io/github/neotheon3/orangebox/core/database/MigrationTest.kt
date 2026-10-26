package io.github.neotheon3.orangebox.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Migration scaffold: opens every exported schema version and runs all [Migrations] up to the current
 * version, validating against the exported schema. Add a case when VERSION goes up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), OrangeBoxDatabase::class.java)

    @Test fun `version 1 schema is exported and opens`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO vehicles (name, maker, model, fuel, displacementCc, odometerKm, createdAtMillis) VALUES ('A', NULL, NULL, 'PETROL', 1200, 10, 1)")
            db.query("SELECT COUNT(*) FROM vehicles").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun `1 to 2 keeps vehicles and adds the trip tables`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO vehicles (name, maker, model, fuel, displacementCc, odometerKm, createdAtMillis) VALUES ('A', NULL, NULL, 'PETROL', 1200, 10, 1)")
        }
        helper.runMigrationsAndValidate(DB, 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM vehicles").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO trips (vehicleId, startedAtMillis, lastSampleAtMillis, durationS, distanceKm, fuelLitres, co2Grams, idleS, fuelCutS, ecoS, overspeedS, harshAccelCount, harshBrakeCount, overspeedCount, maxSpeedKph) VALUES (1, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
            db.query("SELECT COUNT(*) FROM trips").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun `2 to 3 keeps events and adds a null duration (#164)`() {
        helper.createDatabase(DB, 2).use { db ->
            db.execSQL("INSERT INTO trips (vehicleId, startedAtMillis, lastSampleAtMillis, durationS, distanceKm, fuelLitres, co2Grams, idleS, fuelCutS, ecoS, overspeedS, harshAccelCount, harshBrakeCount, overspeedCount, maxSpeedKph) VALUES (NULL, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0)")
            db.execSQL("INSERT INTO trip_events (tripId, timestampMillis, type, value) VALUES (1, 20, 'harsh_brake', -4.3)")
        }
        helper.runMigrationsAndValidate(DB, 3, true).use { db ->
            db.query("SELECT value, durationS FROM trip_events").use { c ->
                c.moveToFirst(); assertEquals(-4.3, c.getDouble(0), 1e-9); assertEquals(true, c.isNull(1))
            }
            db.execSQL("INSERT INTO trip_events (tripId, timestampMillis, type, value, durationS) VALUES (1, 30, 'harsh_brake', -5.5, 2.4)")
        }
    }

    @Test fun `3 to 4 keeps trips and adds dtc_records (#51)`() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL("INSERT INTO trips (vehicleId, startedAtMillis, lastSampleAtMillis, durationS, distanceKm, fuelLitres, co2Grams, idleS, fuelCutS, ecoS, overspeedS, harshAccelCount, harshBrakeCount, overspeedCount, maxSpeedKph) VALUES (NULL, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
        }
        helper.runMigrationsAndValidate(DB, 4, true).use { db ->
            db.query("SELECT COUNT(*) FROM trips").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL("INSERT INTO dtc_records (code, kind, vehicleId, firstSeenAtMillis, lastSeenAtMillis) VALUES ('P0300', 'stored', NULL, 1, 1)")
            db.query("SELECT code, endedAtMillis FROM dtc_records").use { c ->
                c.moveToFirst(); assertEquals("P0300", c.getString(0)); assertEquals(true, c.isNull(1))
            }
        }
    }

    @Test fun `4 to 5 backfills unique client UUIDs and adds the sync tables (M9)`() {
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        helper.createDatabase(DB, 4).use { db ->
            db.execSQL("INSERT INTO vehicles (name, maker, model, fuel, displacementCc, odometerKm, createdAtMillis) VALUES ('SX4', 'Maruti Suzuki', 'SX4', 'PETROL', 1586, 1, 1)")
            db.execSQL("INSERT INTO vehicles (name, maker, model, fuel, displacementCc, odometerKm, createdAtMillis) VALUES ('Celerio', NULL, NULL, 'CNG', 998, 1, 2)")
            repeat(3) {
                db.execSQL("INSERT INTO trips (vehicleId, startedAtMillis, lastSampleAtMillis, durationS, distanceKm, fuelLitres, co2Grams, idleS, fuelCutS, ecoS, overspeedS, harshAccelCount, harshBrakeCount, overspeedCount, maxSpeedKph) VALUES (1, $it, $it, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
            }
            db.execSQL("INSERT INTO trip_events (tripId, timestampMillis, type, value, durationS) VALUES (1, 20, 'harsh_brake', -4.3, 1.0)")
            db.execSQL("INSERT INTO trip_events (tripId, timestampMillis, type, value, durationS) VALUES (1, 30, 'overspeed', 101, 4.0)")
        }
        helper.runMigrationsAndValidate(DB, 5, true, Migrations.MIGRATION_4_5).use { db ->
            for ((table, column, rows) in listOf(Triple("vehicles", "clientVehicleId", 2), Triple("trips", "clientTripId", 3), Triple("trip_events", "clientEventId", 2))) {
                val ids = mutableListOf<String>()
                db.query("SELECT $column FROM $table").use { c -> while (c.moveToNext()) ids += c.getString(0) }
                assertEquals(table, rows, ids.toSet().size)
                ids.forEach { assertTrue("$table: $it", uuid.matches(it)) }
            }
            db.query("SELECT COUNT(*) FROM trip_sync").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
            db.execSQL("INSERT INTO sync_state (`key`, value) VALUES ('trips_since', 'S')")
            db.execSQL("INSERT INTO trip_sync (tripId, serverId, uploadedBefore, acceptedCompleted, forceUpload, remoteOrigin) VALUES (1, 't1', 1, 1, 0, 0)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM trips WHERE id = 1")
            db.query("SELECT COUNT(*) FROM trip_sync").use { c -> c.moveToFirst(); assertEquals("cascades with the trip", 0, c.getInt(0)) }
        }
    }

    @Test fun `all migrations reach the current version`() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, OrangeBoxDatabase.VERSION, true, *Migrations.ALL).close()
    }

    private companion object { const val DB = "migration-test" }
}
