package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendMode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Whether sync is on right now: Fake, or Remote with a valid address and a signed-in member. Off never is. */
@Singleton
class SyncEligibility @Inject constructor(
    private val backendConfig: BackendConfigRepository,
    private val clients: BackendClients,
) {
    suspend fun isOn(): Boolean {
        val config = backendConfig.config.first()
        return when (config.mode) {
            BackendMode.OFF -> false
            BackendMode.FAKE -> true
            BackendMode.REMOTE -> config.isRemoteReady && clients.sessionStore.read() != null
        }
    }
}
