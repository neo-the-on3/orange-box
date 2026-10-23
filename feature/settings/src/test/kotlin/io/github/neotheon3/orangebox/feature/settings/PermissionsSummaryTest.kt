package io.github.neotheon3.orangebox.feature.settings

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsSummaryTest {
    @Test fun `summary names only the permissions this android version asks for`() {
        assertEquals(R.string.settings_permissions_summary_location, permissionsSummaryRes(Build.VERSION_CODES.N_MR1)) // API 25
        assertEquals(R.string.settings_permissions_summary_location, permissionsSummaryRes(Build.VERSION_CODES.R))
        assertEquals(R.string.settings_permissions_summary_bt_location, permissionsSummaryRes(Build.VERSION_CODES.S))
        assertEquals(R.string.settings_permissions_summary_bt_location, permissionsSummaryRes(Build.VERSION_CODES.S_V2))
        assertEquals(R.string.settings_permissions_summary, permissionsSummaryRes(Build.VERSION_CODES.TIRAMISU))
    }
}
