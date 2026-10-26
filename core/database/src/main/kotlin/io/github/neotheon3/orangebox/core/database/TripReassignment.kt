package io.github.neotheon3.orangebox.core.database

import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Trips of a vehicle deleted on another device (#203). Sync marks such a vehicle gone; trips that exist only on this
 * phone stay, but can't upload because their vehicle has no server copy. The user can move them to another vehicle;
 * the next sync uploads them under it (creating that vehicle on the server if needed).
 */
interface TripReassignment {
    /** Ids of the trips whose vehicle was deleted elsewhere. Empty in Off mode (nothing is ever marked gone). */
    val orphanedTripIds: Flow<Set<Long>>

    /** Vehicles [tripIds] can move to: on this phone, not deleted on the server, of the trips' account (or none). */
    suspend fun targets(tripIds: Set<Long>): List<Vehicle>

    /** Moves those of [tripIds] whose vehicle is gone to [vehicleId]. Returns how many moved. */
    suspend fun move(tripIds: Set<Long>, vehicleId: Long): Int
}

class RoomTripReassignment @Inject constructor(private val dao: SyncDao) : TripReassignment {
    override val orphanedTripIds: Flow<Set<Long>> = dao.observeTripsOfGoneVehicles().map { it.toSet() }.distinctUntilChanged()

    override suspend fun targets(tripIds: Set<Long>): List<Vehicle> {
        if (tripIds.isEmpty()) return emptyList()
        // Another account's vehicle would upload the trips into that account's car: only the trips' own, or unassigned.
        val accounts = dao.accountsOfTrips(tripIds).toSet()
        return dao.vehiclesNotGone().filter { it.accountId == null || it.accountId in accounts }.map { it.toModel() }
    }

    override suspend fun move(tripIds: Set<Long>, vehicleId: Long): Int =
        if (tripIds.isEmpty()) 0 else dao.moveTripsOfGoneVehicles(tripIds, vehicleId)
}
