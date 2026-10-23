package io.github.neotheon3.orangebox

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object TelemetryModule {
    @Provides @Singleton
    fun providePidPoller(connection: io.github.neotheon3.orangebox.core.obd.ObdConnection): io.github.neotheon3.orangebox.core.obd.pid.PidPoller =
        io.github.neotheon3.orangebox.core.obd.pid.PidPoller(connection)

    @Provides @Singleton
    fun provideTelemetrySource(
        @ApplicationScope scope: CoroutineScope,
        connection: io.github.neotheon3.orangebox.core.obd.ObdConnection,
        poller: io.github.neotheon3.orangebox.core.obd.pid.PidPoller,
    ): io.github.neotheon3.orangebox.core.telemetry.TelemetrySource =
        io.github.neotheon3.orangebox.core.telemetry.LiveTelemetry(scope, connection, poller)
}
