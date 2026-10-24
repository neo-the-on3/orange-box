package io.github.neotheon3.orangebox

import android.content.Intent
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.core.database.TripRepository
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.datastore.BackendConfigRepository
import io.github.neotheon3.orangebox.core.model.BackendMode
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** Debug-only: honours `--es backend_mode off|fake|remote`, `--es onboarding skip|reset` and `--es demo_trips seed`. */
@Singleton
class DebugLaunchOverrides @Inject constructor(
    private val backendConfigRepository: BackendConfigRepository,
    private val appPreferences: AppPreferencesRepository,
    private val trips: TripRepository,
) : LaunchOverrides {
    override fun apply(intent: Intent?): BackendMode? {
        applyOnboarding(intent?.getStringExtra(LaunchExtras.ONBOARDING))
        if (intent?.getStringExtra(LaunchExtras.DEMO_TRIPS) == "seed") {
            val added = runBlocking { DemoTrips.seed(trips, System.currentTimeMillis()) }
            Log.i(TAG, "demo_trips seeded: $added")
        }
        val raw = intent?.getStringExtra(LaunchExtras.BACKEND_MODE) ?: return null
        val mode = LaunchExtras.resolveBackendMode(raw)
        if (mode == null) {
            Log.w(TAG, "Ignoring invalid backend_mode extra '$raw'; keeping saved mode")
            return null
        }
        // Tiny DataStore write; blocking keeps the first frame consistent with the requested mode.
        runBlocking { backendConfigRepository.setMode(mode) }
        Log.i(TAG, "backend_mode override applied: ${mode.wireName}")
        return mode
    }

    private fun applyOnboarding(raw: String?) {
        raw ?: return
        val value = LaunchExtras.resolveOnboarding(raw)
        if (value == null) {
            Log.w(TAG, "Ignoring invalid onboarding extra '$raw'")
            return
        }
        val done = value == LaunchExtras.Onboarding.SKIP
        runBlocking {
            appPreferences.setDisclaimerAccepted(done)
            appPreferences.setOnboardingComplete(done)
        }
        Log.i(TAG, "onboarding override applied: ${value.name.lowercase()}")
    }

    private companion object { const val TAG = "OrangeBox" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugLaunchModule {
    @Binds abstract fun bindLaunchOverrides(impl: DebugLaunchOverrides): LaunchOverrides
}

