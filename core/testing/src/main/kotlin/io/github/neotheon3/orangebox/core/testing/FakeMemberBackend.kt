package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.MemberBackend
import io.github.neotheon3.orangebox.core.network.BackendApi
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import kotlinx.coroutines.flow.MutableStateFlow

/** [MemberBackend] over an in-process [FakeBackendApi]; [available] can be switched off. */
class FakeMemberBackend(val server: FakeBackendApi = FakeBackendApi(), available: Boolean = true) : MemberBackend {
    override val available = MutableStateFlow(available)

    override suspend fun api(): BackendApi? = server.takeIf { available.value }
}
