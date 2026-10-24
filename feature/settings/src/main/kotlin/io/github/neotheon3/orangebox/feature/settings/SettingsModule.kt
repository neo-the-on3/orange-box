package io.github.neotheon3.orangebox.feature.settings

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.driving.FullScreenIntents

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides
    fun provideFullScreenCheck(@ApplicationContext context: Context): FullScreenCheck = FullScreenCheck { FullScreenIntents.canUse(context) }
}
