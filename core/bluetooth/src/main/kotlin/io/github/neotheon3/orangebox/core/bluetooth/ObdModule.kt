package io.github.neotheon3.orangebox.core.bluetooth

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.datastore.DongleSelectionRepository
import io.github.neotheon3.orangebox.core.obd.DongleLinkEvents
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ObdModule {
    @Provides @Singleton
    fun provideDongleLinkEvents(
        selection: DongleSelectionRepository,
        @ApplicationScope scope: CoroutineScope,
    ): DongleLinkEvents {
        val selected = selection.selected.map { it?.address }.stateIn(scope, SharingStarted.Eagerly, null)
        return DongleLinkEvents { selected.value }
    }

    @Provides @Singleton
    fun provideObdConnectionManager(
        @ApplicationScope scope: CoroutineScope,
        provider: ObdDongleProvider,
        linkEvents: DongleLinkEvents,
        prefs: AppPreferencesRepository,
    ): ObdConnectionManager {
        val autoStart = prefs.preferences.map { it.autoStartOnDongleConnect }.stateIn(scope, SharingStarted.Eagerly, false)
        return ObdConnectionManager(scope, provider, linkEvents.events, autoStartEnabled = { autoStart.value })
    }

    @Provides
    fun provideObdConnection(manager: ObdConnectionManager): ObdConnection = manager
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ObdBindingsModule {
    @Binds abstract fun bindDongleProvider(impl: DefaultObdDongleProvider): ObdDongleProvider

    @Binds abstract fun bindDongleTester(impl: DefaultDongleTester): DongleTester
}
