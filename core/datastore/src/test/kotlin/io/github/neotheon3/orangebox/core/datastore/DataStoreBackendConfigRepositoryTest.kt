package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreBackendConfigRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repo() = DataStoreBackendConfigRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("settings.preferences_pb").also { it.delete() } },
    )

    @Test fun `defaults to off with no url`() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(BackendConfig(BackendMode.OFF, ""), repo().config.first())
    }

    @Test fun `persists mode changes`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repo()
        repo.config.test {
            assertEquals(BackendMode.OFF, awaitItem().mode)
            repo.setMode(BackendMode.FAKE)
            assertEquals(BackendMode.FAKE, awaitItem().mode)
            repo.setMode(BackendMode.REMOTE)
            assertEquals(BackendMode.REMOTE, awaitItem().mode)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `stores only valid https base urls`() = runTest(UnconfinedTestDispatcher()) {
        val repo = repo()
        assertEquals(BaseUrlValidator.Result.Valid, repo.setBaseUrl(" https://api.example.com "))
        assertEquals("https://api.example.com", repo.config.first().baseUrl)
        assertEquals(BaseUrlValidator.Result.NotHttps, repo.setBaseUrl("http://evil.example"))
        assertEquals("https://api.example.com", repo.config.first().baseUrl)
        assertEquals(BaseUrlValidator.Result.Empty, repo.setBaseUrl(""))
        assertEquals("", repo.config.first().baseUrl)
    }
}
