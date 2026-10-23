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

    @Test fun `all migrations reach the current version`() {
        helper.createDatabase(DB, 1).close()
        helper.runMigrationsAndValidate(DB, OrangeBoxDatabase.VERSION, true, *Migrations.ALL).close()
    }

    private companion object { const val DB = "migration-test" }
}
