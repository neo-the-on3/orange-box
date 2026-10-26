package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Fake mode's member endpoints (#66): opt-in, 404 / 412 / 428 / 501 the way the spec has them, offline. */
class FakeMemberApiTest {
    private val now = Instant.parse("2026-10-21T08:00:00Z")

    private suspend fun fakeWithTrip(simulated: Boolean = false) = FakeBackendApi(clock = { now }).also { f ->
        val v = f.createVehicle(VehicleCreateDto("cv", fuelType = FuelTypes.GASOLINE, nickname = "Car"), "k")
        f.seedTrip(
            TripUploadDto(
                "t1", v.id, TripUploadDto.STATE_COMPLETED, now.minusSeconds(3600), now.minusSeconds(1800), endedAt = now.minusSeconds(1800),
                durationS = 1800, distanceKm = 20.0, ecoSpeedTimeS = 900.0, simulated = simulated,
            ),
        )
    }

    private suspend fun errorOf(block: suspend () -> Unit) = runCatching { block() }.exceptionOrNull() as ApiError.Http

    @Test fun `ranking opt-in is off by default, the member appears once opted in`() = runTest {
        val f = fakeWithTrip()
        assertFalse(f.getMe().value.rankingOptIn)
        assertTrue(f.listRanking(RankingMetrics.ECO_SPEED, Periods.WEEK, "UTC").items.none { it.isMe })
        assertEquals(404, errorOf { f.getMyRanking(RankingMetrics.ECO_SPEED, Periods.WEEK, "UTC") }.status)

        f.patchMe(ProfilePatchDto(rankingOptIn = true), f.getMe().etag)
        val all = buildList {
            var cursor: String? = null
            do {
                val p = f.listRanking(RankingMetrics.ECO_SPEED, Periods.WEEK, "UTC", cursor = cursor)
                addAll(p.items)
                cursor = p.nextCursor
            } while (cursor != null)
        }
        assertEquals(FakeMemberData.OTHERS.size + 1, all.size)
        val me = all.single { it.isMe }
        assertEquals(50.0, me.value, 0.01) // 900 s eco of 1800 s
        val mine = f.getMyRanking(RankingMetrics.ECO_SPEED, Periods.WEEK, "UTC")
        assertEquals(me.rank, mine.rank)
        assertEquals(all.size, mine.participants)
    }

    @Test fun `simulated trips are not ranked, but count in stats`() = runTest {
        val f = fakeWithTrip(simulated = true)
        f.patchMe(ProfilePatchDto(rankingOptIn = true), f.getMe().etag)
        assertEquals(404, errorOf { f.getMyRanking(RankingMetrics.DRIVING_SCORE, Periods.WEEK, "UTC") }.status)
        assertEquals(1, f.getStatsSummary(Periods.WEEK, "UTC").tripCount)
    }

    @Test fun `harsh driving ranks the lowest value first`() = runTest {
        val page = fakeWithTrip().listRanking(RankingMetrics.HARSH_DRIVING, Periods.MONTH, "UTC")
        assertEquals(page.items.map { it.value }.sorted(), page.items.map { it.value })
    }

    @Test fun `profile patch needs a current etag`() = runTest {
        val f = fakeWithTrip()
        assertEquals(428, errorOf { f.patchMe(ProfilePatchDto(rankingOptIn = true), null) }.status)
        assertEquals(412, errorOf { f.patchMe(ProfilePatchDto(rankingOptIn = true), "\"stale\"") }.status)
        val first = f.getMe()
        f.patchMe(ProfilePatchDto(rankingOptIn = true), first.etag)
        assertEquals("the old etag is stale now", 412, errorOf { f.patchMe(ProfilePatchDto(rankingOptIn = false), first.etag) }.status)
    }

    @Test fun `stats summary - week range, totals and level`() = runTest {
        val f = fakeWithTrip()
        f.today = LocalDate.of(2026, 10, 21) // a Wednesday
        val s = f.getStatsSummary(Periods.WEEK, "Asia/Kolkata")
        assertEquals("2026-10-19", s.from)
        assertEquals("2026-10-25", s.to)
        assertEquals(1, s.tripCount)
        assertEquals(20.0, s.distanceKm!!, 0.001)
        assertEquals(1, s.level)
        assertNotNull(s.drivingScore)
        assertEquals(0, f.getStatsSummary(Periods.DAY, "UTC", date = "2026-01-01").tripCount)
    }

    @Test fun `points - welcome bonus, trip points and a redemption`() = runTest {
        val f = fakeWithTrip()
        val b = f.getPointsBalance()
        assertEquals(500 + 30, b.earned)
        assertEquals(200, b.used)
        assertEquals(b.earned - b.used, b.remaining)
        val ledger = f.listPointsLedger().items
        assertEquals("newest first", ledger.sortedByDescending { it.occurredAt }, ledger)
        assertEquals(30, ledger.single { it.category == "driving" }.amount) // 10 + 1 per km
    }

    @Test fun `a server without the member features answers 501`() = runTest {
        val f = fakeWithTrip().apply { memberFeaturesImplemented = false }
        assertEquals(501, errorOf { f.getPointsBalance() }.status)
        assertEquals(501, errorOf { f.listRanking(RankingMetrics.POINTS, Periods.ALL, "UTC") }.status)
        assertEquals(501, errorOf { f.getStatsSummary(Periods.ALL, "UTC") }.status)
    }

    @Test fun `scenarios - 503 is retryable, 500 carries a problem body`() = runTest {
        val f = fakeWithTrip()
        f.arm(FakeBackendApi.Scenario.UNAVAILABLE_503)
        val busy = errorOf { f.getPointsBalance() }
        assertEquals(503, busy.status)
        assertTrue(busy.isRetryable)
        f.arm(FakeBackendApi.Scenario.PROBLEM_500)
        assertEquals("Simulated server error (Fake mode)", errorOf { f.getPointsBalance() }.problem!!.detail)
        f.getPointsBalance() // one failure per arm
    }
}
