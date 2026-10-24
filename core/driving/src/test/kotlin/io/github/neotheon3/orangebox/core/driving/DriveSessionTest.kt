package io.github.neotheon3.orangebox.core.driving

import io.github.neotheon3.orangebox.core.obd.Elm327Session
import io.github.neotheon3.orangebox.core.obd.ObdConnection
import io.github.neotheon3.orangebox.core.obd.ObdConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSessionTest {
    private class Conn : ObdConnection {
        var disconnects = 0
        override val state = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Idle)
        override fun connect() = Unit
        override fun retry() = Unit
        override fun disconnect() { disconnects++ }
        override suspend fun <T> withSession(block: suspend (Elm327Session) -> T): T? = null
    }

    @Test fun `end commits after the 5 s undo window`() = runTest {
        val conn = Conn()
        val session = DriveSession(backgroundScope, conn)
        var ended = 0
        session.addOnEndListener { ended++ }
        session.requestEnd()
        assertTrue(session.pendingEnd.value)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(0, conn.disconnects)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, conn.disconnects)
        assertEquals(1, ended)
        assertFalse(session.pendingEnd.value)
    }

    @Test fun `undo inside the window keeps driving`() = runTest {
        val conn = Conn()
        val session = DriveSession(backgroundScope, conn)
        session.requestEnd()
        session.requestEnd() // second tap is ignored
        advanceTimeBy(3_000); runCurrent()
        session.undoEnd()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, conn.disconnects)
        assertFalse(session.pendingEnd.value)
    }
}
