package io.github.neotheon3.orangebox.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Tokens of the signed-in member. Stored encrypted on the device; never logged. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val memberId: String,
    /** Epoch millis after which the access token is expired (refreshed early, on 401). */
    val accessExpiresAtMillis: Long,
    val refreshExpiresAtMillis: Long,
    /** The address signed in with, shown in Settings. Kept across refreshes. */
    val email: String? = null,
)

/** Where [OkHttpBackendApi] reads and writes the session. Android: an encrypted DataStore file. */
interface SessionStore {
    suspend fun read(): Session?

    suspend fun write(session: Session)

    suspend fun clear()
}

/** A [SessionStore] whose changes can be observed (Settings shows who is signed in). */
interface ObservableSessionStore : SessionStore {
    /** The session as it changes; null when signed out. */
    val sessions: Flow<Session?>
}

fun TokenResponse.toSession(nowMillis: Long, email: String? = null) = Session(
    accessToken = accessToken,
    refreshToken = refreshToken,
    memberId = memberId,
    accessExpiresAtMillis = nowMillis + expiresIn * 1000,
    refreshExpiresAtMillis = nowMillis + refreshExpiresIn * 1000,
    email = email,
)

class InMemorySessionStore(session: Session? = null) : ObservableSessionStore {
    private val state = MutableStateFlow(session)

    override val sessions: Flow<Session?> = state

    override suspend fun read() = state.value

    override suspend fun write(session: Session) {
        state.value = session
    }

    override suspend fun clear() {
        state.value = null
    }
}
