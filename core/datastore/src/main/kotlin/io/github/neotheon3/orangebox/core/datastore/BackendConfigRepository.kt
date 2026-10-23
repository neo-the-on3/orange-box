package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Source of truth for the backend mode (Off / Fake / Remote) and the Remote base URL. */
interface BackendConfigRepository {
    val config: Flow<BackendConfig>

    suspend fun setMode(mode: BackendMode)

    /** Stores [url] only if it is a valid HTTPS URL (or blank, which clears it). Returns the validation result. */
    suspend fun setBaseUrl(url: String): BaseUrlValidator.Result
}

@Singleton
class DataStoreBackendConfigRepository @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
) : BackendConfigRepository {

    override val config: Flow<BackendConfig> = dataStore.data
        .map { prefs ->
            BackendConfig(
                mode = BackendMode.parse(prefs[KEY_MODE]) ?: BackendMode.DEFAULT,
                baseUrl = prefs[KEY_BASE_URL].orEmpty(),
            )
        }
        .distinctUntilChanged()

    override suspend fun setMode(mode: BackendMode) {
        dataStore.edit { it[KEY_MODE] = mode.wireName }
    }

    override suspend fun setBaseUrl(url: String): BaseUrlValidator.Result {
        val trimmed = url.trim()
        val result = BaseUrlValidator.validate(trimmed)
        when (result) {
            BaseUrlValidator.Result.Valid -> dataStore.edit { it[KEY_BASE_URL] = trimmed }
            BaseUrlValidator.Result.Empty -> dataStore.edit { it.remove(KEY_BASE_URL) }
            else -> Unit // keep the previous valid value
        }
        return result
    }

    internal companion object {
        val KEY_MODE = stringPreferencesKey("backend_mode")
        val KEY_BASE_URL = stringPreferencesKey("backend_base_url")
    }
}
