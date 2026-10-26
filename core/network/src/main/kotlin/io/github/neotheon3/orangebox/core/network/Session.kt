package io.github.neotheon3.orangebox.core.network

/** Tokens of the signed-in member. Stored encrypted on the device; never logged. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val memberId: String,
    /** Epoch millis after which the access token is expired (refreshed early, on 401). */
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long,
)

/** Where [OkHttpBackendApi] reads and writes the session. Android: an encrypted DataStore file. */
interface SessionStore {
    suspend fun read(): Session?

    suspend fun write(session: Session)

    suspend fun clear()
}

fun TokenResponse.toSession(nowMillis: Long) = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    memberId = memberId,
    accessExpiresAtMillis = nowMillis + expiresIn * 1000,
    refreshExpiresAtMillis = nowMillis + refreshExpiresIn * 1000,
)

class InMemorySessionStore(private var session: Session? = null) : SessionStore {
    override suspend fun read() = session

    override suspend fun write(session: Session) {
        this.session = session
    }

    override suspend fun clear() {
        session = null
    }
}
