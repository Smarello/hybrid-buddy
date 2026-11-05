package it.techreloaded.hybridbuddy.signals

import it.techreloaded.hybridbuddy.obdlink.IsoTp

/**
 * Signals of the Guida screen: the recipe Hybrid Assistant uses for the Yaris MY20 (docs/hybrid-assistant-hyball-analisi.md,
 * §4a and §8), read here as direct mode 22 requests of the source DIDs. All are candidates: never read on the Yaris 2026.
 * Data bytes are counted from the first byte after `62 DID DID` (b1 = index 0).
 */
object DriveSignals {
    /**
     * OBD-II mode 01 (speed, rpm): functional request 7DF with the answers filtered to the engine ECU 7E8. On the Yaris
     * 2026 the physical address 7E0 got NO DATA to all 44 mode 01 requests of the first drive (2026-11-05), while 7DF
     * was answered by 7E8 minutes before (D-023).
     */
    val obdEngineEcu = EcuTarget("7DF", "7E8")
    val hybridEcu = EcuTarget("7D2", "7DA")
    val batteryEcu = EcuTarget("747", "74F")

    /** Engine control unit used by Hybrid Assistant for the engine DIDs (700 → 708). */
    val engineDidEcu = EcuTarget("700", "708")

    /** `brake_mastcyl_trq` in Hybrid Assistant: torque requested by the brake, ≤ 0 while braking. */
    val brakeRequestTorque = mode22("brake_request_torque", "Coppia richiesta dal freno", hybridEcu, "1067", "Nm", 0, 2) { b ->
        signed16(b, 0) / 8.0
    }

    /** `brake_regen_trq` in Hybrid Assistant: regenerative torque, ≤ 0 while braking. */
    val regenBrakeTorque = mode22("regen_brake_torque", "Coppia rigenerativa", hybridEcu, "1069", "Nm", 0, 4) { b ->
        signed16(b, 2) / 8.0
    }

    /** `1060` b1 / 2 in Hybrid Assistant: accelerator pedal, "pressed" when > 0 (meaning inferred from its use, SIGNALS.md). */
    val acceleratorPedal = mode22("accelerator_pedal", "Pedale acceleratore", hybridEcu, "1060", "%", 0, 1) { b -> b[0] / 2.0 }

    val batteryPower = batteryPower(batteryEcu)
    val batteryPowerFromHybridEcu = batteryPower(hybridEcu)
    val chargeLimit = chargeLimit(batteryEcu)
    val chargeLimitFromHybridEcu = chargeLimit(hybridEcu)

    /** Engine torque as Hybrid Assistant uses it (DID 10C2, raw − 32768): could be a requested torque, not the actual one. */
    val engineTorque = mode22("engine_torque", "Coppia motore termico", engineDidEcu, "10C2", "Nm", 0, 2) { b ->
        signed16(b, 0).toDouble()
    }

    val vehicleSpeed = BaseSignals.vehicleSpeed.on(obdEngineEcu)
    val engineSpeed = BaseSignals.engineSpeed.on(obdEngineEcu)

    /** Speed above which friction counts while braking (`m_brakespeed` of Hybrid Assistant for the Yaris MY20). */
    const val BRAKE_MIN_SPEED_KMH = 15.0

    /** "Moving" state of Hybrid Assistant (`carstate`, `_v130_5`). */
    const val MOVING_MIN_SPEED_KMH = 2.0

    /** Below this engine speed the car is in EV for Hybrid Assistant (`carstate`, `_v130_4`). */
    const val EV_MAX_RPM = 800.0

    /**
     * Polling rounds, cycled: the brake torques and the accelerator in every round, everything else every other round, so that no signal
     * expires between two reads (STALE_AFTER_MS = 2 s). First drive (2026-11-05): a round took 0.33 s median, 0.6 s p90,
     * so with four rounds the engine torque was read every 1.2–2.2 s and kept expiring (D-025). Five control unit
     * switches per cycle. The battery is read on both 747 and 7D2: the interpretation prefers 747 (Li-ion branch of
     * Hybrid Assistant).
     */
    val schedule: List<List<SignalDefinition>> = listOf(
        listOf(brakeRequestTorque, regenBrakeTorque, acceleratorPedal, vehicleSpeed, engineSpeed, batteryPower, chargeLimit),
        listOf(brakeRequestTorque, regenBrakeTorque, acceleratorPedal, batteryPowerFromHybridEcu, chargeLimitFromHybridEcu, engineTorque),
    )

    /**
     * DID 1F9A: voltage b3-b4 /64 V, current b5-b6 two's complement /10 A, negative while charging; P = V·I/1000.
     * First drive (2026-11-05): +3 A at rest, −159…+156 A on the road; the (raw − 32768) form of Hybrid Assistant's
     * direct read would give ±3270 A (D-024).
     */
    private fun batteryPower(ecu: EcuTarget) =
        mode22("battery_power_${ecu.requestHeader}", "Potenza batteria", ecu, "1F9A", "kW", 1, 6) { b ->
            val volts = unsigned16(b, 2) / 64.0
            val amps = twosComplement16(b, 4) / 10.0
            volts * amps / 1000
        }

    /** DID 1807 b1-b2: charge limit, used by Hybrid Assistant as the scale of the regeneration. */
    private fun chargeLimit(ecu: EcuTarget) =
        mode22("charge_limit_${ecu.requestHeader}", "Limite di carica", ecu, "1807", "kW", 1, 2) { b ->
            signed16(b, 0) / 100.0
        }

    private fun unsigned16(bytes: List<Int>, index: Int) = bytes[index] * 256 + bytes[index + 1]
    private fun signed16(bytes: List<Int>, index: Int) = unsigned16(bytes, index) - 32768
    private fun twosComplement16(bytes: List<Int>, index: Int) = unsigned16(bytes, index).let { if (it >= 32768) it - 65536 else it }

    private fun mode22(
        id: String, label: String, ecu: EcuTarget, did: String, unit: String, decimals: Int, dataBytes: Int,
        convert: (List<Int>) -> Double,
    ) = SignalDefinition(id, label, "22$did", unit, decimals, ecu) { lines ->
        when (val answer = Mode22.read(lines, ecu.responseHeader, did.toInt(16))) {
            is Mode22.Answer.Data ->
                if (answer.bytes.size < dataBytes) Decoded.Failure("risposta corta a 22$did")
                else Decoded.Value(convert(answer.bytes), ecu.responseHeader)
            is Mode22.Answer.Refused -> Decoded.Failure("rifiuta 22$did (7F 22 %02X)".format(answer.code))
            Mode22.Answer.None -> Decoded.Failure("nessuna risposta valida a 22$did")
        }
    }
}

object Mode22 {
    sealed interface Answer {
        /** Data bytes after `62 DID DID`. */
        data class Data(val bytes: List<Int>) : Answer
        /** Negative response `7F 22 code` (e.g. 31 = requestOutOfRange). */
        data class Refused(val code: Int) : Answer
        data object None : Answer
    }

    /** Last answer of [responseHeader] to `22 [did]`, reassembled from single or multi-frame CAN messages. */
    fun read(lines: List<String>, responseHeader: String, did: Int): Answer {
        val last = IsoTp.messages(lines)
            .filter { it.ecu == responseHeader }
            // 7F 22 78 = "response pending": the real answer follows.
            .lastOrNull { !(it.payload.size >= 3 && it.payload[0] == 0x7F && it.payload[2] == 0x78) }
            ?.payload ?: return Answer.None
        return when {
            last.size >= 3 && last[0] == 0x62 && last[1] == did shr 8 && last[2] == did and 0xFF -> Answer.Data(last.drop(3))
            last.size >= 3 && last[0] == 0x7F && last[1] == 0x22 -> Answer.Refused(last[2])
            else -> Answer.None
        }
    }
}
