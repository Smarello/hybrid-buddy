package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.exploration.Answer
import it.techreloaded.hybridbuddy.exploration.BrakeExplorationPlan
import it.techreloaded.hybridbuddy.exploration.MappingRecording
import it.techreloaded.hybridbuddy.exploration.PedalAnalysis
import it.techreloaded.hybridbuddy.exploration.PedalPhase
import it.techreloaded.hybridbuddy.exploration.ProbedIdentifier
import it.techreloaded.hybridbuddy.interpretation.BrakeIndicator
import it.techreloaded.hybridbuddy.interpretation.brakeIndicator
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
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.session.SignalReading
import it.techreloaded.hybridbuddy.signals.Decoded
import it.techreloaded.hybridbuddy.signals.DriveSignals
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

    @Test
    fun `A34 2026-11-05 - base session with 0_3_0, first attempt refused by the LX, then mode 01 answered on 7DF`() = runBlocking {
        val (header, lines) = load("2026-11-05-a34-sessione-0.3.0.jsonl")
        assertEquals("0.3.0", header.appVersion)
        val replay = AcquisitionSession(SessionSource.Replay("a34", simulated = false), ReplayConnector(lines), timeouts = fastTimeouts)
        val job = launch(Dispatchers.Default) { replay.run() }
        val states = withTimeout(30_000) { replay.state.takeWhile { it.phoneLink != PhoneLinkState.FINISHED }.toList() }
        job.join()

        assertTrue(states.any { it.phoneLink == PhoneLinkState.RECONNECTING && it.phoneLinkDetail!!.startsWith("LX non raggiungibile") })
        // Functional addressing (7DF after ATSP0): the engine ECU answers; 27 s later 7E0 would not (next test).
        val speed = replay.state.value.readings.getValue("vehicle_speed")
        assertEquals("7E8", speed.ecu)
        assertEquals(0.0, speed.value)
        assertTrue(replay.state.value.readings.getValue("engine_rpm").value!! in 1290.0..1330.0)
    }

    @Test
    fun `A34 2026-11-05 - first drive with 0_3_0, 7D2 747 700 answer the direct reads, 7E0 never answers mode 01`() {
        val (header, lines) = load("2026-11-05-a34-guida-0.3.0.jsonl")
        assertEquals("0.3.0", header.appVersion)
        val exchanges = lines.filterIsInstance<RecordedExchange>()
        // 0.3.0 addressed mode 01 physically to 7E0: NO DATA every time, so the speed was never available (D-023).
        val mode01 = exchanges.filter { it.cmd == "010C" || it.cmd == "010D" }
        assertEquals(44, mode01.size)
        assertTrue(mode01.all { it.error == "NO DATA" })
        assertTrue(exchanges.any { it.cmd == "ATSH 7E0" } && exchanges.none { it.cmd == "ATSH 7DF" })

        // Readings rebuilt as the session built them; the indicator evaluated at every read of the regenerative torque.
        val signals = DriveSignals.schedule.flatten().distinct()
        var requestHeader = ""
        var state = SessionState(SessionSource.Replay("a34", simulated = false), phoneLink = PhoneLinkState.CONNECTED)
        val indicators = mutableListOf<BrakeIndicator>()
        val batteryKw = mutableListOf<Double>()
        val liftOffRegenKw = mutableListOf<Double>()
        for (exchange in exchanges) {
            if (exchange.cmd.startsWith("ATSH ")) requestHeader = exchange.cmd.removePrefix("ATSH ")
            val targeted = signals.filter { it.command == exchange.cmd && (it.target?.requestHeader == requestHeader || it.command.startsWith("01")) }
            for (signal in targeted) {
                val reading = when (val error = exchange.error) {
                    null -> when (val decoded = signal.decode(AdapterResponse.lines(exchange.raw, exchange.cmd))) {
                        is Decoded.Value -> SignalReading(exchange.t, decoded.value, decoded.ecu, null)
                        is Decoded.Failure -> SignalReading(exchange.t, null, null, decoded.reason)
                    }
                    else -> SignalReading(exchange.t, null, null, "non risponde ($error)")
                }
                state = state.copy(readings = state.readings + (signal.id to reading))
                if (signal.command == "221F9A" && reading.value != null) {
                    batteryKw += reading.value
                    val brake = state.readings[DriveSignals.brakeRequestTorque.id]?.value
                    val regen = state.readings[DriveSignals.regenBrakeTorque.id]?.value
                    val engine = state.readings[DriveSignals.engineTorque.id]?.value
                    if (brake == 0.0 && regen == 0.0 && engine == 0.0 && reading.value < -2.0) liftOffRegenKw += reading.value
                }
            }
            if (exchange.cmd == DriveSignals.regenBrakeTorque.command) indicators += brakeIndicator(state, exchange.t)
        }

        assertEquals(622, indicators.size)
        // Pedal released: without the speed (and the accelerator, never read by 0.3.0) the coasting state is undecidable.
        assertEquals(506, indicators.count { it is BrakeIndicator.Unavailable && it.reason.startsWith("rilascio: velocità") })
        val regenerative = indicators.filterIsInstance<BrakeIndicator.Regenerative>()
        assertEquals(22, regenerative.size)
        assertEquals(40, regenerative.mapNotNull { it.intensityPercent }.max())
        // Friction needs the speed, which 7E0 never gave: never classified in this drive.
        assertEquals(0, indicators.count { it is BrakeIndicator.Friction })
        assertEquals(94, indicators.count { it is BrakeIndicator.Unavailable && it.reason.startsWith("velocità") })

        // HV current as 16-bit two's complement: −29.9…+26.5 kW; the (raw − 32768) form would give about ±580 kW (D-024).
        assertTrue(batteryKw.min() in -31.0..-28.0 && batteryKw.max() in 25.0..28.0, "potenza batteria ${batteryKw.min()}…${batteryKw.max()}")
        assertTrue(state.readings.getValue(DriveSignals.chargeLimit.id).value!! in -35.0..-22.0)
        assertEquals("rifiuta 221807 (7F 22 31)", state.readings.getValue(DriveSignals.chargeLimitFromHybridEcu.id).failure)
        // Releasing the accelerator charges the battery (up to about −8 kW) while both brake torques stay 0:
        // the ring cannot show that regeneration from 221067/221069 (STATUS.md, open problem).
        assertTrue(liftOffRegenKw.size >= 10, "rigenerazione in rilascio vista ${liftOffRegenKw.size} volte")
    }
}
