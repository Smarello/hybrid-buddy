package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplayTest {
    private fun record(): String {
        val connections = AtomicInteger()
        val writer = StringWriter()
        val recorder = JsonlSessionRecorder(writer, RecordingHeader(startedAtEpochMs = 1, source = RecordingHeader.LIVE, appVersion = "test"))
        val session = AcquisitionSession(
            live,
            {
                val first = connections.incrementAndGet() == 1
                InMemoryAdapterLink(fakeCarReply { if (first && it == "0105") AdapterReply.Disconnect else null })
            },
            recorder = recorder,
            timeouts = fastTimeouts,
        )
        runUntil(session) { connections.get() >= 2 && it.readings.size == session.signals.size }
        return writer.toString()
    }

    @Test
    fun `a recording replays through the same pipeline, disconnection included, then finishes`() = runBlocking {
        val recorded = record()
        val (header, lines) = Recording.parse(recorded.lineSequence())
        val replay = AcquisitionSession(
            SessionSource.Replay("test.jsonl", simulated = header.source == RecordingHeader.SIMULATED),
            ReplayConnector(lines),
            timeouts = fastTimeouts,
        )
        val job = launch(Dispatchers.Default) { replay.run() }
        val states = withTimeout(10_000) { replay.state.takeWhile { it.phoneLink != PhoneLinkState.FINISHED }.toList() }
        job.join()

        assertEquals("fine registrazione", replay.state.value.phoneLinkDetail)
        assertTrue(states.any { it.phoneLink == PhoneLinkState.RECONNECTING })
        assertEquals(1240.0, replay.state.value.readings.getValue("engine_rpm").value)
        val replayed = replay.state.value.recentExchanges.map { it.command to it.error }
        val original = lines.filterIsInstance<RecordedExchange>().map { it.cmd to it.error }
        assertEquals(original, replayed.take(original.size))
    }

    @Test
    fun `recordings contain no personal identifiers`() {
        val recorded = record()
        assertFalse("0902" in recorded, "VIN must never be requested")
        assertFalse(Regex("""([0-9A-F]{2}:){5}[0-9A-F]{2}""", RegexOption.IGNORE_CASE).containsMatchIn(recorded), "no Bluetooth address")
        val (header, _) = Recording.parse(recorded.lineSequence())
        assertEquals(RecordingHeader(startedAtEpochMs = 1, source = RecordingHeader.LIVE, appVersion = "test"), header)
    }

    @Test
    fun `bundled sample recording is marked simulated and replays`() = runBlocking {
        val (header, lines) = Recording.parse(File("../app/src/main/assets/sample-simulated.jsonl").readLines().asSequence())
        assertEquals(RecordingHeader.SIMULATED, header.source)
        val replay = AcquisitionSession(SessionSource.Replay("sample", simulated = true), ReplayConnector(lines.take(60)), timeouts = fastTimeouts)
        val job = launch(Dispatchers.Default) { replay.run() }
        withTimeout(10_000) { job.join() }
        assertTrue(replay.state.value.readings.isNotEmpty())
    }
}
