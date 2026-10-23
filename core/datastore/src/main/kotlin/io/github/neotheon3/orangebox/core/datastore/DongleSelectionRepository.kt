package io.github.neotheon3.orangebox.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface DongleSelectionRepository {
    val selected: Flow<SelectedDongle?>

    /** Selects [dongle], or clears the selection when null. */
    suspend fun select(dongle: SelectedDongle?)
}

@Singleton
class DataStoreDongleSelectionRepository @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>,
) : DongleSelectionRepository {
    override val selected: Flow<SelectedDongle?> = dataStore.data
        .map { p -> p[KEY_ADDRESS]?.let { SelectedDongle(it, p[KEY_NAME].orEmpty()) } }
        .distinctUntilChanged()

    override suspend fun select(dongle: SelectedDongle?) {
        dataStore.edit {
            if (dongle == null) {
                it.remove(KEY_ADDRESS); it.remove(KEY_NAME)
            } else {
                it[KEY_ADDRESS] = dongle.address.uppercase(); it[KEY_NAME] = dongle.name
            }
        }
    }

    internal companion object {
        val KEY_ADDRESS = stringPreferencesKey("obd_dongle_address")
        val KEY_NAME = stringPreferencesKey("obd_dongle_name")
    }
}
