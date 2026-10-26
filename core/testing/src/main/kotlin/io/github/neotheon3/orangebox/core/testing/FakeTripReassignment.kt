package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.database.TripReassignment
import io.github.neotheon3.orangebox.core.model.Vehicle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** In-memory [TripReassignment]: [orphanedTripIds] is set by the test; [move] drops the moved ids and records them. */
class FakeTripReassignment(orphaned: Set<Long> = emptySet(), var vehicles: List<Vehicle> = emptyList()) : TripReassignment {
    private val state = MutableStateFlow(orphaned)
    override val orphanedTripIds: StateFlow<Set<Long>> = state
    val moves = mutableListOf<Pair<Set<Long>, Long>>()

    fun setOrphaned(ids: Set<Long>) {
        state.value = ids
    }

    override suspend fun targets(tripIds: Set<Long>): List<Vehicle> = vehicles

    override suspend fun move(tripIds: Set<Long>, vehicleId: Long): Int {
        val movable = tripIds intersect state.value
        if (movable.isEmpty() || vehicles.none { it.id == vehicleId }) return 0
        moves += movable to vehicleId
        state.value = state.value - movable
        return movable.size
    }
}
