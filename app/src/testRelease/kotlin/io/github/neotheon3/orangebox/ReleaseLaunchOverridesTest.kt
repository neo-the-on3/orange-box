package io.github.neotheon3.orangebox

import android.content.Intent
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReleaseLaunchOverridesTest {
    @Test fun `release ignores backend_mode extra`() {
        val intent = Intent().putExtra(LaunchExtras.BACKEND_MODE, "fake")
        assertNull(ReleaseLaunchOverrides.apply(intent))
        assertNull(ReleaseLaunchOverrides.apply(null))
    }
}
