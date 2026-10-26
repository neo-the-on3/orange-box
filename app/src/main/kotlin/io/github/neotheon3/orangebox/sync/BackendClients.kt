package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.datastore.EncryptedSessionStore
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.OkHttpBackendApi
import io.github.neotheon3.orangebox.core.network.SessionStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backend client for a mode. **Off: none, and nothing is built** (no OkHttpClient, no request).
 * Fake: one in-process [FakeBackendApi] for the app's lifetime. Remote: OkHttp against the HTTPS base URL,
 * built on first use and rebuilt when the URL changes.
 */
@Singleton
class BackendClients(private val sessions: SessionStore) {
    @Inject constructor(sessions: EncryptedSessionStore) : this(sessions as SessionStore)

    private var remote: Pair<String, OkHttpBackendApi>? = null
    private val fake by lazy { FakeBackendApi() }

    /** For tests: counts OkHttp clients built (must stay 0 in Off and Fake). */
    var httpClientsBuilt = 0
        private set

    val sessionStore: SessionStore get() = sessions

    @Synchronized
    fun forConfig(config: BackendConfig): BackendApi? = when (config.mode) {
        BackendMode.OFF -> null
        BackendMode.FAKE -> fake
        BackendMode.REMOTE -> if (!config.isRemoteReady) null else remote?.takeIf { it.first == config.baseUrl }?.second
            ?: OkHttpBackendApi(config.baseUrl, httpClient(), sessions).also { remote = config.baseUrl to it }
    }

    private fun httpClient(): OkHttpClient {
        httpClientsBuilt++
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
