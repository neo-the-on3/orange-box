package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.neotheon3.orangebox.core.network.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EncryptedSessionStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val session = Session("access.jwt.value", "refresh-secret", "m-1", 1_000, 2_000, email = "kiran@example.com")

    private fun TestScope.dataStore() = PreferenceDataStoreFactory.create(scope = backgroundScope) {
        tmp.root.resolve("backend_session.preferences_pb")
    }

    @Test fun `round trips and clears`() = runTest(UnconfinedTestDispatcher()) {
        val store = EncryptedSessionStore.forTests(dataStore(), AesGcmSecretBox { key })
        assertNull(store.read())
        store.write(session)
        assertEquals(session, store.read())
        store.clear()
        assertNull(store.read())
    }

    @Test fun `tokens are not stored in the clear`() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore()
        EncryptedSessionStore.forTests(ds, AesGcmSecretBox { key }).write(session)
        val raw = ds.data.first()[EncryptedSessionStore.KEY]!!
        assertFalse(raw.contains("refresh-secret"))
        assertFalse(raw.contains("access.jwt"))
        assertFalse(tmp.root.resolve("backend_session.preferences_pb").readText(Charsets.ISO_8859_1).contains("refresh-secret"))
    }

    @Test fun `a session sealed with another key reads as signed out`() = runTest(UnconfinedTestDispatcher()) {
        val ds = dataStore()
        EncryptedSessionStore.forTests(ds, AesGcmSecretBox { key }).write(session)
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(EncryptedSessionStore.forTests(ds, AesGcmSecretBox { otherKey }).read())
    }
}
