package io.github.neotheon3.orangebox.sync

import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.network.FakeBackendApi
import io.github.neotheon3.orangebox.core.network.InMemorySessionStore
import io.github.neotheon3.orangebox.core.network.Session
import io.github.neotheon3.orangebox.core.testing.FakeBackendConfigRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** #68: the rankings and points screens exist in Fake, and in Remote only with a session; Off builds nothing. */
class AppMemberBackendTest {
    private val sessions = InMemorySessionStore()
    private val remote = FakeBackendApi()

    private fun backend(config: BackendConfig): Pair<AppMemberBackend, BackendClients> {
        val clients = BackendClients(sessions).also { it.remoteForTests = remote }
        return AppMemberBackend(FakeBackendConfigRepository(config), clients) to clients
    }

    @Test fun `off - hidden, no client`() = runTest {
        val (b, clients) = backend(BackendConfig(BackendMode.OFF))
        assertFalse(b.available.first())
        assertNull(b.api())
        assertEquals(0, clients.httpClientsBuilt)
    }

    @Test fun `fake - visible without signing in, in-process server`() = runTest {
        val (b, clients) = backend(BackendConfig(BackendMode.FAKE))
        assertTrue(b.available.first())
        assertSame(clients.fakeServer, b.api())
        assertEquals(0, clients.httpClientsBuilt)
    }

    @Test fun `remote - only with a session`() = runTest {
        val (b, _) = backend(BackendConfig(BackendMode.REMOTE, "https://api.example.com"))
        assertFalse(b.available.first())
        assertNull(b.api())
        sessions.write(Session("a", "r", "m1", Long.MAX_VALUE, Long.MAX_VALUE, email = "k@example.com"))
        assertTrue(b.available.first())
        assertSame(remote, b.api())
    }
}
