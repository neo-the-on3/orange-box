package io.github.neotheon3.orangebox.core.datastore

import io.github.neotheon3.orangebox.core.model.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** The display units in effect (the user's choice, else the device country's defaults); bound in core:driving. */
fun interface DisplayUnits {
    fun units(): Flow<UnitPreferences>

    companion object {
        val METRIC = DisplayUnits { flowOf(UnitPreferences()) }
    }
}
