package io.github.neotheon3.orangebox.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.InMemorySessionStore
import io.github.neotheon3.orangebox.core.network.Session
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/** A one-off sync when a trip ends (M9): only while sync is on, and never cutting a backoff short. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripEndSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sessions = InMemorySessionStore()
    private val active = MutableStateFlow<Long?>(null)
    private var triggers = 0

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun eligibility(config: FakeBackendConfigRepository) = SyncEligibility(config, BackendClients(sessions))

    private fun TestScope.install(config: FakeBackendConfigRepository): TripEndSync =
        TripEndSync(backgroundScope, active, eligibility(config)) { triggers++; true }.also { it.install(); runCurrent() }

    private fun TestScope.drive(id: Long) {
        active.value = id; runCurrent()
        active.value = null; runCurrent()
    }

    private fun remote() = FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "https://api.example.invalid"))

    private suspend fun signIn() = sessions.write(Session("a", "r", "m", Long.MAX_VALUE, Long.MAX_VALUE, "kiran@example.com"))

    @Test fun `fake - each trip end queues a sync, a trip start doesn't`() = runTest {
        install(FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE)))
        active.value = 1; runCurrent()
        assertEquals(0, triggers)
        active.value = null; runCurrent()
        assertEquals(1, triggers)
        drive(2)
        assertEquals(2, triggers)
    }

    @Test fun `off never syncs`() = runTest {
        install(FakeBackendConfigRepository())
        drive(1)
        assertEquals(0, triggers)
    }

    @Test fun `remote only while signed in with a valid address`() = runTest {
        install(remote())
        drive(1)
        assertEquals("signed out", 0, triggers)
        signIn()
        drive(2)
        assertEquals(1, triggers)
    }

    @Test fun `remote without a valid address doesn't sync, even with a session`() = runTest {
        signIn()
        install(FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "http://not-https.invalid")))
        drive(1)
        assertEquals(0, triggers)
    }

    @Test fun `the real scheduler queues a one-off after a trip, and keeps one delayed by Retry-After`() = runTest {
        val config = FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE))
        val scheduler = SyncScheduler(context, config)
        assertTrue(scheduler.syncAfterTrip())
        val first = oneOff().single()
        assertEquals(WorkInfo.State.ENQUEUED, first.state)
        assertFalse("already queued: kept", scheduler.syncAfterTrip())
        assertEquals(first.id, oneOff().single().id)

        scheduler.cancelAll()
        scheduler.syncSoon(delaySeconds = 600) // the server's Retry-After
        val delayed = oneOff().first { it.state == WorkInfo.State.ENQUEUED }
        assertFalse(scheduler.syncAfterTrip())
        assertEquals(delayed.id, oneOff().first { it.state == WorkInfo.State.ENQUEUED }.id)
    }

    @Test fun `nothing is queued while the periodic sync backs off, or while a one-off runs or retries`() {
        fun info(state: WorkInfo.State, attempts: Int = 0) = WorkInfo(UUID.randomUUID(), state, emptySet(), Data.EMPTY, Data.EMPTY, attempts)
        assertTrue(SyncScheduler.shouldQueueAfterTrip(emptyList(), emptyList()))
        assertTrue(SyncScheduler.shouldQueueAfterTrip(listOf(info(WorkInfo.State.SUCCEEDED)), listOf(info(WorkInfo.State.ENQUEUED))))
        assertTrue("a failed one-off is replaced", SyncScheduler.shouldQueueAfterTrip(listOf(info(WorkInfo.State.FAILED)), emptyList()))
        assertFalse(SyncScheduler.shouldQueueAfterTrip(listOf(info(WorkInfo.State.RUNNING)), emptyList()))
        assertFalse("one-off backing off", SyncScheduler.shouldQueueAfterTrip(listOf(info(WorkInfo.State.ENQUEUED, attempts = 2)), emptyList()))
        assertFalse("periodic backing off", SyncScheduler.shouldQueueAfterTrip(emptyList(), listOf(info(WorkInfo.State.ENQUEUED, attempts = 1))))
    }

    private fun oneOff() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.ONE_OFF).get()
}
