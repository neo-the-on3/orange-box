package io.github.neotheon3.orangebox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.neotheon3.orangebox.core.datastore.AppPreferencesRepository
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import io.github.neotheon3.orangebox.navigation.OrangeBoxNavHost
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var launchOverrides: LaunchOverrides
    @Inject lateinit var debugEntries: DebugEntries
    @Inject lateinit var appPreferences: AppPreferencesRepository
    @Inject lateinit var driveSession: io.github.neotheon3.orangebox.core.driving.DriveSession

    /** Bumped by every "Start driving" / driving-notification tap; the NavHost opens the dashboard. */
    private val openDashboard = kotlinx.coroutines.flow.MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            launchOverrides.apply(intent)
            handleOpenDashboard(intent)
        }
        setContent {
            OrangeBoxTheme {
                // Null until DataStore answers (a few ms); the NavHost waits so its start destination is right.
                val prefs by appPreferences.preferences.collectAsStateWithLifecycle(initialValue = null)
                val dashboardRequests by openDashboard.collectAsStateWithLifecycle()
                prefs?.let {
                    OrangeBoxNavHost(
                        debugEntries = debugEntries,
                        showOnboarding = !it.onboardingComplete,
                        driveSession = driveSession,
                        openDashboardRequests = dashboardRequests,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchOverrides.apply(intent)
        handleOpenDashboard(intent)
    }

    internal fun handleOpenDashboard(intent: Intent?) {
        if (intent?.getBooleanExtra(io.github.neotheon3.orangebox.driving.DrivingNotifications.EXTRA_OPEN_DASHBOARD, false) == true) {
            openDashboard.value++
        }
    }
}
