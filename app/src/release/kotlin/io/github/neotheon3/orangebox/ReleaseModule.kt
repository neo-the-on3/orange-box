package io.github.neotheon3.orangebox

import android.content.Intent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.bluetooth.NoSimulatedDongle
import io.github.neotheon3.orangebox.core.bluetooth.SimulatedDongleSource
import io.github.neotheon3.orangebox.core.model.BackendMode

/** Release builds ignore launch-intent overrides and have no debug destinations. */
@Module
@InstallIn(SingletonComponent::class)
object ReleaseModule {
    @Provides fun provideLaunchOverrides(): LaunchOverrides = ReleaseLaunchOverrides

    @Provides fun provideDebugEntries(): DebugEntries = NoDebugEntries

    @Provides fun provideSimulatedDongle(): SimulatedDongleSource = NoSimulatedDongle
}

/** Ignores every launch extra, including `backend_mode`. */
object ReleaseLaunchOverrides : LaunchOverrides {
    override fun apply(intent: Intent?): BackendMode? = null
}
