package io.github.neotheon3.orangebox

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.common.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
object VersionModule {
    @Provides
    fun provideAppVersion(): io.github.neotheon3.orangebox.core.model.AppVersion = io.github.neotheon3.orangebox.core.model.AppVersion(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        gitSha = BuildConfig.GIT_SHA,
        ciRun = BuildConfig.CI_RUN,
        debug = BuildConfig.DEBUG,
    )
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
        vehicles: io.github.neotheon3.orangebox.core.database.VehicleRepository,
        prefs: io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository,
    ): io.github.neotheon3.orangebox.core.telemetry.TelemetrySource =
        io.github.neotheon3.orangebox.core.telemetry.LiveTelemetry(
            scope, connection, poller,
            vehicle = vehicles.activeVehicle.map { it?.let(VehicleProfiles::of) },
            overspeedKph = prefs.preferences.map { it.dashboard.overspeedKph.toDouble() }.distinctUntilChanged(),
        )
}

/** Maps the user's car to what the fuel estimate needs. */
object VehicleProfiles {
    fun of(v: io.github.neotheon3.orangebox.core.model.Vehicle) = io.github.neotheon3.orangebox.core.telemetry.VehicleProfile(
        fuelType = when (v.fuel) {
            io.github.neotheon3.orangebox.core.model.VehicleFuel.PETROL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.GASOLINE
            io.github.neotheon3.orangebox.core.model.VehicleFuel.DIESEL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.DIESEL
            io.github.neotheon3.orangebox.core.model.VehicleFuel.LPG -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.LPG
            io.github.neotheon3.orangebox.core.model.VehicleFuel.CNG -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.CNG
            io.github.neotheon3.orangebox.core.model.VehicleFuel.ETHANOL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.ETHANOL
        },
        displacementLitres = v.displacementCc?.let { it / 1000.0 },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendAccountModule {
    @dagger.Binds
    abstract fun bindBackendAccount(impl: io.github.neotheon3.orangebox.sync.AppBackendAccount): io.github.neotheon3.orangebox.core.datastore.BackendAccount
}
