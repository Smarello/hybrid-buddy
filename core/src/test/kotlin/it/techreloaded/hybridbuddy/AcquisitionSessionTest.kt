package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.RecordedEvent
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.session.Timeouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

val fastTimeouts = Timeouts(
    resetMs = 300, atCommandMs = 300, firstObdRequestMs = 300, obdRequestMs = 150,
    reconnectBaseDelayMs = 20, reconnectMaxDelayMs = 50,
)
val live = SessionSource.Live("OBDLink LX")

/** Runs [session] until [condition] holds on its state, then stops it. */
fun runUntil(session: AcquisitionSession, condition: (SessionState) -> Boolean): SessionState = runBlocking {
    val job = launch(Dispatchers.Default) { session.run() }
    try {
        withTimeout(10_000) { session.state.first(condition) }
    } finally {
        job.cancelAndJoin()
    }
}

class AcquisitionSessionTest {
    @Test
    fun `initializes the adapter and reads every base signal`() {
        val session = AcquisitionSession(live, { InMemoryAdapterLink(fakeCarReply()) }, timeouts = fastTimeouts)
        val state = runUntil(session) { it.readings.size == session.signals.size }

        assertEquals(1240.0, state.readings.getValue("engine_rpm").value)
        assertEquals("7E8", state.readings.getValue("engine_rpm").ecu)
        assertEquals(14.1, state.readings.getValue("adapter_voltage").value)
        assertEquals("ELM327 v1.4b · STN1155 v5.10.3", state.adapterIdentity)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", state.protocol)
        val sent = state.recentExchanges.map { it.command }
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATH1", "ATSP0", "ATI", "STI", "0100", "ATDP"), sent.take(9))
    }

    @Test
    fun `a timeout on one signal only makes that signal missing`() {
        val reply = fakeCarReply { if (it == "0104") AdapterReply.Silence else null }
        val session = AcquisitionSession(live, { InMemoryAdapterLink(reply) }, timeouts = fastTimeouts)
        val state = runUntil(session) { it.readings.size == session.signals.size }

        assertEquals(ElmConnection.TIMEOUT, state.readings.getValue("calculated_load").failure)
        assertNull(state.readings.getValue("calculated_load").value)
        assertEquals(1240.0, state.readings.getValue("engine_rpm").value)
        assertEquals(PhoneLinkState.CONNECTED, state.phoneLink)
    }

    @Test
    fun `reconnects after the link drops and after failed connection attempts`() {
        val connections = AtomicInteger()
        val connector = AdapterConnector {
            when (connections.incrementAndGet()) {
                1 -> InMemoryAdapterLink(fakeCarReply { if (it == "010D") AdapterReply.Disconnect else null })
                2 -> throw IOException("LX spento")
                else -> InMemoryAdapterLink(fakeCarReply())
            }
        }
        val writer = StringWriter()
        val recorder = JsonlSessionRecorder(writer, RecordingHeader(startedAtEpochMs = 0, source = RecordingHeader.LIVE, appVersion = "test"))
        val session = AcquisitionSession(live, connector, recorder = recorder, timeouts = fastTimeouts)
        val state = runUntil(session) { connections.get() >= 3 && it.readings["vehicle_speed"]?.value == 0.0 }

        assertEquals(PhoneLinkState.CONNECTED, state.phoneLink)
        val events = Recording.parse(writer.toString().lineSequence()).second.filterIsInstance<RecordedEvent>().map { it.event }
        assertEquals(
            listOf(RecordedEvent.CONNECTED, RecordedEvent.DISCONNECTED, RecordedEvent.CONNECT_FAILED, RecordedEvent.CONNECTED),
            events.take(4),
        )
    }

    @Test
    fun `an adapter that stops answering is treated as lost after consecutive timeouts`() {
        val connections = AtomicInteger()
        val connector = AdapterConnector {
            val silent = connections.incrementAndGet() == 1
            InMemoryAdapterLink(fakeCarReply { if (silent && it.startsWith("01")) AdapterReply.Silence else null })
        }
        val session = AcquisitionSession(live, connector, timeouts = fastTimeouts)
        val state = runUntil(session) { connections.get() >= 2 && it.readings["engine_rpm"]?.value != null }
        assertEquals(1240.0, state.readings.getValue("engine_rpm").value)
    }

    @Test
    fun `every exchange is recorded with time, duration and error`() {
        val writer = StringWriter()
        val recorder = JsonlSessionRecorder(writer, RecordingHeader(startedAtEpochMs = 1, source = RecordingHeader.LIVE, appVersion = "test"))
        val reply = fakeCarReply { if (it == "0105") AdapterReply.Text(listOf("NO DATA\r\r>")) else null }
        val session = AcquisitionSession(live, { InMemoryAdapterLink(reply) }, recorder = recorder, timeouts = fastTimeouts)
        runUntil(session) { it.readings.size == session.signals.size }

        val (header, lines) = Recording.parse(writer.toString().lineSequence())
        assertEquals(RecordingHeader.LIVE, header.source)
        val exchanges = lines.filterIsInstance<RecordedExchange>()
        assertTrue(exchanges.all { it.t > 0 && it.durationMs >= 0 })
        assertEquals("NO DATA", exchanges.first { it.cmd == "0105" }.error)
        assertEquals("7E8 04 41 0C 13 60", exchanges.first { it.cmd == "010C" }.raw!!.trim())
    }
}
