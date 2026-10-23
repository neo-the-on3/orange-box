package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DongleSelectionRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `select persists and clears`() = runTest(UnconfinedTestDispatcher()) {
        val repo = DataStoreDongleSelectionRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) { tmp.newFile("s.preferences_pb").also { it.delete() } },
        )
        assertNull(repo.selected.first())
        repo.select(SelectedDongle("aa:bb:cc:dd:ee:ff", "OBDII"))
        assertEquals(SelectedDongle("AA:BB:CC:DD:EE:FF", "OBDII"), repo.selected.first())
        repo.select(null)
        assertNull(repo.selected.first())
    }
}
