package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.bluetooth.BluetoothAvailability
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.neotheon3.orangebox.core.bluetooth.DongleCandidate
import io.github.neotheon3.orangebox.core.designsystem.OrangeBoxTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SetupScreensTest {
    @get:Rule val compose = createComposeRule()

    private fun onboarding(state: OnboardingUiState, onNext: () -> Unit = {}, onChecked: (Boolean) -> Unit = {}, dark: Boolean = false) {
        compose.setContent {
            OrangeBoxTheme(darkTheme = dark) {
                OnboardingScreen(
                    state = state, onNext = onNext, onDisclaimerChecked = onChecked, onFinish = {},
                    permissions = { Text("perm-slot") }, vehicle = { Text("vehicle-slot") }, dongle = { Text("dongle-slot") },
                )
            }
        }
    }

    @Test fun `disclaimer continue is disabled until accepted`() {
        var checked: Boolean? = null
        onboarding(OnboardingUiState(step = OnboardingStep.DISCLAIMER), onChecked = { checked = it })
        compose.onNodeWithTag("onboarding_next").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding_disclaimer_accept").performScrollTo().performClick()
        assertEquals(true, checked)
    }

    @Test fun `accepted disclaimer enables continue`() {
        onboarding(OnboardingUiState(step = OnboardingStep.DISCLAIMER, disclaimerChecked = true))
        compose.onNodeWithTag("onboarding_next").assertIsEnabled()
    }

    @Test fun `vehicle and dongle steps can be skipped`() {
        var next = 0
        onboarding(OnboardingUiState(step = OnboardingStep.DONGLE), onNext = { next++ }, dark = true)
        compose.onNodeWithText("dongle-slot").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_skip").performClick()
        assertEquals(1, next)
    }

    @Test fun `vehicle form switches to manual entry`() {
        var form = VehicleForm()
        compose.setContent { OrangeBoxTheme { VehicleSetupContent(VehicleSetupUiState(form), onFormChange = { form = it }) } }
        compose.onNodeWithTag("vehicle_maker").assertIsDisplayed()
        compose.onNodeWithTag("vehicle_source_manual").performClick()
        assertEquals(VehicleSource.MANUAL, form.source)
    }

    @Test fun `manual vehicle form shows validation errors`() {
        val state = VehicleSetupUiState(VehicleForm(source = VehicleSource.MANUAL), errors = setOf(VehicleFormError.NAME))
        compose.setContent { OrangeBoxTheme { VehicleSetupContent(state, onFormChange = {}) } }
        compose.onNodeWithText("Enter a name").assertIsDisplayed()
        compose.onNodeWithTag("vehicle_fuel_diesel").assertIsDisplayed()
    }

    @Test fun `dongle list selects, tests and shows re-pair help`() {
        val c = DongleCandidate("00:11:22:33:44:55", "OBDII")
        var selected: DongleCandidate? = null
        var tested = 0
        var btSettings = 0
        val state = DongleSetupUiState(
            candidates = listOf(c), selectedAddress = c.address,
            test = TestState.Failed(FailureKind.NOT_PAIRED, "No paired OBD dongle"),
        )
        compose.setContent {
            OrangeBoxTheme {
                DongleSetupContent(
                    state, onSelect = { selected = it }, onTest = { tested++ }, onAutoStartChange = {},
                    onOpenBluetoothSettings = { btSettings++ }, permissionSlot = {},
                )
            }
        }
        compose.onNodeWithTag("dongle_${c.address}").performClick()
        compose.onNodeWithTag("dongle_test").performClick()
        compose.onNodeWithTag("dongle_repair_help").assertIsDisplayed()
        compose.onNodeWithTag("dongle_bt_settings").performClick()
        assertEquals(c, selected)
        assertEquals(1, tested)
        assertEquals(1, btSettings)
    }

    @Test fun `onboarding buttons stay clear of the navigation bar on every step`() {
        // 3-button nav: 48 dp at the bottom in portrait; in landscape it moves to the right edge (#119).
        var portrait by mutableStateOf(true)
        var step by mutableStateOf(OnboardingStep.WELCOME)
        compose.setContent {
            OrangeBoxTheme {
                OnboardingScreen(
                    state = OnboardingUiState(step = step, disclaimerChecked = true), onNext = {}, onDisclaimerChecked = {}, onFinish = {},
                    permissions = { Text("perm-slot") }, vehicle = { Text("vehicle-slot") }, dongle = { Text("dongle-slot") },
                    bottomBarInsets = if (portrait) WindowInsets(bottom = 48.dp) else WindowInsets(right = 48.dp),
                )
            }
        }
        for (p in listOf(true, false)) {
            portrait = p
            val (bottomInset, rightInset) = if (p) 48.dp to 0.dp else 0.dp to 48.dp
            for (s in OnboardingStep.entries) {
                step = s
                compose.waitForIdle()
                val root = compose.onRoot().getUnclippedBoundsInRoot()
                val tag = when (s) {
                    OnboardingStep.DONE -> "onboarding_finish"
                    OnboardingStep.VEHICLE -> "onboarding_skip"
                    else -> "onboarding_next"
                }
                val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue("$s portrait=$p bottom=${b.bottom} root=${root.bottom}", b.bottom <= root.bottom - bottomInset)
                assertTrue("$s portrait=$p right=${b.right} root=${root.right}", b.right <= root.right - rightInset)
            }
        }
    }

    @Test fun `no bluetooth hardware is explained and the settings button hidden`() {
        compose.setContent {
            OrangeBoxTheme {
                DongleSetupContent(
                    DongleSetupUiState(candidates = listOf(DongleCandidate("SIM", "Simulated ELM327", simulated = true)), bluetooth = BluetoothAvailability.UNAVAILABLE),
                    onSelect = {}, onTest = {}, onAutoStartChange = {}, onOpenBluetoothSettings = {}, permissionSlot = {},
                )
            }
        }
        compose.onNodeWithTag("dongle_bt_unavailable").assertIsDisplayed()
        compose.onNodeWithTag("dongle_bt_settings").assertDoesNotExist()
        compose.onNodeWithTag("dongle_empty").assertDoesNotExist()
    }

    @Test fun `bluetooth off offers to turn it on`() {
        var opened = 0
        compose.setContent {
            OrangeBoxTheme {
                DongleSetupContent(
                    DongleSetupUiState(bluetooth = BluetoothAvailability.OFF),
                    onSelect = {}, onTest = {}, onAutoStartChange = {}, onOpenBluetoothSettings = { opened++ }, permissionSlot = {},
                )
            }
        }
        compose.onNodeWithTag("dongle_bt_off").assertIsDisplayed()
        compose.onNodeWithTag("dongle_bt_settings").assertTextEquals("Turn on Bluetooth").performClick()
        assertEquals(1, opened)
    }

    @Test fun `only the simulated dongle still says nothing real is paired`() {
        compose.setContent {
            OrangeBoxTheme {
                DongleSetupContent(
                    DongleSetupUiState(candidates = listOf(DongleCandidate("SIM", "Simulated ELM327", simulated = true))),
                    onSelect = {}, onTest = {}, onAutoStartChange = {}, onOpenBluetoothSettings = {}, permissionSlot = {},
                )
            }
        }
        compose.onNodeWithTag("dongle_empty").assertIsDisplayed()
    }

    @Test fun `odometer field keeps the decimal point`() {
        var form = VehicleForm()
        compose.setContent { OrangeBoxTheme { VehicleSetupContent(VehicleSetupUiState(form), onFormChange = { form = it }) } }
        compose.onNodeWithTag("vehicle_odometer").performTextInput("abc-12.5x")
        assertEquals("12.5", form.odometerKm)
    }

    @Test fun `dongle without permission shows the permission slot`() {
        compose.setContent {
            OrangeBoxTheme {
                DongleSetupContent(
                    DongleSetupUiState(needsPermission = true), onSelect = {}, onTest = {}, onAutoStartChange = {},
                    onOpenBluetoothSettings = {}, permissionSlot = { Text("perm-slot") },
                )
            }
        }
        compose.onNodeWithTag("dongle_needs_permission").assertIsDisplayed()
        compose.onNodeWithText("perm-slot").assertIsDisplayed()
        compose.onNodeWithTag("dongle_test").assertIsNotEnabled()
        assertTrue(true)
    }
}
