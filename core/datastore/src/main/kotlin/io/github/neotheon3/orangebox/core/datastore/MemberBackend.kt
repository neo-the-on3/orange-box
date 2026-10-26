package io.github.neotheon3.orangebox.core.datastore

import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.BackendApi
import kotlinx.coroutines.flow.Flow

/**
 * The backend behind the member features (#68): server stats, rankings and points. Implemented in the app, which
 * owns the clients. Off never has one, so nothing is shown and no client is built; Remote needs a session.
 */
interface MemberBackend {
    /** Fake, or Remote and signed in ([isAvailable]). */
    val available: Flow<Boolean>

    /** The client to call now, or null when the features aren't available. */
    suspend fun api(): BackendApi?

    companion object {
        /** Fake for demos and tests; Remote only with a session. Local stats (M5) stay the default view. */
        fun isAvailable(mode: BackendMode, signedIn: Boolean): Boolean = mode == BackendMode.FAKE || (mode == BackendMode.REMOTE && signedIn)
    }
}
