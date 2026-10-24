package io.github.neotheon3.orangebox.core.database

import android.content.Context
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OrangeBoxDatabase =
        Room.databaseBuilder(context, OrangeBoxDatabase::class.java, OrangeBoxDatabase.NAME)
            .addMigrations(*Migrations.ALL)
            .build()

    @Provides fun provideVehicleDao(db: OrangeBoxDatabase): VehicleDao = db.vehicleDao()
    @Provides fun provideTripDao(db: OrangeBoxDatabase): TripDao = db.tripDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleRepositoryModule {
    @Binds abstract fun bindVehicleRepository(impl: RoomVehicleRepository): VehicleRepository
    @Binds abstract fun bindTripRepository(impl: RoomTripRepository): TripRepository
}
