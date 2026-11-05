package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.interpretation.BrakeIndicator
import it.techreloaded.hybridbuddy.interpretation.Fresh
import it.techreloaded.hybridbuddy.interpretation.LinkLevel
import it.techreloaded.hybridbuddy.interpretation.batteryPowerKw
import it.techreloaded.hybridbuddy.interpretation.brakeIndicator
import it.techreloaded.hybridbuddy.interpretation.driveEcuStatus
import it.techreloaded.hybridbuddy.interpretation.enginePowerKw
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.obdlink.ReadOnlyCommands
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionRecorder
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.session.SignalReading
import it.techreloaded.hybridbuddy.session.SimulatedCar
import it.techreloaded.hybridbuddy.signals.Decoded
import it.techreloaded.hybridbuddy.signals.DriveSignals
import it.techreloaded.hybridbuddy.signals.SignalDefinition
import java.io.StringWriter
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DriveTest {
    private val now = 1_000_000L

    private fun state(vararg values: Pair<SignalDefinition, Double>, failures: Map<SignalDefinition, String> = emptyMap()) =
        SessionState(
            SessionSource.Live("LX"), phoneLink = PhoneLinkState.CONNECTED,
            readings = values.associate { (signal, value) -> signal.id to SignalReading(now - 100, value, null, null) } +
                failures.map { (signal, reason) -> signal.id to SignalReading(now - 100, null, null, reason) },
        )

    private fun decode(signal: SignalDefinition, payload: List<Int>) =
        signal.decode(SimulatedCar.frames(signal.target!!.responseHeader, payload).split('\r').map { it.trim() }.filter { it.isNotEmpty() })

    @Test
    fun `decodes the Hybrid Assistant DIDs from single and multi-frame answers`() {
        // M = (0x7C40 − 32768)/8 = −120 Nm, in a multi-frame answer (3 + 6 data bytes).
        assertEquals(Decoded.Value(-120.0, "7DA"), decode(DriveSignals.brakeRequestTorque, listOf(0x62, 0x10, 0x67, 0x7C, 0x40, 0, 0, 0, 0)))
        assertEquals(Decoded.Value(-70.0, "7DA"), decode(DriveSignals.regenBrakeTorque, listOf(0x62, 0x10, 0x69, 0, 0, 0x7D, 0xD0)))
        // 200 V (12800) · −50 A (0xFE0C, two's complement) = −10 kW, charging.
        assertEquals(
            Decoded.Value(-10.0, "74F"),
            decode(DriveSignals.batteryPower, listOf(0x62, 0x1F, 0x9A, 0, 0, 0x32, 0x00, 0xFE, 0x0C)),
        )
        assertEquals(Decoded.Value(25.0, "74F"), decode(DriveSignals.chargeLimit, listOf(0x62, 0x18, 0x07, 0x89, 0xC4)))
        assertEquals(Decoded.Value(60.0, "708"), decode(DriveSignals.engineTorque, listOf(0x62, 0x10, 0xC2, 0x80, 0x3C)))
        assertEquals(Decoded.Failure("rifiuta 221067 (7F 22 31)"), decode(DriveSignals.brakeRequestTorque, listOf(0x7F, 0x22, 0x31)))
        assertEquals(Decoded.Failure("risposta corta a 221069"), decode(DriveSignals.regenBrakeTorque, listOf(0x62, 0x10, 0x69, 0, 0)))
        assertEquals(Decoded.Failure("nessuna risposta valida a 221069"), decode(DriveSignals.regenBrakeTorque, listOf(0x62, 0x10, 0x67, 0, 0, 0, 0)))
    }

    @Test
    fun `classifies braking like Hybrid Assistant`() {
        val m = DriveSignals.brakeRequestTorque
        val r = DriveSignals.regenBrakeTorque
        val speed = DriveSignals.vehicleSpeed

        val friction = brakeIndicator(state(m to -118.0, r to -68.0, speed to 47.0), now)
        assertEquals(BrakeIndicator.Friction(42), friction)
        assertEquals(0.71, friction.fill)

        val regen = brakeIndicator(
            state(m to -60.0, r to -60.0, speed to 50.0, DriveSignals.batteryPower to -10.0, DriveSignals.chargeLimit to 20.0), now,
        )
        assertEquals(BrakeIndicator.Regenerative(50), regen)
        assertEquals(0.25, regen.fill)
        assertEquals(BrakeIndicator.Regenerative(null), brakeIndicator(state(m to -60.0, r to -60.0), now))

        assertEquals(BrakeIndicator.Idle(BrakeIndicator.BELOW_BRAKE_SPEED), brakeIndicator(state(m to -80.0, r to -10.0, speed to 10.0), now))
        assertEquals(BrakeIndicator.Idle(BrakeIndicator.BELOW_BRAKE_SPEED), brakeIndicator(state(m to -80.0, r to -10.0, speed to 15.0), now))
        // Stationary with the pedal released: nothing else is needed to say "no braking".
        assertEquals(BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING), brakeIndicator(state(m to 0.0, r to 0.0, speed to 0.0), now))
    }

    @Test
    fun `regeneration on accelerator release comes from the battery power while coasting, as the HyBall does`() {
        val m = DriveSignals.brakeRequestTorque
        val r = DriveSignals.regenBrakeTorque
        val speed = DriveSignals.vehicleSpeed
        val pedal = DriveSignals.acceleratorPedal
        val rpm = DriveSignals.engineSpeed
        val torque = DriveSignals.engineTorque
        val battery = DriveSignals.batteryPower
        val coasting = arrayOf(m to 0.0, r to 0.0, speed to 60.0, pedal to 0.0, rpm to 0.0)

        assertEquals(BrakeIndicator.Regenerative(16, liftOff = true), brakeIndicator(state(*coasting, battery to -4.0, DriveSignals.chargeLimit to 25.0), now))
        assertEquals(0.08, BrakeIndicator.Regenerative(16, liftOff = true).fill)
        // Engine spinning without torque counts as off (fuel cut); a running engine charging the battery is not regeneration.
        assertEquals(
            BrakeIndicator.Regenerative(null, liftOff = true),
            brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, pedal to 0.0, rpm to 1200.0, torque to -20.0, battery to -4.0), now),
        )
        assertEquals(
            BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING),
            brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, pedal to 0.0, rpm to 1500.0, torque to 30.0, battery to -4.0), now),
        )
        assertEquals(BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING), brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, pedal to 15.0, rpm to 0.0, battery to -4.0), now))
        assertEquals(BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING), brakeIndicator(state(*coasting, battery to 0.5), now))

        // Every signal the coasting state needs is named when missing; the first missing one wins.
        assertEquals(BrakeIndicator.Unavailable("rilascio: velocità: 7DF: in attesa"), brakeIndicator(state(m to 0.0, r to 0.0), now))
        assertEquals(
            BrakeIndicator.Unavailable("rilascio: acceleratore: 7D2: rifiuta 221060 (7F 22 31)"),
            brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, failures = mapOf(pedal to "rifiuta 221060 (7F 22 31)")), now),
        )
        assertEquals(BrakeIndicator.Unavailable("rilascio: giri: 7DF: in attesa"), brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, pedal to 0.0), now))
        assertEquals(
            BrakeIndicator.Unavailable("rilascio: coppia termica: 700: in attesa"),
            brakeIndicator(state(m to 0.0, r to 0.0, speed to 60.0, pedal to 0.0, rpm to 1500.0), now),
        )
        assertEquals(BrakeIndicator.Unavailable("rilascio: 747: in attesa · 7D2: in attesa"), brakeIndicator(state(*coasting), now))
    }

    @Test
    fun `a missing, refused or stale brake torque is never shown as zero`() {
        val m = DriveSignals.brakeRequestTorque
        val r = DriveSignals.regenBrakeTorque
        assertEquals(
            BrakeIndicator.Unavailable("7D2: rifiuta 221067 (7F 22 31)"),
            brakeIndicator(state(r to -10.0, failures = mapOf(m to "rifiuta 221067 (7F 22 31)")), now),
        )
        assertEquals(BrakeIndicator.Unavailable("7D2: in attesa"), brakeIndicator(state(m to -10.0), now))
        assertEquals(BrakeIndicator.Unavailable("7D2: dato scaduto"), brakeIndicator(state(m to -10.0, r to -10.0), now + 5_000))
        assertEquals(
            BrakeIndicator.Unavailable("velocità: 7DF: in attesa"),
            brakeIndicator(state(m to -100.0, r to -10.0), now),
        )
    }

    @Test
    fun `battery power prefers 747 and falls back to 7D2, engine power from torque and rpm`() {
        assertEquals(-5.0, (batteryPowerKw(state(DriveSignals.batteryPower to -5.0, DriveSignals.batteryPowerFromHybridEcu to -7.0), now) as Fresh.Value).value)
        assertEquals(
            -7.0,
            (batteryPowerKw(state(DriveSignals.batteryPowerFromHybridEcu to -7.0, failures = mapOf(DriveSignals.batteryPower to "non risponde (NO DATA)")), now) as Fresh.Value).value,
        )
        assertEquals(Fresh.Missing("747: in attesa · 7D2: in attesa"), batteryPowerKw(state(), now))
        val engine = enginePowerKw(state(DriveSignals.engineTorque to 100.0, DriveSignals.engineSpeed to 3000.0), now) as Fresh.Value
        assertEquals(100 * 2 * PI * 3000 / 60_000, engine.value, 1e-9)
        assertEquals(Fresh.Missing("giri: 7DF: in attesa"), enginePowerKw(state(DriveSignals.engineTorque to 100.0), now))
    }

    private fun simulatedDrive(car: SimulatedCar, recorder: JsonlSessionRecorder? = null) =
        AcquisitionSession(live, car, DriveSignals.schedule, recorder, fastTimeouts)

    @Test
    fun `reads the drive signals addressing each control unit, read-only`() {
        var clock = 0L
        val car = SimulatedCar(clock = { clock })
        clock = 35_000 // braking with friction at 38 km/h: M = −150, R = −70
        val session = simulatedDrive(car)
        val state = runUntil(session) { it.readings.size == session.signals.size }

        val now = System.currentTimeMillis()
        assertEquals(BrakeIndicator.Friction(53), brakeIndicator(state, now))
        assertEquals(-18.0, (batteryPowerKw(state, now) as Fresh.Value).value, 0.01)
        assertEquals(0.0, (enginePowerKw(state, now) as Fresh.Value).value)
        assertTrue(driveEcuStatus(state, now).all { it.level == LinkLevel.OK })

        val sent = state.recentExchanges.map { it.command }
        assertTrue(sent.all(ReadOnlyCommands::isAllowed))
        val firstBrake = sent.indexOf("221067")
        assertEquals(listOf("ATSH 7D2", "ATCRA 7DA", "ATFCSH 7D2", "ATFCSD 30 00 00", "ATFCSM 1"), sent.subList(firstBrake - 5, firstBrake))
        assertEquals(1, sent.count { it == "ATFCSM 1" })
    }

    @Test
    fun `a silent battery ECU falls back to 7D2 and is asked less and less often`() {
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = object : SessionRecorder {
            override fun exchange(exchange: Exchange) {
                sent += exchange.command
            }

            override fun event(atEpochMs: Long, event: String, detail: String?) = Unit
        }
        val session = AcquisitionSession(live, SimulatedCar(silentEcus = setOf("747")), DriveSignals.schedule, recorder, fastTimeouts)
        val state = runUntil(session) { sent.count { it == "221067" } >= 40 }
        val now = System.currentTimeMillis()
        assertTrue(batteryPowerKw(state, now) is Fresh.Value)
        assertEquals("non risponde (NO DATA)", state.readings.getValue(DriveSignals.batteryPower.id).failure)
        // 40 rounds give 747 twenty turns: without the backoff 20 asks, with it (1, 2, 4, 8, 16 skipped turns) 5.
        assertTrue(sent.count { it == "ATSH 747" } <= 5, "747 interrogata ${sent.count { it == "ATSH 747" }} volte")
        assertEquals(LinkLevel.ERROR, driveEcuStatus(state, now).single { it.label == "747" }.level)
    }

    @Test
    fun `a refusing hybrid ECU makes only the brake unavailable, with the cause`() {
        val session = simulatedDrive(SimulatedCar(refusingEcus = setOf("7D2")))
        val state = runUntil(session) { it.readings.size == session.signals.size }
        val now = System.currentTimeMillis()
        assertEquals(BrakeIndicator.Unavailable("7D2: rifiuta 221067 (7F 22 31)"), brakeIndicator(state, now))
        assertTrue(enginePowerKw(state, now) is Fresh.Value)
    }

    @Test
    fun `the simulated car coasts with the accelerator released and the ring shows the lift-off regeneration`() {
        var clock = 0L
        val car = SimulatedCar(clock = { clock })
        clock = 27_000 // accelerator released at 66 km/h, engine off, battery charging at −4 kW
        val session = simulatedDrive(car)
        val state = runUntil(session) { it.readings.size == session.signals.size }
        assertEquals(BrakeIndicator.Regenerative(16, liftOff = true), brakeIndicator(state, System.currentTimeMillis()))
    }

    @Test
    fun `a recorded drive replays through the same schedule`() {
        var clock = 0L
        val car = SimulatedCar(clock = { clock })
        clock = 31_000 // regenerative braking
        val writer = StringWriter()
        val header = RecordingHeader(startedAtEpochMs = 0, source = RecordingHeader.SIMULATED, appVersion = "test", note = "guida")
        val session = simulatedDrive(car, JsonlSessionRecorder(writer, header))
        runUntil(session) { state -> state.recentExchanges.count { it.command == "221067" } >= 8 }
        val recorded = session.state.value // after the stop: includes every recorded exchange

        val (_, lines) = Recording.parse(writer.toString().lineSequence())
        assertTrue(lines.any { it is RecordedExchange && it.cmd == "221069" })
        val replay = AcquisitionSession(SessionSource.Replay("guida", simulated = true), ReplayConnector(lines), DriveSignals.schedule)
        val replayed = runUntil(replay) { it.phoneLink == PhoneLinkState.FINISHED }

        for (signal in replay.signals) {
            assertEquals(recorded.readings[signal.id]?.value, replayed.readings[signal.id]?.value, signal.id)
        }
        assertEquals(-60.0, replayed.readings.getValue(DriveSignals.regenBrakeTorque.id).value)
    }
}
