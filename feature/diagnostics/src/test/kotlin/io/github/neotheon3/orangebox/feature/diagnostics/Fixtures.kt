package io.github.neotheon3.orangebox.feature.diagnostics

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import io.github.neotheon3.orangebox.core.database.DtcRecordEntity
import io.github.neotheon3.orangebox.core.diagnostics.DiagnosticsReport
import io.github.neotheon3.orangebox.core.diagnostics.DtcStatus
import io.github.neotheon3.orangebox.core.diagnostics.TroubleCode
import java.io.File
import java.time.ZoneOffset

internal val testTime: (Long) -> String = defaultTimeFormat(ZoneOffset.ofHoursMinutes(5, 30))

/** The #48 acceptance set: Ignition, Air/Fuel and Network lamps red. */
internal fun acceptanceReport(milOn: Boolean = true) = DiagnosticsReport(
    readAtMillis = 1_761_370_000_000,
    codes = listOf(
        TroubleCode("P0300", DtcStatus.STORED),
        TroubleCode("P0420", DtcStatus.STORED),
        TroubleCode("U0100", DtcStatus.STORED),
        TroubleCode("P0171", DtcStatus.PENDING),
        TroubleCode("P0420", DtcStatus.PERMANENT),
    ),
    milOn = milOn,
    vin = "1HGCM82633A004352",
)

internal val sampleHistory = listOf(
    DtcRecordEntity(3, "P0300", "stored", null, 1_761_370_000_000, 1_761_370_000_000),
    DtcRecordEntity(2, "P0301", "stored", null, 1_761_000_000_000, 1_761_100_000_000, 1_761_200_000_000, DtcRecordEntity.END_CLEARED),
    DtcRecordEntity(1, "P0133", "pending", null, 1_760_000_000_000, 1_760_100_000_000, 1_760_200_000_000, DtcRecordEntity.END_RESOLVED),
)

/** Writes the current root to `build/outputs/diagnostics/<name>.png` (artwork review, not a diff test). */
internal fun ComposeContentTestRule.shoot(name: String) {
    waitForIdle()
    val bitmap = onRoot().captureToImage().asAndroidBitmap()
    File("build/outputs/diagnostics/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
