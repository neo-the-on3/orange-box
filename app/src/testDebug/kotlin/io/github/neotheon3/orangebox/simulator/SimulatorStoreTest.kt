package io.github.neotheon3.orangebox.simulator

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatorStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `state survives a restart and reset restores defaults`() = runTest(UnconfinedTestDispatcher()) {
        val file = tmp.newFile("simulator.preferences_pb").also { it.delete() }
        val custom = SimulatorState(useSimulatedDongle = true, speedKph = 88, dtcs = listOf("P0133"), milOn = true, garbledResponses = true)
        run {
            val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
            val store = SimulatorStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(SimulatorState(), store.load())
            store.save(custom)
            scope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        }
        // "Restart": a fresh DataStore over the same file.
        val scope2 = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val store2 = SimulatorStore(PreferenceDataStoreFactory.create(scope = scope2) { file })
        assertEquals(custom, store2.load())
        store2.save(SimulatorState())
        assertEquals(SimulatorState(), store2.load())
        scope2.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }
}
