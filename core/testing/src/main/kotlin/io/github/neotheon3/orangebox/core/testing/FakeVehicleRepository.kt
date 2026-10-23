package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.database.VehicleRepository
import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/** In-memory [VehicleRepository]. */
class FakeVehicleRepository(initial: List<Vehicle> = emptyList(), activeId: Long? = null) : VehicleRepository {
    val all = MutableStateFlow(initial)
    val active = MutableStateFlow(activeId)
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    override val vehicles: Flow<List<Vehicle>> = all
    override val activeVehicle: Flow<Vehicle?> = combine(all, active) { list, id -> list.firstOrNull { it.id == id } }

    override suspend fun save(vehicle: Vehicle): Long {
        val id = if (vehicle.id == 0L) nextId++ else vehicle.id
        all.update { list -> list.filterNot { it.id == id } + vehicle.copy(id = id) }
        return id
    }

    override suspend fun delete(id: Long) {
        all.update { list -> list.filterNot { it.id == id } }
        if (active.value == id) active.value = null
    }

    override suspend fun setActive(id: Long?) { active.value = id }
}
