package io.github.neotheon3.orangebox.feature.rankings

import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.ApiError
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.Periods
import io.github.neotheon3.orangebox.core.network.ProblemDto
import io.github.neotheon3.orangebox.core.network.RankingMetrics
import io.github.neotheon3.orangebox.core.testing.FakeMemberBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

class MemberRepositoryTest {
    private val server = FakeBackendApi()
    private val backend = FakeMemberBackend(server)
    private val repo = MemberRepository(backend) { ZoneId.of("Asia/Kolkata") }

    private fun http(status: Int, code: String = "x") = ApiError.Http(status, ProblemDto(status = status, code = code))

    @Test fun `visible only in Fake, or Remote signed in`() {
        assertFalse(MemberBackend.isAvailable(BackendMode.OFF, signedIn = true))
        assertTrue(MemberBackend.isAvailable(BackendMode.FAKE, signedIn = false))
        assertFalse(MemberBackend.isAvailable(BackendMode.REMOTE, signedIn = false))
        assertTrue(MemberBackend.isAvailable(BackendMode.REMOTE, signedIn = true))
    }

    @Test fun `no backend - signed out, nothing called`() = runTest {
        backend.available.value = false
        assertEquals(Load.SignedOut, repo.summary(Periods.WEEK))
        assertTrue(server.calls.isEmpty())
    }

    @Test fun `the zone goes as tz`() = runTest {
        repo.summary(Periods.WEEK)
        assertTrue(server.calls.single().startsWith("GET /v1/stats/summary"))
        assertTrue(repo.summary(Periods.DAY) is Load.Ready)
    }

    @Test fun `rankings not built yet - 404 or 501 is unavailable, not an error`() = runTest {
        server.failNext(http(404, "not_found"))
        assertEquals(Load.Unavailable, repo.ranking(RankingMetrics.ECO_SPEED, Periods.WEEK))
        server.memberFeaturesImplemented = false
        assertEquals(Load.Unavailable, repo.ranking(RankingMetrics.ECO_SPEED, Periods.WEEK))
        assertEquals(Load.Unavailable, repo.points())
        assertEquals(Load.Unavailable, repo.summary(Periods.WEEK))
        assertEquals(Load.Unavailable, repo.myRanking(RankingMetrics.ECO_SPEED, Periods.WEEK))
    }

    @Test fun `my ranking 404 is not ranked`() = runTest {
        assertEquals(Load.Ready(null), repo.myRanking(RankingMetrics.ECO_SPEED, Periods.WEEK))
    }

    @Test fun `errors map to retry states`() = runTest {
        server.failNext(ApiError.Network(IOException("down")), FakeBackendApi.unavailable(), http(500), ApiError.NotSignedIn(), http(401))
        assertEquals(Load.Failed(Load.Reason.NETWORK), repo.points())
        assertEquals(Load.Failed(Load.Reason.BUSY), repo.points())
        assertEquals(Load.Failed(Load.Reason.SERVER), repo.points())
        assertEquals(Load.SignedOut, repo.points())
        assertEquals(Load.SignedOut, repo.points())
        assertTrue(repo.points() is Load.Ready)
    }

    @Test fun `ranking opt-in is off by default and toggles with PATCH me`() = runTest {
        assertEquals(false, (repo.profile() as Load.Ready).value.rankingOptIn)
        assertEquals(true, (repo.setRankingOptIn(true) as Load.Ready).value.rankingOptIn)
        assertTrue(server.calls.contains("PATCH /v1/me"))
        assertEquals(true, (repo.profile() as Load.Ready).value.rankingOptIn)
        assertEquals(false, (repo.setRankingOptIn(false) as Load.Ready).value.rankingOptIn)
    }

    @Test fun `a 412 conflict fetches the profile again and retries once`() = runTest {
        server.arm(FakeBackendApi.Scenario.CONFLICT_412)
        assertEquals(true, (repo.setRankingOptIn(true) as Load.Ready).value.rankingOptIn)
        assertEquals(2, server.calls.count { it == "PATCH /v1/me" })
        server.arm(FakeBackendApi.Scenario.CONFLICT_412)
        server.arm(FakeBackendApi.Scenario.CONFLICT_412)
        assertEquals("a second conflict gives up", Load.Failed(Load.Reason.SERVER), repo.setRankingOptIn(false))
    }
}
