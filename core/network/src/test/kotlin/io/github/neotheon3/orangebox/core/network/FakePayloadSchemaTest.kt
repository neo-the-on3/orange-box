package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * #66: what [FakeBackendApi] answers is valid against the schemas of docs/backend/openapi-v2.yaml, so Fake mode
 * shows the shapes a real server sends. A small JSON Schema subset is checked: `$ref`, `allOf`, `type` (with
 * `null`), `required`, `properties` / `additionalProperties: false`, `enum`, `items`, bounds, `pattern`, lengths
 * and the `date` / `date-time` formats.
 *
 * The app's JSON leaves nulls out ([BackendJson]), so a required property that is absent passes only when its
 * schema allows `null` (e.g. `PageMeta.nextCursor` on the last page); the app decodes both the same way.
 */
class FakePayloadSchemaTest {
    @Suppress("UNCHECKED_CAST")
    private val schemas: Map<String, Map<String, Any?>> = run {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/backend/openapi-v2.yaml") }.first { it.isFile }
        val root = Yaml(LoaderOptions().apply { codePointLimit = 16 * 1024 * 1024 }).load<Map<String, Any?>>(file.readText())
        ((root["components"] as Map<String, Any?>)["schemas"] as Map<String, Map<String, Any?>>)
    }

    /** As a server sends it: defaults written (`archived: false`), nulls left out like the app's [BackendJson]. */
    private val wire = kotlinx.serialization.json.Json(BackendJson) { encodeDefaults = true }

    private fun <T> check(schema: String, serializer: KSerializer<T>, value: T) {
        val json = wire.encodeToJsonElement(serializer, value)
        val errors = mutableListOf<String>()
        validate(json, mapOf("\$ref" to "#/components/schemas/$schema"), "$", errors)
        if (errors.isNotEmpty()) fail("$schema: ${errors.joinToString("\n")}\n$json")
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolve(s: Map<String, Any?>): Map<String, Any?> =
        (s["\$ref"] as? String)?.let { resolve(schemas.getValue(it.substringAfterLast('/'))) } ?: s

    private fun types(s: Map<String, Any?>): List<String>? = when (val t = s["type"]) {
        is String -> listOf(t)
        is List<*> -> t.map { it.toString() }
        else -> null
    }

    /** Whether [s] (following refs and allOf) accepts `null`. */
    @Suppress("UNCHECKED_CAST")
    private fun allowsNull(s0: Map<String, Any?>): Boolean {
        val s = resolve(s0)
        types(s)?.let { return "null" in it }
        return (s["allOf"] as? List<Map<String, Any?>>)?.all(::allowsNull) ?: false
    }

    @Suppress("UNCHECKED_CAST")
    private fun validate(v: JsonElement, s0: Map<String, Any?>, path: String, errors: MutableList<String>) {
        val s = resolve(s0)
        (s["allOf"] as? List<Map<String, Any?>>)?.forEach { validate(v, it, path, errors) }
        val types = types(s)
        if (v is JsonNull) {
            if (types != null && "null" !in types) errors += "$path: null not allowed ($types)"
            return
        }
        if (types != null && types.none { matchesType(v, it) }) {
            errors += "$path: $v is not $types"
            return
        }
        (s["enum"] as? List<*>)?.let { e -> if ((v as? JsonPrimitive)?.content !in e.map { it.toString() }) errors += "$path: $v not in $e" }
        if (v is JsonPrimitive && v.isString) {
            val str = v.content
            (s["pattern"] as? String)?.let { if (!Regex(it).containsMatchIn(str)) errors += "$path: '$str' !~ $it" }
            (s["minLength"] as? Int)?.let { if (str.length < it) errors += "$path: shorter than $it" }
            (s["maxLength"] as? Int)?.let { if (str.length > it) errors += "$path: longer than $it" }
            when (s["format"]) {
                "date" -> runCatching { LocalDate.parse(str) }.onFailure { errors += "$path: '$str' is not a date" }
                "date-time" -> runCatching { OffsetDateTime.parse(str) }.onFailure { errors += "$path: '$str' is not a date-time" }
            }
        }
        if (v is JsonPrimitive && !v.isString) {
            v.doubleOrNull?.let { d ->
                (s["minimum"] as? Number)?.let { if (d < it.toDouble()) errors += "$path: $d < $it" }
                (s["maximum"] as? Number)?.let { if (d > it.toDouble()) errors += "$path: $d > $it" }
            }
        }
        if (v is JsonObject) {
            val props = (s["properties"] as? Map<String, Map<String, Any?>>).orEmpty()
            (s["required"] as? List<String>)?.forEach { r ->
                if (r !in v && !(props[r]?.let(::allowsNull) ?: false)) errors += "$path: missing required '$r'"
            }
            if (s["additionalProperties"] == false) {
                val all = props.keys + (s["allOf"] as? List<Map<String, Any?>>).orEmpty().flatMap {
                    (resolve(it)["properties"] as? Map<String, Any?>).orEmpty().keys
                }
                (v.keys - all).forEach { errors += "$path: unknown property '$it'" }
            }
            v.forEach { (k, child) -> props[k]?.let { validate(child, it, "$path.$k", errors) } }
        }
        if (v is JsonArray) {
            (s["items"] as? Map<String, Any?>)?.let { item -> v.forEachIndexed { i, child -> validate(child, item, "$path[$i]", errors) } }
            (s["maxItems"] as? Int)?.let { if (v.size > it) errors += "$path: more than $it items" }
        }
    }

    private fun matchesType(v: JsonElement, type: String): Boolean = when (type) {
        "object" -> v is JsonObject
        "array" -> v is JsonArray
        "string" -> v is JsonPrimitive && v.isString
        "boolean" -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        "integer" -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        "number" -> v is JsonPrimitive && !v.isString && v.doubleOrNull != null
        "null" -> v is JsonNull
        else -> true
    }

    private val now = Instant.parse("2026-10-21T08:00:00Z")

    private suspend fun seeded(): FakeBackendApi {
        val fake = FakeBackendApi(clock = { now }).apply { rankingPageSize = 5; ledgerPageSize = 2 }
        val v = fake.createVehicle(VehicleCreateDto("cv1", make = "Maruti Suzuki", model = "Celerio", fuelType = FuelTypes.GASOLINE, nickname = "Celerio"), "k")
        listOf(1L, 2L, 3L).forEach { d ->
            val start = now.minusSeconds(d * 3600 * 20)
            fake.seedTrip(
                TripUploadDto(
                    "t$d", v.id, TripUploadDto.STATE_COMPLETED, start, start.plusSeconds(1800), endedAt = start.plusSeconds(1800),
                    durationS = 1800, distanceKm = 12.0 * d, maxSpeedKmh = 80.0, harshAccelCount = 1, harshBrakeCount = 0, fuelUsedL = 1.1 * d,
                    fuelCutTimeS = 60.0, idleTimeS = 120.0, ecoSpeedTimeS = 900.0, overSpeedTimeS = 30.0,
                ),
            )
        }
        return fake
    }

    @Test fun `profile, stats, rankings and points payloads match the spec`() = runTest {
        val fake = seeded()
        val me = fake.getMe()
        check("Profile", ProfileDto.serializer(), me.value)
        val opted = fake.patchMe(ProfilePatchDto(rankingOptIn = true), me.etag)
        check("Profile", ProfileDto.serializer(), opted.value)
        for (p in Periods.ALL_VALUES) check("StatsSummary", StatsSummaryDto.serializer(), fake.getStatsSummary(p, "Asia/Kolkata"))
        check("StatsSummary", StatsSummaryDto.serializer(), fake.getStatsSummary(Periods.DAY, "UTC", date = "2020-01-01"))
        for (m in RankingMetrics.ALL) {
            var cursor: String? = null
            do {
                val page = fake.listRanking(m, Periods.MONTH, "Asia/Kolkata", cursor = cursor)
                check("RankingPage", RankingPageDto.serializer(), page)
                cursor = page.nextCursor
            } while (cursor != null)
            check("MyRanking", MyRankingDto.serializer(), fake.getMyRanking(m, Periods.MONTH, "Asia/Kolkata"))
        }
        check("PointsBalance", PointsBalanceDto.serializer(), fake.getPointsBalance())
        var cursor: String? = null
        var pages = 0
        do {
            val page = fake.listPointsLedger(cursor)
            check("PointsLedgerPage", PointsLedgerPageDto.serializer(), page)
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue("ledger is paginated", pages > 1)
    }

    @Test fun `vehicles, trips, auth and errors match the spec`() = runTest {
        val fake = seeded()
        check("Health", HealthDto.serializer(), fake.health())
        check("OtpResponse", OtpResponse.serializer(), fake.requestOtp("a@example.com"))
        check("TokenResponse", TokenResponse.serializer(), fake.signIn("a@example.com", "000000"))
        val vehicles = fake.listVehicles()
        check("VehiclePage", VehiclePage.serializer(), vehicles)
        check("Vehicle", VehicleDto.serializer(), fake.getVehicle(vehicles.items.first().id).value)
        fake.pageSize = 2
        var page = fake.listTrips()
        check("TripPage", TripPage.serializer(), page)
        while (page.nextCursor != null) {
            page = fake.listTrips(cursor = page.nextCursor)
            check("TripPage", TripPage.serializer(), page)
        }
        fake.deleteTripElsewhere("t1")
        check("TripDeletionPage", TripDeletionPage.serializer(), fake.listTripDeletions(page.nextSince!!))

        fake.arm(FakeBackendApi.Scenario.CONFLICT_412)
        val me = fake.getMe()
        val conflict = runCatching { fake.patchMe(ProfilePatchDto(rankingOptIn = true), me.etag) }.exceptionOrNull() as ApiError.Http
        assertEquals(412, conflict.status)
        check("Problem", ProblemDto.serializer(), conflict.problem!!)
        fake.arm(FakeBackendApi.Scenario.PROBLEM_500)
        val problem = runCatching { fake.listVehicles() }.exceptionOrNull() as ApiError.Http
        assertEquals(500, problem.status)
        check("Problem", ProblemDto.serializer(), problem.problem!!)
    }
}
