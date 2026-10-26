package io.github.neotheon3.orangebox.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift check against docs/backend/openapi-v2.yaml: every operation the client calls exists, response DTOs
 * don't default a field the spec requires (a missing one must fail decoding), and request DTOs send every
 * required field. A line-based reader is enough for the spec's layout; it isn't a general YAML parser.
 */
class SpecContractTest {
    private val spec: List<String> = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "docs/backend/openapi-v2.yaml") }
        .first { it.isFile }
        .readLines()

    private fun hasOperation(path: String, method: String): Boolean {
        val start = spec.indexOfFirst { it == "  $path:" }
        if (start < 0) return false
        val end = (start + 1 until spec.size).firstOrNull { spec[it].matches(Regex("^ {0,2}\\S.*")) } ?: spec.size
        return (start + 1 until end).any { spec[it] == "    $method:" }
    }

    /** Union of the `required: [...]` lists in a schema block, following `$ref`s (e.g. inside `allOf`). */
    private fun required(schema: String): Set<String> {
        val start = spec.indexOfFirst { it == "    $schema:" }
        check(start >= 0) { "schema $schema not in spec" }
        val end = (start + 1 until spec.size).firstOrNull { spec[it].matches(Regex("^ {0,4}\\S.*")) } ?: spec.size
        val block = spec.subList(start + 1, end)
        val own = block.mapNotNull { Regex("^ {6}(?: {4}| {2}- )?required: \\[(.*)]").find(it)?.groupValues?.get(1) }
            .flatMap { it.split(',').map(String::trim) }
        val refs = block.mapNotNull { Regex("^ {8}- \\\$ref: '#/components/schemas/(\\w+)'").find(it)?.groupValues?.get(1) }
        return (own + refs.flatMap { required(it) }).toSet()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.nonOptional(): Set<String> =
        (0 until elementsCount).filterNot { isElementOptional(it) }.map { getElementName(it) }.toSet()

    @OptIn(ExperimentalSerializationApi::class)
    private fun SerialDescriptor.names(): Set<String> = (0 until elementsCount).map { getElementName(it) }.toSet()

    @Test fun `every operation the client calls is in the spec`() {
        listOf(
            "/v1/health" to "get", "/v1/auth/otp" to "post", "/v1/auth/token" to "post", "/v1/auth/logout" to "post",
            "/v1/vehicles" to "get", "/v1/vehicles" to "post", "/v1/trips" to "get", "/v1/trips/deletions" to "get",
            "/v1/trips/batch" to "post", "/v1/trips/{tripId}" to "delete", "/v1/telemetry/batches" to "post",
        ).forEach { (path, method) -> assertTrue("$method $path", hasOperation(path, method)) }
    }

    @Test fun `response DTOs require what the spec requires`() {
        mapOf(
            "TokenResponse" to TokenResponse.serializer().descriptor,
            "OtpResponse" to OtpResponse.serializer().descriptor,
            "Health" to HealthDto.serializer().descriptor,
            "Vehicle" to VehicleDto.serializer().descriptor,
            "Trip" to TripDto.serializer().descriptor,
            "TripDeletion" to TripDeletionDto.serializer().descriptor,
            "TelemetryBatchAck" to TelemetryBatchAckDto.serializer().descriptor,
        ).forEach { (schema, d) ->
            val req = required(schema)
            assertTrue("$schema has no required fields?", req.isNotEmpty())
            // `archived` defaults to false on purpose (older servers); everything else we model must not default.
            val modelled = req.intersect(d.names()) - "archived"
            assertTrue("$schema: $modelled must be non-optional in ${d.nonOptional()}", d.nonOptional().containsAll(modelled))
        }
        assertTrue(TripPage.serializer().descriptor.names().containsAll(required("TripPage")))
        assertTrue(TripDeletionPage.serializer().descriptor.names().containsAll(required("TripDeletionPage")))
        assertTrue(BatchItemResult.serializer().descriptor.names().containsAll(setOf("clientId", "status", "id", "problem")))
    }

    @Test fun `request DTOs carry every field the spec requires`() {
        mapOf(
            "TripUpload" to TripUploadDto.serializer().descriptor,
            "VehicleCreate" to VehicleCreateDto.serializer().descriptor,
            "EmailOtpGrant" to EmailOtpGrant.serializer().descriptor,
            "RefreshGrant" to RefreshGrant.serializer().descriptor,
            "OtpRequest" to OtpRequest.serializer().descriptor,
            "TripBatch" to TripBatchDto.serializer().descriptor,
            "TelemetryBatch" to TelemetryBatchDto.serializer().descriptor,
            "Sample" to SampleDto.serializer().descriptor,
            "Event" to EventDto.serializer().descriptor,
        ).forEach { (schema, d) ->
            val req = required(schema)
            assertTrue("$schema has no required fields?", req.isNotEmpty())
            assertTrue("$schema: $req ⊆ ${d.names()}", d.names().containsAll(req))
        }
    }

    /** Property names of a schema block (its own `properties:` maps, plus those of `$ref`s), one level deep. */
    private fun properties(schema: String): Set<String> {
        val start = spec.indexOfFirst { it == "    $schema:" }
        check(start >= 0) { "schema $schema not in spec" }
        val end = (start + 1 until spec.size).firstOrNull { spec[it].matches(Regex("^ {0,4}\\S.*")) } ?: spec.size
        val block = spec.subList(start + 1, end)
        val names = mutableSetOf<String>()
        block.forEachIndexed { i, line ->
            val indent = line.indexOfFirst { it != ' ' }
            if (line.trim() != "properties:") return@forEachIndexed
            for (next in block.drop(i + 1)) {
                val ni = next.indexOfFirst { it != ' ' }
                if (next.isBlank()) continue
                if (ni <= indent) break
                if (ni == indent + 2) Regex("^\\s*(\\w+):").find(next)?.let { names += it.groupValues[1] }
            }
        }
        val refs = block.mapNotNull { Regex("^ {8}- \\\$ref: '#/components/schemas/(\\w+)'").find(it)?.groupValues?.get(1) }
        return names + refs.flatMap { properties(it) }
    }

    /** #69: a DTO field the spec doesn't define (a typo or a renamed field) fails the build. */
    @Test fun `every DTO field is a property of its spec schema`() {
        mapOf(
            "TripUpload" to TripUploadDto.serializer().descriptor,
            "VehicleCreate" to VehicleCreateDto.serializer().descriptor,
            "Vehicle" to VehicleDto.serializer().descriptor,
            "Trip" to TripDto.serializer().descriptor,
            "TripDeletion" to TripDeletionDto.serializer().descriptor,
            "TokenResponse" to TokenResponse.serializer().descriptor,
            "OtpResponse" to OtpResponse.serializer().descriptor,
            "EmailOtpGrant" to EmailOtpGrant.serializer().descriptor,
            "TelemetryBatch" to TelemetryBatchDto.serializer().descriptor,
            "TelemetryBatchAck" to TelemetryBatchAckDto.serializer().descriptor,
            "Sample" to SampleDto.serializer().descriptor,
            "Event" to EventDto.serializer().descriptor,
            "Problem" to ProblemDto.serializer().descriptor,
        ).forEach { (schema, d) ->
            val props = properties(schema)
            assertTrue("$schema has no properties?", props.isNotEmpty())
            val extra = d.names() - props - PENDING_SPEC_FIELDS[schema].orEmpty()
            assertTrue("$schema: DTO fields $extra are not in the spec (${props.sorted()})", extra.isEmpty())
        }
    }

    companion object {
        /**
         * Fields the client already models from spec text that isn't on `main` yet.
         * TODO(M9 question 1 for Go Developer): `Problem.reason` is only on PR #183's branch; drop this entry once
         *  #183 merges (the test then checks it like every other field).
         */
        val PENDING_SPEC_FIELDS: Map<String, Set<String>> = mapOf("Problem" to setOf("reason"))
    }
}
