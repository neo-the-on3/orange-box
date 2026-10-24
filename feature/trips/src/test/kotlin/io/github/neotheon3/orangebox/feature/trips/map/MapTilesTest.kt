package io.github.neotheon3.orangebox.feature.trips.map

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MapTilesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Before fun start() = server.start()
    @After fun stop() = server.close()

    private fun provider(): MapTiles.Provider {
        val template = server.url("/").toString().trimEnd('/') + "/{z}/{x}/{y}.png"
        return MapTiles.Provider(MapTiles.client(tmp.newFolder(), MapTiles.userAgent("0.1.0.41-abc1234-debug")), template)
    }

    private fun tile(vararg headers: Pair<String, String>) = MockResponse.Builder().code(200).body(Buffer().write(png))
        .apply { headers.forEach { (k, v) -> addHeader(k, v) } }.build()

    @Test fun `policy URL and identifying user agent`() {
        assertEquals("https://tile.openstreetmap.org/12/2930/1902.png", MapTiles.url(12, 2930, 1902))
        assertEquals("OrangeBox/0.1.0.41-abc1234-debug (+https://github.com/neo-the-on3/orange-box)", MapTiles.userAgent("0.1.0.41-abc1234-debug"))
        assertEquals("OrangeBox/dev (+https://github.com/neo-the-on3/orange-box)", MapTiles.userAgent(""))
    }

    @Test fun `MapCompose row and col map to slippy y and x, with our user agent and no no-cache header`() = runTest {
        server.enqueue(tile("Cache-Control" to "max-age=3600"))
        val bytes = provider().getTileStream(row = 1902, col = 2930, zoomLvl = 12)!!.readBytes()
        assertArrayEquals(png, bytes)
        val r = server.takeRequest()
        assertEquals("/12/2930/1902.png", r.url.encodedPath)
        assertEquals("OrangeBox/0.1.0.41-abc1234-debug (+https://github.com/neo-the-on3/orange-box)", r.headers["User-Agent"])
        assertNull(r.headers["Pragma"])
        assertNull(r.headers["Cache-Control"])
    }

    @Test fun `a tile without caching headers is cached for 7 days, not fetched again`() = runTest {
        server.enqueue(tile())
        val p = provider()
        assertArrayEquals(png, p.getTileStream(1, 2, 3)!!.readBytes())
        assertArrayEquals(png, p.getTileStream(1, 2, 3)!!.readBytes())
        assertEquals(1, server.requestCount)
    }

    @Test fun `an expired tile is revalidated with its ETag`() = runTest {
        server.enqueue(tile("Cache-Control" to "max-age=0", "ETag" to "\"t1\""))
        server.enqueue(MockResponse.Builder().code(304).addHeader("Cache-Control", "max-age=0").addHeader("ETag", "\"t1\"").build())
        val p = provider()
        p.getTileStream(1, 2, 3)!!.readBytes()
        assertArrayEquals(png, p.getTileStream(1, 2, 3)!!.readBytes())
        server.takeRequest()
        assertEquals("\"t1\"", server.takeRequest().headers["If-None-Match"])
    }

    @Test fun `refused or failed tiles are empty, never exceptions`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(MockResponse.Builder().code(403).build())
        val p = provider()
        assertNull(p.getTileStream(0, 0, 1))
        assertNull(p.getTileStream(0, 1, 1))
        assertNull("deeper than the server's max zoom is never requested", p.getTileStream(0, 0, 19))
        assertEquals(2, server.requestCount)
        server.close()
        assertNull(p.getTileStream(1, 1, 1))
    }
}
