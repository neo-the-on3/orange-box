package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendConfig
import io.github.neotheon3.orangebox.core.model.BackendMode
import io.github.neotheon3.orangebox.core.model.BaseUrlValidator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [BackendConfigRepository] with the same validation rules as the DataStore implementation. */
class FakeBackendConfigRepository(initial: BackendConfig = BackendConfig()) : BackendConfigRepository {
    private val state = MutableStateFlow(initial)
    override val config: StateFlow<BackendConfig> = state

    override suspend fun setMode(mode: BackendMode) {
        state.value = state.value.copy(mode = mode)
    }

    override suspend fun setBaseUrl(url: String): BaseUrlValidator.Result {
        val trimmed = url.trim()
        val result = BaseUrlValidator.validate(trimmed)
        when (result) {
            BaseUrlValidator.Result.Valid -> state.value = state.value.copy(baseUrl = trimmed)
            BaseUrlValidator.Result.Empty -> state.value = state.value.copy(baseUrl = "")
            else -> Unit
        }
        return result
    }
}
