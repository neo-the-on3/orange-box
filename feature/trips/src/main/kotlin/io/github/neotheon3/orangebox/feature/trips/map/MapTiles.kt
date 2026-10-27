package io.github.neotheon3.orangebox.feature.trips.map

import android.content.Context
import io.github.neotheon3.orangebox.core.common.OrangeLog
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import ovh.plrapps.mapcompose.core.TileStreamProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keyless raster tiles under the OSMF tile usage policy (docs/adr/0001-keyless-map-library.md):
 * the exact HTTPS URL, an identifying User-Agent, an HTTP disk cache that honours the server's caching
 * headers (7 days when a response has none), conditional revalidation, and only the tiles MapCompose
 * asks for (the viewport); no prefetch, no offline packs.
 */
object MapTiles {
    /** The one place the tile source is configured. `{z}/{x}/{y}` slippy-map order. */
    const val URL_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    const val FIX_THE_MAP_URL = "https://www.openstreetmap.org/fixthemap"
    const val MAX_ZOOM = 18
    const val TILE_SIZE = 256
    const val PROJECT_URL = "https://github.com/neo-the-on3/orange-box"
    private const val CACHE_BYTES = 50L * 1024 * 1024
    internal const val MIN_CACHE_SECONDS = 7 * 24 * 3600

    fun url(z: Int, x: Int, y: Int, template: String = URL_TEMPLATE): String =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())

    /** e.g. `OrangeBox/0.1.0.41-abc1234 (+https://github.com/neo-the-on3/orange-box)`. */
    fun userAgent(versionName: String): String = "OrangeBox/${versionName.ifBlank { "dev" }} (+$PROJECT_URL)"

    fun versionName(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    fun client(cacheDir: File, userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "osm-tiles"), CACHE_BYTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            val cacheable = response.header("Cache-Control")?.contains("max-age") == true || response.header("Expires") != null
            if (response.isSuccessful && !cacheable) {
                response.newBuilder().header("Cache-Control", "public, max-age=$MIN_CACHE_SECONDS").build()
            } else {
                response
            }
        }
        .build()

    @Volatile private var shared: OkHttpClient? = null

    /** One client (and one disk cache) per process. */
    fun sharedClient(context: Context): OkHttpClient = shared ?: synchronized(this) {
        shared ?: client(context.applicationContext.cacheDir, userAgent(versionName(context))).also { shared = it }
    }

    /**
     * MapCompose asks with (row, col, zoom); slippy maps use x = col, y = row. Failures (offline, HTTP
     * 403/429 if the server limits us) just leave the tile empty: the route is drawn regardless.
     */
    class Provider(private val client: OkHttpClient, private val template: String = URL_TEMPLATE) : TileStreamProvider {
        private val loggedFailure = AtomicBoolean(false)

        override suspend fun getTileStream(row: Int, col: Int, zoomLvl: Int): InputStream? {
            if (zoomLvl > MAX_ZOOM) return null
            val request = Request.Builder().url(url(zoomLvl, col, row, template)).build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        logOnce("http_${response.code}")
                        null
                    } else {
                        ByteArrayInputStream(response.body.bytes())
                    }
                }
            } catch (e: IOException) {
                logOnce("io")
                null
            }
        }

        private fun logOnce(reason: String) {
            if (loggedFailure.compareAndSet(false, true)) OrangeLog.i(OrangeLog.Areas.APP, "map_tiles_unavailable", "reason" to reason)
        }
    }
}
