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
        country: io.github.neotheon3.orangebox.core.driving.DeviceCountry,
    ): io.github.neotheon3.orangebox.core.telemetry.TelemetrySource =
        io.github.neotheon3.orangebox.core.telemetry.LiveTelemetry(
            scope, connection, poller,
            vehicle = kotlinx.coroutines.flow.combine(
                vehicles.activeVehicle,
                prefs.preferences.map { country.petrolBlend(it) }.distinctUntilChanged(),
            ) { v, blend -> v?.let { VehicleProfiles.of(it, blend) } },
            overspeedKph = prefs.preferences.map { it.dashboard.overspeedKph.toDouble() }.distinctUntilChanged(),
        )
}

/** Maps the user's car to what the fuel estimate needs. */
object VehicleProfiles {
    fun of(
        v: io.github.neotheon3.orangebox.core.model.Vehicle,
        blend: io.github.neotheon3.orangebox.core.model.PetrolBlend = io.github.neotheon3.orangebox.core.model.PetrolBlend.E0,
    ) = io.github.neotheon3.orangebox.core.telemetry.VehicleProfile(
        fuelType = when (v.fuel) {
            io.github.neotheon3.orangebox.core.model.VehicleFuel.PETROL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.GASOLINE
            io.github.neotheon3.orangebox.core.model.VehicleFuel.DIESEL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.DIESEL
            io.github.neotheon3.orangebox.core.model.VehicleFuel.LPG -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.LPG
            io.github.neotheon3.orangebox.core.model.VehicleFuel.CNG -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.CNG
            io.github.neotheon3.orangebox.core.model.VehicleFuel.ETHANOL -> io.github.neotheon3.orangebox.core.obd.pid.FuelType.ETHANOL
        },
        displacementLitres = v.displacementCc?.let { it / 1000.0 },
        petrolEthanolPercent = blend.ethanolPercent,
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendAccountModule {
    @dagger.Binds
    abstract fun bindBackendAccount(impl: io.github.neotheon3.orangebox.sync.AppBackendAccount): io.github.neotheon3.orangebox.core.datastore.BackendAccount

    /** Server stats, rankings and points (#68): Fake, or Remote signed in. */
    @dagger.Binds
    abstract fun bindMemberBackend(impl: io.github.neotheon3.orangebox.sync.AppMemberBackend): io.github.neotheon3.orangebox.core.datastore.MemberBackend

    /** Fills the diagnostics' optional report sink: DTC reads and clears are queued for upload (#67). */
    @dagger.Binds
    abstract fun bindDtcReportSink(impl: io.github.neotheon3.orangebox.sync.AppDtcReportSink): io.github.neotheon3.orangebox.core.diagnostics.DtcReportSink
}
