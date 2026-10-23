package io.github.neotheon3.orangebox.core.bluetooth

import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.obd.Elm327Info
import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import io.github.neotheon3.orangebox.core.obd.ObdError
import io.github.neotheon3.orangebox.core.obd.ObdTransportProvider
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DongleTestResult {
    data class Success(val info: Elm327Info, val alreadyConnected: Boolean = false) : DongleTestResult
    data class Failure(val error: ObdError) : DongleTestResult
}

/** One-shot "Test connection" for the selected dongle, separate from the connection manager's retries. */
interface DongleTester {
    suspend fun testSelected(): DongleTestResult
}

@Singleton
class DefaultDongleTester @Inject constructor(
    private val provider: ObdDongleProvider,
    private val connection: ObdConnection,
) : DongleTester {
    override suspend fun testSelected(): DongleTestResult = test(provider, connection)

    companion object {
        const val TIMEOUT_MS = 25_000L

        /** Pure enough to unit-test with a fake provider. */
        suspend fun test(provider: ObdTransportProvider, connection: ObdConnection?, timeoutMs: Long = TIMEOUT_MS): DongleTestResult {
            // The manager already holds the link: report it instead of opening a second socket.
            (connection?.state?.value as? ObdConnectionState.Connected)?.let {
                return DongleTestResult.Success(it.info, alreadyConnected = true)
            }
            val result = try {
                val transport = provider.create()
                try {
                    withTimeout(timeoutMs) {
                        transport.open()
                        DongleTestResult.Success(Elm327Session(transport).initialize())
                    }
                } finally {
                    runCatching { transport.close() }
                }
            } catch (e: ObdError) {
                DongleTestResult.Failure(e)
            } catch (e: TimeoutCancellationException) {
                DongleTestResult.Failure(ObdError.Timeout("No answer from the adapter within ${timeoutMs / 1000} s"))
            }
            when (result) {
                is DongleTestResult.Success -> OrangeLog.i(OrangeLog.Areas.OBD, "dongle_test", "result" to "ok", "protocol" to result.info.protocol)
                is DongleTestResult.Failure -> OrangeLog.w(OrangeLog.Areas.OBD, "dongle_test", "result" to "failed", "error" to result.error::class.simpleName)
            }
            return result
        }
    }
}
