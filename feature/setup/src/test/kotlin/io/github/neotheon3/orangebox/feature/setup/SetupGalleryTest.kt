package io.github.neotheon3.orangebox.feature.setup

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.neotheon3.orangebox.core.bluetooth.DongleCandidate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.neotheon3.orangebox.core.model.Vehicle
import io.github.neotheon3.orangebox.core.model.VehicleFuel

/** M10 gallery (#71/#72): onboarding, vehicles and dongle setup in light, dark and 200 % font, with accessibility checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class SetupGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onboardingDisclaimer() = compose.screenGallery("onboarding-disclaimer") {
        OnboardingScreen(
            state = OnboardingUiState(step = OnboardingStep.DISCLAIMER), onNext = {}, onDisclaimerChecked = {}, onFinish = {},
            permissions = {}, vehicle = {}, dongle = {},
        )
    }

    @Test fun vehicles() = compose.screenGallery("vehicles") {
        VehicleListScreen(
            VehicleListUiState(
                listOf(Vehicle(1, "Maruti Swift", "Maruti Suzuki", "Swift 1.2", VehicleFuel.PETROL, 1197, 42_000), Vehicle(2, "Work van", fuel = VehicleFuel.DIESEL, displacementCc = 2499)),
                activeId = 1, loaded = true,
            ),
            onBack = {}, onEdit = {}, onAdd = {}, onSelect = {}, onDelete = {},
        )
    }

    @Test fun vehicleForm() = compose.screenGallery("vehicle-form") {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) { VehicleSetupContent(VehicleSetupUiState(VehicleForm()), onFormChange = {}, Modifier.padding(16.dp)) }
    }

    @Test fun dongle() = compose.screenGallery("dongle-setup") {
        androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(Modifier.padding(16.dp)) {
                DongleSetupContent(
                    DongleSetupUiState(candidates = listOf(DongleCandidate("00:11:22:33:44:55", "OBDII")), selectedAddress = "00:11:22:33:44:55"),
                    onSelect = {}, onTest = {}, onAutoStartChange = {}, onOpenBluetoothSettings = {}, permissionSlot = {},
                )
            }
        }
    }
}
