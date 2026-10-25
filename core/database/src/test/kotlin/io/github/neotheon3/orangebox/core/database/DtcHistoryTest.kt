package io.github.neotheon3.orangebox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity.Companion.END_CLEARED
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity.Companion.END_RESOLVED
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity.Companion.KIND_PENDING
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity.Companion.KIND_PERMANENT
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity.Companion.KIND_STORED
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** DAO tests for the DTC history (#51). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DtcHistoryTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), OrangeBoxDatabase::class.java)
        .allowMainThreadQueries().build()
    private val dao = db.dtcRecordDao()
    private val repo = RoomDtcHistoryRepository(dao)

    @After fun close() = db.close()

    private suspend fun all() = repo.history.first()

    @Test fun `a first read opens one record per code and kind`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300", "P0420"), KIND_PENDING to listOf("P0171")), vehicleId = 1, at = 1_000)
        val records = all()
        assertEquals(3, records.size)
        assertTrue(records.all { it.isOpen && it.firstSeenAtMillis == 1_000L && it.lastSeenAtMillis == 1_000L && it.vehicleId == 1L })
        assertEquals(setOf("P0300" to KIND_STORED, "P0420" to KIND_STORED, "P0171" to KIND_PENDING), records.map { it.code to it.kind }.toSet())
    }

    @Test fun `a repeated read only moves last seen`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), null, 1_000)
        repo.recordReading(mapOf(KIND_STORED to listOf("p0300")), null, 5_000)
        val r = all().single()
        assertEquals(1_000, r.firstSeenAtMillis)
        assertEquals(5_000, r.lastSeenAtMillis)
        assertTrue(r.isOpen)
    }

    @Test fun `a code missing from a later read of its list ends as resolved`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300", "U0100")), null, 1_000)
        repo.recordReading(mapOf(KIND_STORED to listOf("U0100")), null, 2_000)
        val p0300 = all().single { it.code == "P0300" }
        assertEquals(2_000L, p0300.endedAtMillis)
        assertEquals(END_RESOLVED, p0300.endReason)
        assertTrue(all().single { it.code == "U0100" }.isOpen)
    }

    @Test fun `a list that could not be read leaves its records alone`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300"), KIND_PENDING to listOf("P0171")), null, 1_000)
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), null, 2_000) // pending unreadable
        assertTrue(all().single { it.code == "P0171" }.isOpen)
    }

    @Test fun `clearing ends stored and pending records but keeps permanent ones`() = runBlocking {
        repo.recordReading(
            mapOf(KIND_STORED to listOf("P0300"), KIND_PENDING to listOf("P0171"), KIND_PERMANENT to listOf("P0420")), null, 1_000,
        )
        repo.recordCleared(3_000)
        val byCode = all().associateBy { it.code }
        assertEquals(END_CLEARED, byCode.getValue("P0300").endReason)
        assertEquals(3_000L, byCode.getValue("P0171").endedAtMillis)
        assertNull(byCode.getValue("P0420").endedAtMillis)
    }

    @Test fun `a code that comes back after clearing opens a new record`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), null, 1_000)
        repo.recordCleared(2_000)
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), null, 9_000)
        val records = all()
        assertEquals(2, records.size)
        assertEquals(9_000, records.first().firstSeenAtMillis) // newest first
        assertTrue(records.first().isOpen)
        assertEquals(END_CLEARED, records.last().endReason)
    }

    @Test fun `records are kept per vehicle`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), vehicleId = 1, at = 1_000)
        repo.recordReading(mapOf(KIND_STORED to emptyList()), vehicleId = 2, at = 2_000)
        assertTrue(all().single().isOpen)
    }

    @Test fun `delete all empties the history`() = runBlocking {
        repo.recordReading(mapOf(KIND_STORED to listOf("P0300")), null, 1_000)
        repo.deleteAll()
        assertEquals(emptyList<DtcRecordEntity>(), all())
    }
}
