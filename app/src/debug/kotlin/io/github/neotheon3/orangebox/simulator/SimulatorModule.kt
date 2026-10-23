package io.github.neotheon3.orangebox.simulator

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.DebugEntries
import io.github.neotheon3.orangebox.core.bluetooth.SimulatedDongleSource
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.obd.ObdTransport
import io.github.neotheon3.orangebox.core.simulator.SimulatedElm327Transport
import io.github.neotheon3.orangebox.core.simulator.SimulatorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SimulatorModule {
    @Provides @Singleton
    fun provideSimulatorStore(@ApplicationContext context: Context): SimulatorStore =
        SimulatorStore(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("simulator") })

    @Provides @Singleton
    fun provideSimulatorController(store: SimulatorStore, @ApplicationScope scope: CoroutineScope): SimulatorController {
        // Debug-only, tiny file: load synchronously so the first connection sees the saved toggles.
        val controller = SimulatorController(runBlocking { store.load() })
        scope.launch { controller.state.drop(1).collect { store.save(it) } }
        return controller
    }

    @Provides @Singleton
    fun provideSimulatedDongle(controller: SimulatorController): SimulatedDongleSource = object : SimulatedDongleSource {
        override val available = true
        override fun createTransport(): ObdTransport = SimulatedElm327Transport(controller)
    }

    @Provides fun provideDebugEntries(): DebugEntries = SimulatorDebugEntries
}
