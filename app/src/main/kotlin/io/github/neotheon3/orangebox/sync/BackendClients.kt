package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.datastore.EncryptedSessionStore
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.OkHttpBackendApi
import io.github.neotheon3.orangebox.core.network.ObservableSessionStore
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
class BackendClients(private val sessions: ObservableSessionStore) {
    @Inject constructor(sessions: EncryptedSessionStore) : this(sessions as ObservableSessionStore)

    private var remote: Pair<String, OkHttpBackendApi>? = null
    private val fake by lazy { FakeBackendApi() }

    /** For tests: counts OkHttp clients built (must stay 0 in Off and Fake). */
    var httpClientsBuilt = 0
        private set

    val sessionStore: ObservableSessionStore get() = sessions

    /** The in-process server of Fake mode (debug Simulator: failure scenarios, the 501 switch). */
    val fakeServer: FakeBackendApi get() = fake

    /** Tests: answer Remote with this instead of building an OkHttp client. */
    internal var remoteForTests: BackendApi? = null

    @Synchronized
    fun forConfig(config: BackendConfig): BackendApi? = when (config.mode) {
        BackendMode.OFF -> null
        BackendMode.FAKE -> fake
        BackendMode.REMOTE -> if (!config.isRemoteReady) null else remoteForTests ?: remote?.takeIf { it.first == config.baseUrl }?.second
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
