package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.exploration.BrakeExploration
import it.techreloaded.hybridbuddy.exploration.ExplorationStep
import it.techreloaded.hybridbuddy.exploration.PedalPhase
import it.techreloaded.hybridbuddy.exploration.TargetStatus
import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.RecordedEvent
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrakeExplorationTest {
    /** 16 silent-probe fillers, then the identifiers the fake hybrid ECU knows. */
    private val requests = (0x00..0x0F).map { "21%02X".format(it) } + listOf("21C3", "221001", "221002", "221003")

    /**
     * Fake car: the hybrid ECU (7E2 → 7EA) answers 21C3 multi-frame, 221001 constant, 221002 with the pedal
     * (00 released, C8 pressed), everything else "request out of range"; the brake ECU (7B0) never answers.
     */
    private fun fakeCar(pedalPressed: () -> Boolean): (String) -> AdapterReply {
        var header = "7DF"
        return fakeCarReply { command ->
            if (command.startsWith("ATSH ")) header = command.removePrefix("ATSH ")
            val text = when {
                command.startsWith("AT") -> null
                command.startsWith("21") || command.startsWith("22") -> when {
                    header != "7E2" -> "NO DATA\r\r>"
                    command == "21C3" -> "7EA 10 0B 61 C3 01 02 03 04 \r7EA 21 05 06 07 08 09 AA AA \r\r>"
                    command == "221001" -> "7EA 05 62 10 01 12 34 \r\r>"
                    command == "221002" -> "7EA 04 62 10 02 ${if (pedalPressed()) "C8" else "00"} \r\r>"
                    else -> "7EA 03 7F ${command.take(2)} 31 \r\r>"
                }
                else -> null
            }
            text?.let { AdapterReply.Text(listOf(it)) }
        }
    }

    /** The fake pedal is pressed exactly during the exploration's LIGHT and FULL phases. */
    private fun exploration(writer: StringWriter, requests: List<String> = this.requests): BrakeExploration {
        lateinit var exploration: BrakeExploration
        val pressed = { exploration.state.value.pedalPhase in setOf(PedalPhase.LIGHT, PedalPhase.FULL) }
        exploration = BrakeExploration(
            connector = { InMemoryAdapterLink(fakeCar(pressed)) },
            recorder = JsonlSessionRecorder(writer, RecordingHeader(startedAtEpochMs = 1, source = RecordingHeader.LIVE, appVersion = "test")),
            requests = requests,
            pedalPhaseMs = 150,
            timeouts = fastTimeouts,
        )
        return exploration
    }

    @Test
    fun `maps the hybrid ECU, skips the silent brake ECU, guides the pedal and finds what changes`() = runBlocking {
        val writer = StringWriter()
        val exploration = exploration(writer)
        withTimeout(20_000) { exploration.run() }
        val state = exploration.state.value

        assertEquals(ExplorationStep.DONE, state.step)
        val (hybrid, brakes) = state.targets
        assertEquals(TargetStatus.DONE, hybrid.status)
        assertEquals(1, hybrid.mode21Responding) // 21C3, reassembled from two frames
        assertEquals(2, hybrid.mode22Responding) // 221001, 221002; 221003 answered 7F 22 31
        assertEquals(TargetStatus.SILENT, brakes.status)
        assertEquals(requests.size + 16, state.mappingTotal)
        assertEquals(state.mappingTotal, state.mappingSent)
        assertEquals(1, state.changedWithPedal) // only 221002

        val events = Recording.parse(writer.toString().lineSequence()).second.filterIsInstance<RecordedEvent>()
        assertEquals(PedalPhase.entries.map { it.name }, events.filter { it.event == RecordedEvent.PEDAL_PHASE }.map { it.detail })
        assertTrue(events.any { it.event == RecordedEvent.EXPLORATION && it.detail == "7B0 non risponde" })
        val commands = Recording.parse(writer.toString().lineSequence()).second
            .filterIsInstance<RecordedExchange>().map { it.cmd }
        assertTrue(listOf("ATSH 7E2", "ATCRA 7EA", "ATFCSH 7E2", "ATFCSD 30 00 00", "ATFCSM 1").all { it in commands })
        assertEquals("ATZ", commands.last())
    }

    @Test
    fun `nothing answers - the pedal step is skipped`() = runBlocking {
        val exploration = exploration(StringWriter(), requests = (0x00..0x10).map { "21%02X".format(it) })
        withTimeout(20_000) { exploration.run() }
        assertEquals(ExplorationStep.DONE, exploration.state.value.step)
        assertTrue(exploration.state.value.pedalSkipped)
        assertEquals(0, exploration.state.value.changedWithPedal)
    }

    @Test
    fun `stopping keeps what was recorded`() = runBlocking {
        val writer = StringWriter()
        val exploration = exploration(writer)
        val job = launch(Dispatchers.Default) { exploration.run() }
        withTimeout(20_000) { exploration.state.first { it.step == ExplorationStep.PEDAL } }
        job.cancelAndJoin()
        assertEquals(ExplorationStep.STOPPED, exploration.state.value.step)
        assertTrue("21C3" in writer.toString())
    }

    @Test
    fun `adapter unreachable - failed with the reason`() = runBlocking {
        val exploration = BrakeExploration(connector = { throw java.io.IOException("LX spento") }, timeouts = fastTimeouts)
        exploration.run()
        assertEquals(ExplorationStep.FAILED, exploration.state.value.step)
        assertEquals("LX spento", exploration.state.value.failure)
    }
}
