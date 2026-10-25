package io.github.neotheon3.orangebox.core.driving

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.telemetry.RecordedTripTotals
import kotlinx.coroutines.flow.map

@Module
@InstallIn(SingletonComponent::class)
abstract class DrivingModule {
    @Binds abstract fun bindLocationSource(impl: AndroidLocationSource): LocationSource

    @Binds abstract fun bindMotionSource(impl: io.github.neotheon3.orangebox.core.driving.crash.AndroidMotionSource): io.github.neotheon3.orangebox.core.driving.crash.MotionSource

    companion object {
        /** The recorder's carried totals, for the dashboard TRIP / FUEL tiles (same source as the notification). */
        @Provides fun provideRecordedTripTotals(recorder: TripRecorder): RecordedTripTotals =
            RecordedTripTotals { recorder.active.map { it?.totals } }
    }
}
