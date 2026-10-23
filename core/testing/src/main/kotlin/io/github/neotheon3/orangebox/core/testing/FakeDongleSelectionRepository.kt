package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.DongleSelectionRepository
import io.github.neotheon3.orangebox.core.model.SelectedDongle
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [DongleSelectionRepository]. */
class FakeDongleSelectionRepository(initial: SelectedDongle? = null) : DongleSelectionRepository {
    val state = MutableStateFlow(initial)
    override val selected = state
    override suspend fun select(dongle: SelectedDongle?) { state.value = dongle }
}
