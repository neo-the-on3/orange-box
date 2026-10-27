package io.github.neotheon3.orangebox.feature.diagnostics

import androidx.compose.ui.test.junit4.v2.createComposeRule
import io.github.neotheon3.orangebox.core.testing.GalleryVariant
import io.github.neotheon3.orangebox.core.testing.screenGallery
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** M10 gallery (#71/#72): Diagnostics keeps the dashboard's dark palette in both app themes; 200 % font; a11y checks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xhdpi")
class DiagnosticsGalleryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun faults() = compose.screenGallery("diagnostics-faults") {
        DiagnosticsScreen(DiagnosticsUiState(connected = true, report = acceptanceReport(), history = sampleHistory), onBack = {}, onRefresh = {}, onClear = {}, formatTime = testTime)
    }
}
