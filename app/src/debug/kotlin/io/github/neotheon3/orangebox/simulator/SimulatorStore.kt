package io.github.neotheon3.orangebox.simulator

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.neotheon3.orangebox.core.simulator.SimulatorState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/** Persists [SimulatorState] as JSON so Simulator settings survive app restarts. */
class SimulatorStore(private val dataStore: DataStore<Preferences>) {
    suspend fun load(): SimulatorState = dataStore.data.first()[KEY]?.let {
        runCatching { json.decodeFromString(SimulatorState.serializer(), it) }.getOrNull()
    } ?: SimulatorState()

    suspend fun save(state: SimulatorState) {
        dataStore.edit { it[KEY] = json.encodeToString(SimulatorState.serializer(), state) }
    }

    private companion object {
        val KEY = stringPreferencesKey("simulator_state")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
