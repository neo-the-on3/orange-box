package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.database.DtcHistoryRepository
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import kotlinx.coroutines.flow.MutableStateFlow

/** Records calls; [history] is set directly by tests. */
class FakeDtcHistoryRepository(initial: List<DtcRecordEntity> = emptyList()) : DtcHistoryRepository {
    override val history = MutableStateFlow(initial)
    val readings = mutableListOf<Pair<Map<String, List<String>>, Long?>>()
    val cleared = mutableListOf<Long>()

    override suspend fun recordReading(lists: Map<String, List<String>>, vehicleId: Long?, at: Long) {
        readings += lists to vehicleId
    }

    override suspend fun recordCleared(at: Long) {
        cleared += at
    }

    override suspend fun deleteAll() {
        history.value = emptyList()
    }
}
