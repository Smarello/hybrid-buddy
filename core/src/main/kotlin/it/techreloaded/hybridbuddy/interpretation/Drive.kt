package it.techreloaded.hybridbuddy.interpretation

import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.signals.DriveSignals
import it.techreloaded.hybridbuddy.signals.EcuTarget
import it.techreloaded.hybridbuddy.signals.SignalDefinition
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

/**
 * Brake indicator of the Guida screen, same rule as Hybrid Assistant (`carstate`, `default` branch for the Yaris MY20):
 * braking when M < 0 or R < 0, friction when M − R < 0, friction share (M − R)·100/M; regeneration intensity
 * min(1, |P / charge limit|). M = torque requested by the brake, R = regenerative torque, P = battery power.
 */
sealed interface BrakeIndicator {
    /** Filling of the ring, 0..1: first half green (regeneration), second half red (friction share). */
    val fill: Double

    /** Grey ring: no braking, or friction below [DriveSignals.BRAKE_MIN_SPEED_KMH] (unavoidable to stop). */
    data class Idle(val label: String) : BrakeIndicator {
        override val fill = 0.0
    }

    /** Green: regeneration only; [intensityPercent] null when battery power or charge limit is missing. */
    data class Regenerative(val intensityPercent: Int?) : BrakeIndicator {
        override val fill = (intensityPercent ?: 100) / 200.0
    }

    /** Red: friction brakes dissipate [frictionPercent] % of the braking. */
    data class Friction(val frictionPercent: Int) : BrakeIndicator {
        override val fill = 0.5 + frictionPercent / 200.0
    }

    data class Unavailable(val reason: String) : BrakeIndicator {
        override val fill = 0.0
    }

    companion object {
        const val NOT_BRAKING = "Nessuna frenata"
        const val BELOW_BRAKE_SPEED = "Sotto 15 km/h"
    }
}

fun brakeIndicator(state: SessionState, nowEpochMs: Long): BrakeIndicator {
    val requested = when (val fresh = drive(DriveSignals.brakeRequestTorque, state, nowEpochMs)) {
        is Fresh.Missing -> return BrakeIndicator.Unavailable(fresh.reason)
        is Fresh.Value -> fresh.value
    }
    val regenerative = when (val fresh = drive(DriveSignals.regenBrakeTorque, state, nowEpochMs)) {
        is Fresh.Missing -> return BrakeIndicator.Unavailable(fresh.reason)
        is Fresh.Value -> fresh.value
    }
    if (requested >= 0 && regenerative >= 0) return BrakeIndicator.Idle(BrakeIndicator.NOT_BRAKING)
    if (requested - regenerative < 0) {
        val speed = when (val fresh = drive(DriveSignals.vehicleSpeed, state, nowEpochMs)) {
            is Fresh.Missing -> return BrakeIndicator.Unavailable("velocità: ${fresh.reason}")
            is Fresh.Value -> fresh.value
        }
        if (speed <= DriveSignals.BRAKE_MIN_SPEED_KMH) return BrakeIndicator.Idle(BrakeIndicator.BELOW_BRAKE_SPEED)
        return BrakeIndicator.Friction(((requested - regenerative) * 100 / requested).toInt().coerceIn(0, 100))
    }
    val power = batteryPowerKw(state, nowEpochMs)
    val limit = preferBatteryEcu(DriveSignals.chargeLimit, DriveSignals.chargeLimitFromHybridEcu, state, nowEpochMs)
    val intensity = if (power is Fresh.Value && limit is Fresh.Value) {
        if (limit.value == 0.0) 100 else (min(1.0, abs(power.value / limit.value)) * 100).toInt()
    } else {
        null
    }
    return BrakeIndicator.Regenerative(intensity)
}

/** Battery power in kW, negative while charging: 747 first (Li-ion branch of Hybrid Assistant), then 7D2. */
fun batteryPowerKw(state: SessionState, nowEpochMs: Long): Fresh =
    preferBatteryEcu(DriveSignals.batteryPower, DriveSignals.batteryPowerFromHybridEcu, state, nowEpochMs)

/** Engine power in kW = torque · 2π · rpm / 60000, as Hybrid Assistant computes it. */
fun enginePowerKw(state: SessionState, nowEpochMs: Long): Fresh {
    val torque = drive(DriveSignals.engineTorque, state, nowEpochMs)
    val rpm = drive(DriveSignals.engineSpeed, state, nowEpochMs)
    if (torque is Fresh.Missing) return torque
    if (rpm is Fresh.Missing) return Fresh.Missing("giri: ${rpm.reason}")
    torque as Fresh.Value
    rpm as Fresh.Value
    return Fresh.Value(torque.value * 2 * PI * rpm.value / 60_000, maxOf(torque.ageMs, rpm.ageMs))
}

/** Successful reads per second of the brake torque requested, over the last few seconds. */
fun brakeReadsPerSecond(state: SessionState, nowEpochMs: Long): Double =
    state.recentExchanges.count {
        it.command == DriveSignals.brakeRequestTorque.command && it.error == null && nowEpochMs - it.startedAtEpochMs <= RATE_WINDOW_MS
    } / (RATE_WINDOW_MS / 1000.0)

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
        val signals = DriveSignals.schedule.flatten().distinct().filter { it.target == ecu }
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
    return Fresh.Missing("${(battery as Fresh.Missing).reason} · ${(hybrid as Fresh.Missing).reason}")
}

/** Like [freshValue], with the control unit in the reason, e.g. "7D2: rifiuta 221067 (7F 22 31)". */
private fun drive(signal: SignalDefinition, state: SessionState, nowEpochMs: Long): Fresh =
    when (val fresh = freshValue(signal, state, nowEpochMs)) {
        is Fresh.Value -> fresh
        is Fresh.Missing -> Fresh.Missing(signal.target.prefix() + fresh.reason)
    }

private fun EcuTarget?.prefix() = if (this == null) "" else "$requestHeader: "
