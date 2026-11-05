package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.exploration.Answer
import it.techreloaded.hybridbuddy.exploration.BrakeExplorationPlan
import it.techreloaded.hybridbuddy.exploration.MappingRecording
import it.techreloaded.hybridbuddy.exploration.PedalAnalysis
import it.techreloaded.hybridbuddy.exploration.PedalPhase
import it.techreloaded.hybridbuddy.exploration.ProbedIdentifier
import it.techreloaded.hybridbuddy.obdlink.AdapterResponse
import it.techreloaded.hybridbuddy.obdlink.IsoTp
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.RecordedEvent
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Recordings exported from the real phone, kept as permanent test cases. */
class RealRecordingsTest {
    private fun load(name: String) =
        Recording.parse(javaClass.getResource("/recordings/$name")!!.readText().lineSequence())

    @Test
    fun `A34 2026-11-05 - every connection attempt refused for Bluetooth permission`() = runBlocking {
        val (header, lines) = load("2026-11-05-a34-permesso-bluetooth.jsonl")
        assertEquals(RecordingHeader.LIVE, header.source)
        val replay = AcquisitionSession(SessionSource.Replay("a34", simulated = false), ReplayConnector(lines), timeouts = fastTimeouts)
        val job = launch(Dispatchers.Default) { replay.run() }
        val states = withTimeout(10_000) { replay.state.takeWhile { it.phoneLink != PhoneLinkState.FINISHED }.toList() }
        job.join()

        // The failure is shown as a reconnection with its reason, never as data.
        assertTrue(states.any { it.phoneLink == PhoneLinkState.RECONNECTING && it.phoneLinkDetail == "permesso Bluetooth mancante" })
        assertTrue(replay.state.value.readings.isEmpty())
        assertTrue(replay.state.value.recentExchanges.isEmpty())
    }

    @Test
    fun `A34 2026-11-05 - stationary test with 0_1_1, four ECUs answer, engine ECU chosen`() = runBlocking {
        val (header, lines) = load("2026-11-05-a34-prova-da-fermo-0.1.1.jsonl")
        assertEquals("0.1.1", header.appVersion)
        val exchanges = lines.filterIsInstance<RecordedExchange>()
        assertTrue(exchanges.all { it.error == null }, "no errors, timeouts or disconnections in the real session")
        val supported = IsoTp.messages(AdapterResponse.lines(exchanges.first { it.cmd == "0100" }.raw, "0100")).map { it.ecu }
        assertEquals(setOf("7E8", "7EA", "7EB", "7EE"), supported.toSet())

        // The first ~2 s of the session replayed through the real pipeline (recorded durations are honoured).
        val replay = AcquisitionSession(SessionSource.Replay("a34", simulated = false), ReplayConnector(lines.take(40)), timeouts = fastTimeouts)
        val job = launch(Dispatchers.Default) { replay.run() }
        withTimeout(20_000) { job.join() }
        val rpm = replay.state.value.readings.getValue("engine_rpm")
        assertEquals("7E8", rpm.ecu)
        assertTrue(rpm.value!! in 1200.0..1500.0)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", replay.state.value.protocol)
        assertEquals("ELM327 v1.4b · STN1155 v5.6.19", replay.state.value.adapterIdentity)
    }

    @Test
    fun `A34 2026-11-05 - pedal phases on the brake ECU 7B0, reused mapping`() {
        val text = javaClass.getResource("/recordings/2026-11-05-a34-esplorazione-pedale-0.2.1.jsonl")!!.readText()
        val (header, lines) = Recording.parse(text.lineSequence())
        assertEquals("0.2.1", header.appVersion)
        // A pedal-only recording is never offered as a mapping to reuse.
        assertNull(MappingRecording.read("pedale.jsonl", text.lineSequence()))

        val brakes = BrakeExplorationPlan.targets.single { it.requestHeader == "7B0" }
        val answers = mutableMapOf<ProbedIdentifier, MutableMap<PedalPhase, MutableList<List<Int>>>>()
        var phase: PedalPhase? = null
        for (line in lines) {
            if (line is RecordedEvent && line.event == RecordedEvent.PEDAL_PHASE) phase = PedalPhase.valueOf(line.detail!!)
            val current = phase ?: continue
            if (line !is RecordedExchange || !line.cmd.startsWith("22")) continue
            val answer = Answer.of(AdapterResponse.lines(line.raw, line.cmd), line.error, brakes, line.cmd)
            if (answer is Answer.Positive) {
                answers.getOrPut(ProbedIdentifier(brakes, line.cmd)) { mutableMapOf() }.getOrPut(current) { mutableListOf() } += answer.payload
            }
        }
        assertEquals(66, answers.size)
        val changed = PedalAnalysis.changedWithPedal(answers).map { it.request }.toSet()
        // Same 12 as shown by the app; 221022, 22104C, 221070, 22102D are the strongest candidates (SIGNALS.md).
        assertEquals(
            setOf(
                "221004", "221007", "221011", "221022", "22102D", "221034",
                "22104C", "221070", "221081", "221082", "221083", "2210A2",
            ),
            changed,
        )
    }
}
