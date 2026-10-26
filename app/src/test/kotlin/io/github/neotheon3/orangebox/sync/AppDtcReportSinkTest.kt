package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.neotheon3.orangebox.core.database.OrangeBoxDatabase
import io.github.neotheon3.orangebox.core.database.RoomSyncStore
import io.github.neotheon3.orangebox.core.database.VehicleEntity
import io.github.neotheon3.orangebox.core.diagnostics.DtcReading
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.DtcReportCreateDto
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.InMemorySessionStore
import io.github.neotheon3.orangebox.core.network.Session
import io.github.neotheon3.orangebox.core.sync.SyncEngine
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** DTC reads and clears queued for upload (#67) only while sync is on, in the spec's `DtcReportCreate` shape. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDtcReportSinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OrangeBoxDatabase
    private lateinit var store: RoomSyncStore
    private val sessions = InMemorySessionStore()
    private var ids = 0

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OrangeBoxDatabase::class.java).allowMainThreadQueries().build()
        store = RoomSyncStore(db.syncDao()) { NOW }
    }

    @After fun tearDown() = db.close()

    private fun sink(config: FakeBackendConfigRepository) =
        AppDtcReportSink(SyncEligibility(config, BackendClients(sessions)), store) { "00000000-0000-4000-8000-%012d".format(++ids) }

    private suspend fun car() = db.vehicleDao().insert(
        VehicleEntity(name = "SX4", maker = "Maruti Suzuki", model = "SX4", fuel = "PETROL", displacementCc = 1586, odometerKm = null, createdAtMillis = 1),
    )

    private fun read(vehicleId: Long?, mil: Boolean? = true) = DtcReading(
        DtcReading.Kind.READ, NOW - 1_000, vehicleId, mil, vin = "jt2bf22k1w0123456", stored = listOf("p0300", "P0300", "X123"),
        pending = emptyList(), permanent = null,
    )

    @Test fun `off and remote signed out keep nothing`() = runBlocking {
        val v = car()
        sink(FakeBackendConfigRepository()).onReading(read(v))
        sink(FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "https://api.example.invalid"))).onReading(read(v))
        assertTrue(store.dtcReports().isEmpty())
    }

    @Test fun `remote signed in queues the read in the spec shape`() = runBlocking {
        val v = car()
        sessions.write(Session("a", "r", "m", Long.MAX_VALUE, Long.MAX_VALUE))
        sink(FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "https://api.example.invalid"))).onReading(read(v))
        val dto = store.dtcReports().single().report
        assertEquals(DtcReportCreateDto.KIND_READ, dto.kind)
        assertEquals(Instant.ofEpochMilli(NOW - 1_000), dto.readAt)
        assertEquals("normalised, de-duplicated, non-SAE dropped", listOf("P0300"), dto.stored)
        assertEquals("read and empty", emptyList<String>(), dto.pending)
        assertNull("unread list left out", dto.permanent)
        assertEquals("JT2BF22K1W0123456", dto.vin)
        assertTrue(dto.mil)
    }

    @Test fun `a read without a vehicle, or with an unknown MIL or a bad VIN`() = runBlocking {
        val fake = sink(FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE)))
        fake.onReading(read(vehicleId = null))
        assertTrue(store.dtcReports().isEmpty())
        val dto = AppDtcReportSink.toDto(read(1, mil = null).copy(vin = "NOTAVIN", kind = DtcReading.Kind.CLEARED), "id")
        assertEquals(AppDtcReportSink.MIL_WHEN_UNKNOWN, dto.mil)
        assertNull(dto.vin)
        assertEquals(DtcReportCreateDto.KIND_CLEARED, dto.kind)
    }

    @Test fun `fake - a queued read goes up on the next sync`() = runBlocking {
        val v = car()
        sink(FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE))).onReading(read(v))
        val server = FakeBackendApi()
        val r = SyncEngine(server, store).run()
        assertEquals(1, r.dtcReports)
        assertEquals(listOf("P0300"), server.dtcReports.single().stored)
        assertTrue(store.dtcReports().isEmpty())
    }

    private companion object {
        const val NOW = 1_761_000_000_000L
    }
}
