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
import io.github.neotheon3.orangebox.core.datastore.SignInError
import io.github.neotheon3.orangebox.core.datastore.SignInResult
import io.github.neotheon3.orangebox.core.datastore.SyncError
import io.github.neotheon3.orangebox.core.model.AppVersion
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.DeviceInfo
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.InMemorySessionStore
import io.github.neotheon3.orangebox.core.network.ProblemDto
import io.github.neotheon3.orangebox.core.network.toSession
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' account and sync status (M9): e-mail + code sign-in, sign-out, Sync now, last status. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBackendAccountTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: OrangeBoxDatabase
    private val sessions = InMemorySessionStore()
    private val server = FakeBackendApi()

    /** The in-process server behind Remote: like OkHttpBackendApi, sign-in writes the session and logout clears it. */
    private val remote = object : BackendApi by server {
        /** One member per address (the fake server itself has a single member). */
        override suspend fun signIn(email: String, code: String, device: DeviceInfo?) =
            server.signIn(email, code, device).copy(memberId = memberOf(email))
                .also { sessions.write(it.toSession(0, email.lowercase())) }

        override suspend fun logout() {
            server.logout()
            sessions.clear()
        }
    }

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        db = Room.inMemoryDatabaseBuilder(context, OrangeBoxDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun TestScope.account(config: FakeBackendConfigRepository): Pair<AppBackendAccount, BackendClients> {
        val clients = BackendClients(sessions).also { it.remoteForTests = remote }
        val account = AppBackendAccount(
            config, clients, SyncScheduler(context, config), SyncStatusStore(db.syncDao()), db.syncDao(), RoomSyncStore(db.syncDao()),
            AppVersion.UNKNOWN, backgroundScope,
        )
        account.install()
        testScheduler.runCurrent()
        return account to clients
    }

    /** Lets the account's own coroutines (queued behind this unconfined test body) run. */
    private fun TestScope.settle() = testScheduler.runCurrent()

    private fun remoteConfig() = FakeBackendConfigRepository(BackendConfig(BackendMode.REMOTE, "https://api.example.invalid"))

    private fun oneOff() = WorkManager.getInstance(context).getWorkInfosForUniqueWork(SyncScheduler.ONE_OFF).get().map { it.state }

    @Test fun `off - not signed in, sign-in is unavailable and nothing is built`() = runTest {
        val (account, clients) = account(FakeBackendConfigRepository())
        val state = account.state.first()
        assertFalse(state.signedIn)
        assertNull(state.lastSync)
        assertEquals(SignInResult.Failed(SignInError.NOT_CONFIGURED), account.requestCode("kiran@example.com"))
        account.syncNow()
        assertTrue(oneOff().isEmpty())
        assertEquals(0, clients.httpClientsBuilt)
    }

    @Test fun `remote - e-mail, code, signed in, and a sync is queued`() = runTest {
        val (account, _) = account(remoteConfig())
        assertEquals(SignInResult.Failed(SignInError.INVALID_EMAIL), account.requestCode("not-an-address"))
        assertTrue("nothing sent for an invalid address", server.calls.isEmpty())

        assertEquals(SignInResult.CodeSent(resendAfterSeconds = 60, expiresInSeconds = 600), account.requestCode(" Kiran@Example.com "))
        assertEquals(SignInResult.Failed(SignInError.WRONG_CODE), account.verifyCode("kiran@example.com", "12"))
        assertEquals(SignInResult.Failed(SignInError.WRONG_CODE), account.verifyCode("kiran@example.com", "123456"))
        assertEquals(SignInResult.SignedIn, account.verifyCode("Kiran@Example.com", "000 000"))

        val state = account.state.first()
        assertTrue(state.signedIn)
        assertEquals("kiran@example.com", state.email)
        assertEquals(1, oneOff().size)
    }

    @Test fun `sign-in errors map to what Settings says`() = runTest {
        val (account, _) = account(remoteConfig())
        fun problem(status: Int, code: String, retryAfter: Long? = null) =
            ApiError.Http(status, ProblemDto("about:blank", code, status, code = code, requestId = "r"), retryAfter)

        server.failNext(problem(400, ApiError.CODE_OTP_LOCKED, retryAfter = 3600))
        assertEquals(SignInResult.Failed(SignInError.LOCKED, 3600), account.verifyCode("k@example.com", "111111"))
        server.failNext(problem(400, ApiError.CODE_OTP_ATTEMPTS_EXCEEDED))
        assertEquals(SignInResult.Failed(SignInError.TOO_MANY_ATTEMPTS), account.verifyCode("k@example.com", "111111"))
        server.failNext(problem(429, "rate_limited", retryAfter = 30))
        assertEquals(SignInResult.Failed(SignInError.RATE_LIMITED, 30), account.requestCode("k@example.com"))
        server.failNext(problem(422, ApiError.CODE_TEST_ADDRESS_REJECTED))
        assertEquals(SignInResult.Failed(SignInError.TEST_ADDRESS), account.requestCode("a@test.orangebox.invalid"))
        server.failNext(problem(422, "validation_failed"))
        assertEquals(SignInResult.Failed(SignInError.INVALID_EMAIL), account.requestCode("k@example.com"))
        server.failNext(ApiError.Network(java.io.IOException("offline")))
        assertEquals(SignInResult.Failed(SignInError.NETWORK), account.requestCode("k@example.com"))
        server.failNext(problem(500, "internal_error"))
        assertEquals(SignInResult.Failed(SignInError.SERVER), account.requestCode("k@example.com"))
    }

    @Test fun `sign out clears the session and the server state, keeps every trip with its account`() = runTest {
        val config = remoteConfig()
        val (account, clients) = account(config)
        account.verifyCode("k@example.com", "000000")
        val car = db.vehicleDao().insert(VehicleEntity(name = "SX4", maker = "Maruti Suzuki", model = "SX4", fuel = "PETROL", displacementCc = 1586, odometerKm = null, createdAtMillis = 1))
        db.tripDao().insertTrip(TripEntity(vehicleId = car, startedAtMillis = 1_000, lastSampleAtMillis = 601_000, endedAtMillis = 601_000, distanceKm = 4.0, accountId = memberOf("k@example.com")))
        server.seedTrip(
            io.github.neotheon3.orangebox.core.network.TripUploadDto(
                clientTripId = "00000000-0000-4000-8000-000000000009", vehicleId = "veh_other", state = "completed",
                startedAt = java.time.Instant.parse("2026-10-20T10:00:00Z"), snapshotAt = java.time.Instant.parse("2026-10-20T10:30:00Z"),
                endedAt = java.time.Instant.parse("2026-10-20T10:30:00Z"),
            ),
        )
        val runner = SyncRunner(config, clients, RoomSyncStore(db.syncDao()), SyncStatusStore(db.syncDao())) { 5_000 }
        assertEquals(SyncOutcome.Done, runner.run())
        assertEquals(2, db.tripDao().observeTrips().first().size)
        assertTrue(account.state.first().lastSync!!.ok)

        account.signOut()
        assertNull(sessions.read())
        val state = account.state.first()
        assertFalse(state.signedIn)
        assertNull("status is cleared with the account", state.lastSync)
        val kept = db.tripDao().observeTrips().first()
        assertEquals("pulled and recorded trips both stay", 2, kept.size)
        assertTrue(kept.all { it.accountId == memberOf("k@example.com") })
        assertNull(RoomSyncStore(db.syncDao()).since())
        assertNull(RoomSyncStore(db.syncDao()).account())
        assertTrue("already in that account", RoomSyncStore(db.syncDao()).trips().none { it.needsUpload })
    }

    @Test fun `changing the server address signs out locally`() = runTest {
        val config = remoteConfig()
        val (account, _) = account(config)
        account.verifyCode("k@example.com", "000000")
        assertTrue(account.state.first().signedIn)
        config.setBaseUrl("https://other.example.invalid")
        settle()
        assertNull(sessions.read())
        assertFalse(account.state.first().signedIn)
    }

    @Test fun `the runner records the last status - ok, and a retryable failure`() = runTest {
        val config = FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE))
        val (account, clients) = account(config)
        val status = SyncStatusStore(db.syncDao())
        val runner = SyncRunner(config, clients, RoomSyncStore(db.syncDao()), status) { 42_000 }
        runner.run()
        assertEquals(42_000, account.state.first().lastSync!!.atMillis)
        assertTrue(account.state.first().lastSync!!.ok)

        (clients.forConfig(BackendConfig(BackendMode.FAKE)) as FakeBackendApi).failNext(FakeBackendApi.unavailable(10))
        assertEquals(SyncOutcome.RetryAfter(10), runner.run())
        val last = account.state.first().lastSync!!
        assertFalse(last.ok)
        assertEquals(SyncError.SERVER_BUSY, last.error)
        assertTrue(last.willRetry)
    }

    @Test fun `fake - sync now queues a run without an account`() = runTest {
        val (account, _) = account(FakeBackendConfigRepository(BackendConfig(BackendMode.FAKE)))
        assertFalse(account.state.first().signedIn)
        WorkManager.getInstance(context).cancelAllWork()
        account.syncNow()
        assertTrue(oneOff().any { it == WorkInfo.State.ENQUEUED })
    }

    // --- accounts (Q13) ---

    private suspend fun car(account: String? = null) = db.vehicleDao().insert(
        VehicleEntity(name = "SX4", maker = "Maruti Suzuki", model = "SX4", fuel = "PETROL", displacementCc = 1586, odometerKm = null, createdAtMillis = 1, accountId = account),
    )

    private suspend fun trip(car: Long, account: String? = null, start: Long = 1_760_000_000_000) = db.tripDao().insertTrip(
        TripEntity(vehicleId = car, startedAtMillis = start, lastSampleAtMillis = start + 600_000, endedAtMillis = start + 600_000, distanceKm = 4.0, accountId = account),
    )

    private fun TestScope.runner(config: FakeBackendConfigRepository, clients: BackendClients) =
        SyncRunner(config, clients, RoomSyncStore(db.syncDao()), SyncStatusStore(db.syncDao())) { 5_000 }

    @Test fun `sign-in without other trips - no prompt, unassigned vehicles join, the sync is queued`() = runTest {
        val v = car()
        val (account, _) = account(remoteConfig())
        account.verifyCode("k@example.com", "000000")
        val state = account.state.first()
        assertFalse(state.otherTripsChoicePending)
        assertEquals(0, state.otherTrips)
        assertEquals(memberOf("k@example.com"), db.vehicleDao().get(v)!!.accountId)
        assertEquals(memberOf("k@example.com"), RoomSyncStore(db.syncDao()).account())
        assertEquals(1, oneOff().size)
    }

    @Test fun `sign-in with signed-out trips asks first - nothing syncs until answered`() = runTest {
        val config = remoteConfig()
        val v = car()
        trip(v)
        trip(v, start = 1_760_000_900_000)
        val (account, clients) = account(config)
        account.verifyCode("k@example.com", "000000")
        val state = account.state.first()
        assertTrue(state.otherTripsChoicePending)
        assertEquals(2, state.otherTrips)
        assertTrue("no sync queued", oneOff().isEmpty())
        assertEquals(io.github.neotheon3.orangebox.core.datastore.OtherTrips(2, 2), account.otherTrips())

        server.calls.clear()
        assertEquals(SyncOutcome.Done, runner(config, clients).run())
        assertTrue("the runner waits for the choice", server.calls.isEmpty())
    }

    @Test fun `merge uploads the other trips to the account, restamped, and syncs`() = runTest {
        val config = remoteConfig()
        val v = car()
        trip(v)
        val (account, clients) = account(config)
        account.verifyCode("k@example.com", "000000")
        account.mergeOtherTrips()
        val state = account.state.first()
        assertFalse(state.otherTripsChoicePending)
        assertEquals(0, state.otherTrips)
        assertEquals(1, oneOff().size)
        runner(config, clients).run()
        assertEquals(1, server.trips.size)
        assertTrue(db.tripDao().observeTrips().first().all { it.accountId == memberOf("k@example.com") })
    }

    @Test fun `replace deletes the other trips from the phone and pulls the account's`() = runTest {
        val config = remoteConfig()
        val v = car()
        val signedOut = trip(v)
        server.createVehicle(io.github.neotheon3.orangebox.core.network.VehicleCreateDto("srv-car", "Tata", "Nexon", "diesel"), "k")
        server.seedTrip(
            io.github.neotheon3.orangebox.core.network.TripUploadDto(
                "00000000-0000-4000-8000-00000000000a", server.vehicles.single().id, "completed",
                java.time.Instant.parse("2026-10-20T10:00:00Z"), java.time.Instant.parse("2026-10-20T10:30:00Z"),
            ),
        )
        val (account, clients) = account(config)
        account.verifyCode("k@example.com", "000000")
        account.replaceOtherTrips()
        assertNull(db.tripDao().trip(signedOut))
        runner(config, clients).run()
        val trips = db.tripDao().observeTrips().first()
        assertEquals(listOf("00000000-0000-4000-8000-00000000000a"), trips.map { it.clientTripId })
        assertEquals("nothing of ours went up", 1, server.trips.size)
        assertFalse(account.state.first().otherTripsChoicePending)
    }

    @Test fun `cancel keeps the other trips unsynced and the choice offered in Settings`() = runTest {
        val config = remoteConfig()
        val v = car()
        trip(v)
        val (account, clients) = account(config)
        account.verifyCode("k@example.com", "000000")
        account.keepOtherTripsSeparate()
        val state = account.state.first()
        assertFalse(state.otherTripsChoicePending)
        assertEquals("still offered", 1, state.otherTrips)
        runner(config, clients).run()
        assertTrue("never auto-uploaded", server.trips.isEmpty())
        // A trip recorded now belongs to the account and syncs.
        trip(v, account = memberOf("k@example.com"), start = 1_760_001_000_000)
        runner(config, clients).run()
        assertEquals(1, server.trips.size)
    }

    @Test fun `another account's trips count as other trips after switching, already uploaded there`() = runTest {
        val config = remoteConfig()
        val (account, clients) = account(config)
        account.verifyCode("a@example.com", "000000")
        trip(car(memberOf("a@example.com")), account = memberOf("a@example.com"))
        runner(config, clients).run()
        account.signOut()
        account.verifyCode("b@example.com", "000000")
        val state = account.state.first()
        assertTrue(state.otherTripsChoicePending)
        assertEquals(1, state.otherTrips)
        assertEquals("on A's server already: not lost by Replace", 0, account.otherTrips().neverUploaded)
    }

    private fun memberOf(email: String) = "member:" + email.trim().lowercase()
}
