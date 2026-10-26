package io.github.neotheon3.orangebox.core.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Spec `UtcInstant` / `date-time`. Writes `Instant.toString()`: UTC with a `Z` and 0–9 fractional digits, which
 * the spec's telemetry pattern accepts as is. Reads any RFC 3339 date-time, so `updatedAt` with fewer than 3
 * fractional digits (or none) and a numeric offset both parse; values are compared as [Instant]s, never as
 * strings.
 */
object UtcInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("UtcInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Instant = parse(decoder.decodeString())

    fun format(value: Instant): String = value.toString()

    fun parse(value: String): Instant = try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        OffsetDateTime.parse(value).toInstant()
    }
}
