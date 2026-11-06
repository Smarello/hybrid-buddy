package it.techreloaded.hybridbuddy.interpretation

import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.signals.DriveSignals
import it.techreloaded.hybridbuddy.signals.EcuTarget
import it.techreloaded.hybridbuddy.signals.SignalDefinition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.truncate

/**
 * Brake indicator of the Guida screen. Pedal braking is classified as Hybrid Assistant does (`carstate`, `default` branch
 * for the Yaris MY20): braking when M < 0 or R < 0, friction when M − R < 0, friction share (M − R)·100/M; regeneration
 * intensity min(1, |P / charge limit|). M = torque requested by the brake, R = regenerative torque, P = battery power.
 * M and R are truncated to whole Nm before the comparison, as Hybrid Assistant stores them (integers in `scanner`): a
 * difference of a fraction of Nm (M −50.875, R −50.75 on the first drive) is regeneration only, not "friction 0 %".
 * Without the pedal, the regeneration on accelerator release is read from the battery power while [DriveState.coasting],
 * as the HyBall of Hybrid Assistant does outside braking (D-026).
 */
sealed interface BrakeIndicator {
    /** Filling of the ring, 0..1: first half green (regeneration), second half red (friction share). */
    val fill: Double

    /** Grey ring: no braking, or friction below [DriveSignals.BRAKE_MIN_SPEED_KMH] (unavoidable to stop). */
    data class Idle(val label: String) : BrakeIndicator {
        override val fill = 0.0
    }

    /**
     * Green: regeneration only; [intensityPercent] null when battery power or charge limit is missing;
     * [liftOff] when it comes from releasing the accelerator, not from the brake pedal.
     */
    data class Regenerative(val intensityPercent: Int?, val liftOff: Boolean = false) : BrakeIndicator {
        override val fill = (intensityPercent ?: 100) / 200.0
    }

    /** Red: friction brakes dissipate [frictionPercent] % of the braking. */
    data class Friction(val frictionPercent: Int) : BrakeIndicator {
        override val fill = 0.5 + frictionPercent / 200.0
    }

    /** [error] true when a signal it needs failed (yellow triangle), false while a value is awaited or late (grey). */
    data class Unavailable(val reason: String, val error: Boolean = false) : BrakeIndicator {
        override val fill = 0.0
    }

    companion object {
        const val NOT_BRAKING = "Nessuna frenata"
        const val BELOW_BRAKE_SPEED = "Sotto 15 km/h"
    }
}

/**
 * What the car is doing, derived at every evaluation from the fresh signals, as `carstate` of Hybrid Assistant does
 * (D-026): EV = engine rpm < 800, engine spinning = torque < 0 with rpm ≥ 800, engine off = EV or spinning,
 * moving = speed ≥ 2 km/h, coasting = moving with the accelerator released, no pedal braking and the engine off.
 * A state is null when a signal it needs is missing; [coastingMissing] names the first missing one.
 */
class DriveState(state: SessionState, nowEpochMs: Long) {
    val brakeRequest = drive(DriveSignals.brakeRequestTorque, state, nowEpochMs)
    val regen = drive(DriveSignals.regenBrakeTorque, state, nowEpochMs)
    val speed = drive(DriveSignals.vehicleSpeed, state, nowEpochMs)
    val accelerator = drive(DriveSignals.acceleratorPedal, state, nowEpochMs)
    val engineRpm = drive(DriveSignals.engineSpeed, state, nowEpochMs)
    val engineTorque = drive(DriveSignals.engineTorque, state, nowEpochMs)
    val batteryPower = batteryPowerKw(state, nowEpochMs)
    val chargeLimit = preferBatteryEcu(DriveSignals.chargeLimit, DriveSignals.chargeLimitFromHybridEcu, state, nowEpochMs)

    val braking: Boolean? = pedal { m, r -> m < 0 || r < 0 }
    val frictionBraking: Boolean? = pedal { m, r -> (m < 0 || r < 0) && m - r < 0 }
    /** (M − R)·100/M on the truncated torques, 0..100; null when not braking with friction. */
    val frictionPercent: Int? = if (frictionBraking == true) {
        val m = truncate((brakeRequest as Fresh.Value).value)
        val r = truncate((regen as Fresh.Value).value)
        ((m - r) * 100 / m).toInt().coerceIn(0, 100)
    } else {
        null
    }
    val moving: Boolean? = speed.test { it >= DriveSignals.MOVING_MIN_SPEED_KMH }
    val acceleratorPressed: Boolean? = accelerator.test { it > 0 }
    val engineOff: Boolean? = when (val rpm = engineRpm) {
        is Fresh.Missing -> null
        is Fresh.Value -> if (rpm.value < DriveSignals.EV_MAX_RPM) true else engineTorque.test { it < 0 }
    }
    val regenerating: Boolean? = batteryPower.test { it < 0 }
    /** EV of `carstate`: engine rpm below [DriveSignals.EV_MAX_RPM]; null without the rpm. */
    val electricOnly: Boolean? = engineRpm.test { it < DriveSignals.EV_MAX_RPM }
    /** Engine icon of the Hybrid Assistant dashboard (user guide §8.1); null when a signal it needs is missing. */
    val engineState: EngineState? = when (electricOnly) {
        null -> null
        true -> EngineState.OFF
        false -> engineTorque.test { it < 0 }?.let { spinning -> if (spinning) EngineState.SPINNING else EngineState.RUNNING }
    }
    /** min(1, |P / charge limit|)·100; null when power or limit is missing. */
    val regenIntensityPercent: Int? =
        if (batteryPower is Fresh.Value && chargeLimit is Fresh.Value) {
            if (chargeLimit.value == 0.0) 100 else (min(1.0, abs(batteryPower.value / chargeLimit.value)) * 100).toInt()
        } else {
            null
        }

    /** Null when undecidable: a stationary car or a pressed pedal decides it before the other signals are needed. */
    val coasting: Boolean?
    val coastingMissing: Fresh.Missing?

    init {
        val decided: Pair<Boolean?, Fresh.Missing?> = when {
            moving == null -> null to (speed as Fresh.Missing).named("velocità")
            moving == false -> false to null
            braking == null -> null to ((brakeRequest as? Fresh.Missing) ?: regen as Fresh.Missing)
            braking == true -> false to null
            acceleratorPressed == null -> null to (accelerator as Fresh.Missing).named("acceleratore")
            acceleratorPressed == true -> false to null
            engineOff == null -> null to when (val rpm = engineRpm) {
                is Fresh.Missing -> rpm.named("giri")
                is Fresh.Value -> (engineTorque as Fresh.Missing).named("coppia termica")
            }
            else -> engineOff to null
        }
        coasting = decided.first
        coastingMissing = decided.second
    }

    /** Torques truncated to whole Nm, as Hybrid Assistant compares them. */
    private fun pedal(test: (m: Double, r: Double) -> Boolean): Boolean? {
        val m = brakeRequest as? Fresh.Value ?: return null
        val r = regen as? Fresh.Value ?: return null
        return test(truncate(m.value), truncate(r.value))
    }

    private fun Fresh.test(test: (Double) -> Boolean): Boolean? = (this as? Fresh.Value)?.let { test(it.value) }
}

/** Grey engine stopped, green running, yellow spinning without fuel (dragged or engine braking), as Hybrid Assistant colours it. */
enum class EngineState { OFF, RUNNING, SPINNING }

fun brakeIndicator(state: SessionState, nowEpochMs: Long): BrakeIndicator {
    val car = DriveState(state, nowEpochMs)
    (car.brakeRequest as? Fresh.Missing)?.let { return BrakeIndicator.Unavailable(it.reason, it.error) }
    (car.regen as? Fresh.Missing)?.let { return BrakeIndicator.Unavailable(it.reason, it.error) }
    if (car.braking == true) {
        if (car.frictionBraking != true) return BrakeIndicator.Regenerative(car.regenIntensityPercent)
        val speed = when (val fresh = car.speed) {
            is Fresh.Missing -> return BrakeIndicator.Unavailable("velocità: ${fresh.reason}", fresh.error)
            is Fresh.Value -> fresh.value
        }
        if (speed <= DriveSignals.BRAKE_MIN_SPEED_KMH) return BrakeIndicator.Idle(BrakeIndicator.BELOW_BRAKE_SPEED)
        return BrakeIndicator.Friction(car.frictionPercent!!)
    }
    return when (car.coasting) {
        null -> car.coastingMissing!!.let { BrakeIndicator.Unavailable("rilascio: ${it.reason}", it.error) }
        false -> BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING)
        true -> when (car.regenerating) {
            null -> (car.batteryPower as Fresh.Missing).let { BrakeIndicator.Unavailable("rilascio: ${it.reason}", it.error) }
            false -> BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING)
            true -> BrakeIndicator.Regenerative(car.regenIntensityPercent, liftOff = true)
        }
    }
}

/** Battery power in kW, negative while charging: 747 first (Li-ion branch of Hybrid Assistant), then 7D2. */
fun batteryPowerKw(state: SessionState, nowEpochMs: Long): Fresh =
    preferBatteryEcu(DriveSignals.batteryPower, DriveSignals.batteryPowerFromHybridEcu, state, nowEpochMs)

/** Engine power in kW = torque · 2π · rpm / 60000, as Hybrid Assistant computes it. */
fun enginePowerKw(state: SessionState, nowEpochMs: Long): Fresh {
    val torque = drive(DriveSignals.engineTorque, state, nowEpochMs)
    val rpm = drive(DriveSignals.engineSpeed, state, nowEpochMs)
    if (torque is Fresh.Missing) return torque
    if (rpm is Fresh.Missing) return rpm.named("giri")
    torque as Fresh.Value
    rpm as Fresh.Value
    return Fresh.Value(torque.value * 2 * PI * rpm.value / 60_000, maxOf(torque.ageMs, rpm.ageMs))
}

/** Successful reads per second of the brake torque requested, over the last few seconds. */
fun brakeReadsPerSecond(state: SessionState, nowEpochMs: Long): Double =
    state.recentExchanges.count {
        it.command in BRAKE_REQUEST_COMMANDS && it.error == null && nowEpochMs - it.startedAtEpochMs <= RATE_WINDOW_MS
    } / (RATE_WINDOW_MS / 1000.0)

private val BRAKE_REQUEST_COMMANDS = setOf(DriveSignals.brakeTorques.command, DriveSignals.brakeRequestTorque.command)

/** One control unit of the top line: OK when one of its signals is current, ERROR when all of them failed. */
fun driveEcuStatus(state: SessionState, nowEpochMs: Long): List<ChainLink> {
    val adapter = when (state.phoneLink) {
        PhoneLinkState.CONNECTED -> LinkLevel.OK
        PhoneLinkState.FINISHED -> LinkLevel.IDLE
        else -> LinkLevel.WARNING
    }
    val units = listOf(
        "Auto" to DriveSignals.obdEngineEcu, "7D2" to DriveSignals.hybridEcu,
        "747" to DriveSignals.batteryEcu, "700" to DriveSignals.engineDidEcu,
    ).map { (label, ecu) ->
        val signals = DriveSignals.polled.filter { it.target == ecu }
        val level = when {
            state.phoneLink != PhoneLinkState.CONNECTED -> LinkLevel.IDLE
            signals.any { freshValue(it, state, nowEpochMs) is Fresh.Value } -> LinkLevel.OK
            signals.all { state.readings[it.id]?.failure != null } -> LinkLevel.ERROR
            else -> LinkLevel.WARNING
        }
        ChainLink(level, label)
    }
    return listOf(ChainLink(adapter, "LX")) + units
}

private fun preferBatteryEcu(fromBattery: SignalDefinition, fromHybrid: SignalDefinition, state: SessionState, nowEpochMs: Long): Fresh {
    val battery = drive(fromBattery, state, nowEpochMs)
    if (battery is Fresh.Value) return battery
    val hybrid = drive(fromHybrid, state, nowEpochMs)
    if (hybrid is Fresh.Value) return hybrid
    battery as Fresh.Missing
    hybrid as Fresh.Missing
    // Alternatives: an error only when neither control unit can give the value.
    return Fresh.Missing("${battery.reason} · ${hybrid.reason}", battery.error && hybrid.error)
}

private fun Fresh.Missing.named(what: String) = copy(reason = "$what: $reason")

/** Like [freshValue], with the control unit in the reason, e.g. "7D2: rifiuta 221067 (7F 22 31)". */
private fun drive(signal: SignalDefinition, state: SessionState, nowEpochMs: Long): Fresh =
    when (val fresh = freshValue(signal, state, nowEpochMs)) {
        is Fresh.Value -> fresh
        is Fresh.Missing -> fresh.copy(reason = signal.target.prefix() + fresh.reason)
    }

private fun EcuTarget?.prefix() = if (this == null) "" else "$requestHeader: "
