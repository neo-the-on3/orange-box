package io.github.neotheon3.orangebox.core.database

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>

    /** The vehicle trips and telemetry use, or null when none is set up. */
    val activeVehicle: Flow<Vehicle?>

    /** Inserts or updates [vehicle]; returns its id. */
    suspend fun save(vehicle: Vehicle): Long

    suspend fun delete(id: Long)

    suspend fun setActive(id: Long?)
}

@Singleton
class RoomVehicleRepository @Inject constructor(
    private val dao: VehicleDao,
    private val prefs: AppPreferencesRepository,
) : VehicleRepository {
    var clock: () -> Long = System::currentTimeMillis

    override val vehicles: Flow<List<Vehicle>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val activeVehicle: Flow<Vehicle?> = prefs.preferences.map { it.activeVehicleId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else dao.observe(id).map { it?.toModel() } }

    override suspend fun save(vehicle: Vehicle): Long {
        val id = if (vehicle.id == 0L) {
            dao.insert(VehicleEntity.from(vehicle, clock()))
        } else {
            val existing = dao.get(vehicle.id)
            val updated = VehicleEntity.from(vehicle, existing?.createdAtMillis ?: clock())
            dao.update(existing?.let { updated.copy(clientVehicleId = it.clientVehicleId) } ?: updated); vehicle.id
        }
        OrangeLog.i(OrangeLog.Areas.APP, "vehicle_saved", "id" to id, "fuel" to vehicle.fuel.name)
        return id
    }

    override suspend fun delete(id: Long) {
        dao.delete(id)
        if (prefs.preferences.first().activeVehicleId == id) prefs.setActiveVehicle(null)
    }

    override suspend fun setActive(id: Long?) = prefs.setActiveVehicle(id)
}
