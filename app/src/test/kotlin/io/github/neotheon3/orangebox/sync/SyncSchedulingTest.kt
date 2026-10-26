package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.neotheon3.orangebox.core.database.OrangeBoxDatabase
import io.github.neotheon3.orangebox.core.database.RoomSyncStore
import io.github.neotheon3.orangebox.core.database.TripEntity
import io.github.neotheon3.orangebox.core.database.VehicleEntity
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.InMemorySessionStore
import io.github.neotheon3.orangebox.core.network.Session
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Off makes no requests and schedules nothing; Fake / Remote schedule and run the sync (M9). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncSchedulingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OrangeBoxDatabase

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, OrangeBoxDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun states(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().map { it.state }

    @Test fun `off - nothing is enqueued, and switching to off cancels the sync`() = runTest(UnconfinedTestDispatcher()) {
        val config = FakeBackendConfigRepository()
        SyncScheduler(context, config).install(backgroundScope)
        assertTrue(states(SyncScheduler.PERIODIC).isEmpty())
        assertTrue(states(SyncScheduler.ONE_OFF).isEmpty())

        config.setMode(BackendMode.FAKE)
        assertEquals(listOf(WorkInfo.State.ENQUEUED), states(SyncScheduler.PERIODIC))
        assertEquals(1, states(SyncScheduler.ONE_OFF).size)

        config.setMode(BackendMode.OFF)
        assertEquals(listOf(WorkInfo.State.CANCELLED), states(SyncScheduler.PERIODIC))
        assertTrue(states(SyncScheduler.ONE_OFF).all { it == WorkInfo.State.CANCELLED })
    }

    @Test fun `remote without a valid https url schedules nothing`() = runTest(UnconfinedTestDispatcher()) {
        val config = FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, ""))
        SyncScheduler(context, config).install(backgroundScope)
        assertTrue(states(SyncScheduler.PERIODIC).isEmpty())
    }

    @Test fun `off - the runner builds no client and makes no request`() = runTest {
        val clients = BackendClients(InMemorySessionStore())
        val runner = SyncRunner(FakeBackendConfigRepository(), clients, RoomSyncStore(db.syncDao()))
        assertEquals(SyncOutcome.Done, runner.run(attempt = 0))
        assertNull(clients.forConfig(BackendConfig()))
        assertEquals(0, clients.httpClientsBuilt)
    }

    @Test fun `remote without a session does nothing and builds no request`() = runTest {
        val clients = BackendClients(InMemorySessionStore())
        val runner = SyncRunner(FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "https://api.example.invalid")), clients, RoomSyncStore(db.syncDao()))
        assertEquals(SyncOutcome.Done, runner.run(attempt = 0))
    }

    @Test fun `fake - a run uploads local trips to the in-process backend, without OkHttp`() = runTest {
        val clients = BackendClients(InMemorySessionStore())
        val v = db.vehicleDao().insert(VehicleEntity(name = "SX4", maker = "Maruti Suzuki", model = "SX4", fuel = "PETROL", displacementCc = 1586, odometerKm = null, createdAtMillis = 1))
        db.tripDao().insertTrip(TripEntity(vehicleId = v, startedAtMillis = 1_000, lastSampleAtMillis = 601_000, endedAtMillis = 601_000, distanceKm = 4.0))
        val runner = SyncRunner(FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE)), clients, RoomSyncStore(db.syncDao()))
        assertEquals(SyncOutcome.Done, runner.run(attempt = 0))
        val fake = clients.forConfig(BackendConfig(BackendMode.FAKE)) as FakeBackendApi
        assertEquals(1, fake.trips.size)
        assertEquals(0, clients.httpClientsBuilt)
    }

    @Test fun `503 with Retry-After asks for a delayed run, other 4xx fail`() = runTest {
        val sessions = InMemorySessionStore(Session("a", "r", "m", Long.MAX_VALUE, Long.MAX_VALUE))
        val clients = BackendClients(sessions)
        val fakeConfig = FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE))
        val runner = SyncRunner(fakeConfig, clients, RoomSyncStore(db.syncDao()))
        val fake = clients.forConfig(BackendConfig(BackendMode.FAKE)) as FakeBackendApi
        fake.failNext(FakeBackendApi.unavailable(retryAfterSeconds = 12))
        assertEquals(SyncOutcome.RetryAfter(12), runner.run(attempt = 0))
        fake.failNext(io.github.neotheon3.orangebox.core.network.ApiError.Http(422, null))
        assertTrue(runner.run(attempt = 0) is SyncOutcome.Failed)
        fake.failNext(io.github.neotheon3.orangebox.core.network.ApiError.Network(java.io.IOException("offline")))
        assertEquals(SyncOutcome.Retry, runner.run(attempt = 0))
    }
}
