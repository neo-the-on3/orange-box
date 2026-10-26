package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** MockWebServer tests of the member endpoints (#68): `/v1/me`, stats summary, rankings and points. */
class MemberApiTest {
    private lateinit var server: MockWebServer
    private val sessions = InMemorySessionStore()
    private lateinit var api: OkHttpBackendApi

    @Before fun setUp() = runTest {
        server = MockWebServer()
        server.start()
        api = OkHttpBackendApi(server.url("/").toString(), OkHttpClient(), sessions, clock = { 1_000_000L }, allowCleartextForTests = true)
        sessions.write(Session("a1", "r1", "m1", 2_000_000L, 9_000_000L, email = "k@example.com"))
    }

    @After fun tearDown() = server.close()

    private fun json(code: Int, body: String, vararg headers: Pair<String, String>) = MockResponse.Builder()
        .code(code)
        .headers(headersOf("Content-Type", if (code >= 400) "application/problem+json" else "application/json", *headers.flatMap { listOf(it.first, it.second) }.toTypedArray()))
        .body(body)
        .build()

    private val profile = """{"id":"m1","displayName":"Kiran","email":"k@example.com","countryCode":"IN","regionCode":null,""" +
        """"rankingOptIn":false,"authProvider":"email_otp","createdAt":"2026-10-01T10:00:00Z","updatedAt":"2026-10-02T10:00:00Z"}"""

    @Test fun `profile - get with etag, merge patch with If-Match`() = runTest {
        server.enqueue(json(200, profile, "ETag" to "\"p1\""))
        server.enqueue(json(200, profile.replace("\"rankingOptIn\":false", "\"rankingOptIn\":true"), "ETag" to "\"p2\""))
        val me = api.getMe()
        assertEquals("\"p1\"", me.etag)
        assertEquals(false, me.value.rankingOptIn)
        val patched = api.patchMe(ProfilePatchDto(rankingOptIn = true), me.etag)
        assertEquals(true, patched.value.rankingOptIn)
        assertEquals("\"p2\"", patched.etag)

        val get = server.takeRequest()
        assertEquals("/v1/me", get.url.encodedPath)
        assertEquals("Bearer a1", get.headers["Authorization"])
        val patch = server.takeRequest()
        assertEquals("PATCH", patch.method)
        assertEquals("\"p1\"", patch.headers["If-Match"])
        assertTrue(patch.headers["Content-Type"]!!.startsWith("application/merge-patch+json"))
        assertEquals("only the changed field is sent", """{"rankingOptIn":true}""", patch.body!!.utf8())
    }

    @Test fun `profile - rankingOptIn defaults to false when absent`() = runTest {
        server.enqueue(json(200, profile.replace("\"rankingOptIn\":false,", "")))
        assertEquals(false, api.getMe().value.rankingOptIn)
    }

    @Test fun `stats summary sends period, tz and date`() = runTest {
        server.enqueue(
            json(200, """{"period":"week","from":"2026-10-19","to":"2026-10-25","tripCount":3,"distanceKm":42.5,"drivingScore":81.5,""" +
                """"scoreStars":{"ecoSpeed":4},"fuelCost":null,"futureField":1}"""),
        )
        val s = api.getStatsSummary("week", "Asia/Kolkata", date = "2026-10-21")
        assertEquals(3, s.tripCount)
        assertEquals(4, s.scoreStars!!.ecoSpeed)
        assertNull(s.fuelCost)
        val r = server.takeRequest()
        assertEquals("/v1/stats/summary", r.url.encodedPath)
        assertEquals("week", r.url.queryParameter("period"))
        assertEquals("Asia/Kolkata", r.url.queryParameter("tz"))
        assertEquals("2026-10-21", r.url.queryParameter("date"))
    }

    @Test fun `rankings - page with cursor, my position, 404 not ranked`() = runTest {
        server.enqueue(
            json(200, """{"metric":"eco_speed","period":"month","periodStart":"2026-10-01","nextCursor":"c2",""" +
                """"items":[{"rank":1,"displayName":"Asha","value":71.5,"isMe":false,"vehicleLabel":"Swift"},{"rank":2,"displayName":"Kiran","value":60.0,"isMe":true}]}"""),
        )
        server.enqueue(json(200, """{"metric":"eco_speed","period":"month","rank":2,"participants":40,"value":60.0,"percentile":95.0}"""))
        server.enqueue(json(404, """{"type":"about:blank","title":"Not ranked","status":404,"code":"not_found"}"""))
        val page = api.listRanking("eco_speed", "month", "UTC", cursor = "c1")
        assertEquals("c2", page.nextCursor)
        assertEquals(true, page.items[1].isMe)
        assertEquals(40, api.getMyRanking("eco_speed", "month", "UTC").participants)
        val notRanked = runCatching { api.getMyRanking("eco_speed", "week", "UTC") }.exceptionOrNull() as ApiError.Http
        assertEquals(404, notRanked.status)

        val list = server.takeRequest()
        assertEquals("/v1/rankings/eco_speed", list.url.encodedPath)
        assertEquals("c1", list.url.queryParameter("cursor"))
        assertEquals("month", list.url.queryParameter("period"))
        assertEquals("/v1/rankings/eco_speed/me", server.takeRequest().url.encodedPath)
    }

    @Test fun `points - balance and ledger pages`() = runTest {
        server.enqueue(json(200, """{"earned":700,"used":200,"remaining":500,"expiringSoon":0,"expiringAt":null,"byCategory":{"driving":200,"event":500}}"""))
        server.enqueue(json(200, """{"items":[{"id":"p1","occurredAt":"2026-10-20T10:00:00Z","category":"driving","amount":25,"tripId":"t1","description":"Trip"}],"nextCursor":null}"""))
        assertEquals(500, api.getPointsBalance().remaining)
        val ledger = api.listPointsLedger(cursor = "x")
        assertEquals(25, ledger.items.single().amount)
        assertNull(ledger.nextCursor)
        assertEquals("/v1/me/points", server.takeRequest().url.encodedPath)
        val l = server.takeRequest()
        assertEquals("/v1/me/points/ledger", l.url.encodedPath)
        assertEquals("x", l.url.queryParameter("cursor"))
    }

    @Test fun `501 from a server without the feature is an Http error`() = runTest {
        server.enqueue(json(501, """{"type":"about:blank","title":"Not Implemented","status":501,"code":"not_implemented"}"""))
        val e = runCatching { api.getPointsBalance() }.exceptionOrNull() as ApiError.Http
        assertEquals(501, e.status)
    }
}
