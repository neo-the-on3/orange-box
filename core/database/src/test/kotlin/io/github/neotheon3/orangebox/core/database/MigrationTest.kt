package io.github.neotheon3.orangebox.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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

    @Test fun `all migrations reach the current version`() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, OrangeBoxDatabase.VERSION, true, *Migrations.ALL).close()
    }

    private companion object { const val DB = "migration-test" }
}
