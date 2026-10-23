package io.github.neotheon3.orangebox.core.testing

import io.github.neotheon3.orangebox.core.telemetry.TelemetrySnapshot
import io.github.neotheon3.orangebox.core.telemetry.TelemetrySource
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTelemetrySource(initial: TelemetrySnapshot? = null) : TelemetrySource {
    override val latest = MutableStateFlow(initial)
}
