package io.github.neotheon3.orangebox.core.model

/**
 * Persisted backend settings.
 *
 * [baseUrl] is only meaningful for [BackendMode.REMOTE]. Remote mode without a valid
 * HTTPS base URL is treated as "not configured" by [isRemoteReady].
 */
data class BackendConfig(
    val mode: BackendMode = BackendMode.DEFAULT,
    val baseUrl: String = "",
) {
    val isRemoteReady: Boolean
        get() = mode == BackendMode.REMOTE && BaseUrlValidator.validate(baseUrl) == BaseUrlValidator.Result.Valid
}

/** Validates a Remote base URL. Only absolute `https://` URLs with a host are accepted (no cleartext). */
object BaseUrlValidator {
    sealed interface Result {
        data object Valid : Result
        data object Empty : Result
        data object NotHttps : Result
        data object Malformed : Result
    }

    private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")

    fun validate(raw: String): Result {
        val url = raw.trim()
        if (url.isEmpty()) return Result.Empty
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return Result.Malformed
        if (!url.substring(0, schemeEnd).equals("https", ignoreCase = true)) return Result.NotHttps
        val rest = url.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || authority.contains('@')) return Result.Malformed
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        if (!hostPattern.matches(host)) return Result.Malformed
        if (authority.contains(':') && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return Result.Malformed
        return Result.Valid
    }
}
