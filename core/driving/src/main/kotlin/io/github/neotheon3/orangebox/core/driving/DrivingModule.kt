package io.github.neotheon3.orangebox.core.driving

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DrivingModule {
    @Binds abstract fun bindLocationSource(impl: AndroidLocationSource): LocationSource
}
