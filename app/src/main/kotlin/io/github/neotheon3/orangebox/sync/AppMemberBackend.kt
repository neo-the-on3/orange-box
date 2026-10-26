package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.BackendApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MemberBackend] for the rankings and points screens (#68): Fake's in-process server, or Remote with a session.
 * Off never builds a client, and neither does Remote without a session.
 */
@Singleton
class AppMemberBackend @Inject constructor(
    private val config: BackendConfigRepository,
    private val clients: BackendClients,
) : MemberBackend {
    override val available: Flow<Boolean> = combine(config.config, clients.sessionStore.sessions) { cfg, session ->
        MemberBackend.isAvailable(cfg.mode, session != null) && (cfg.mode != BackendMode.REMOTE || cfg.isRemoteReady)
    }.distinctUntilChanged()

    override suspend fun api(): BackendApi? {
        val cfg = config.config.first()
        if (!MemberBackend.isAvailable(cfg.mode, clients.sessionStore.read() != null)) return null
        return clients.forConfig(cfg)
    }
}
