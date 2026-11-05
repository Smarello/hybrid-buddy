package it.techreloaded.hybridbuddy.signals

/** Result of decoding one adapter response. */
sealed interface Decoded {
    /** [ecu] is the CAN header of the answering control unit (e.g. 7E8), null when not applicable. */
    data class Value(val value: Double, val ecu: String?) : Decoded
    data class Failure(val reason: String) : Decoded
}

/**
 * A readable signal: request sent to the adapter and conversion of its answer.
 * Provenance and validation state of every definition are tracked in SIGNALS.md.
 */
class SignalDefinition(
    val id: String,
    val label: String,
    val command: String,
    val unit: String,
    val decimals: Int,
    val decode: (lines: List<String>) -> Decoded,
)

/** Standard OBD-II mode 01 PIDs (SAE J1979) plus the adapter's supply voltage. */
object BaseSignals {
    /** CAN 11-bit header of the engine ECU; preferred when several ECUs answer the same PID. */
    const val ENGINE_ECU = "7E8"

    val engineSpeed = mode01("engine_rpm", "Giri motore termico", "0C", "rpm", 0, 2) { (a, b) -> (a * 256 + b) / 4.0 }
    val vehicleSpeed = mode01("vehicle_speed", "Velocità veicolo", "0D", "km/h", 0, 1) { (a) -> a.toDouble() }
    val coolantTemperature = mode01("coolant_temp", "Temperatura refrigerante", "05", "°C", 0, 1) { (a) -> a - 40.0 }
    val calculatedLoad = mode01("calculated_load", "Carico calcolato", "04", "%", 0, 1) { (a) -> a * 100.0 / 255 }

    val adapterVoltage = SignalDefinition("adapter_voltage", "Tensione 12 V (adattatore)", "ATRV", "V", 1) { lines ->
        lines.firstNotNullOfOrNull { Regex("""^(\d+(\.\d+)?)V$""").find(it) }
            ?.let { Decoded.Value(it.groupValues[1].toDouble(), null) }
            ?: Decoded.Failure("formato inatteso")
    }

    val all = listOf(engineSpeed, vehicleSpeed, coolantTemperature, calculatedLoad, adapterVoltage)

    private fun mode01(
        id: String, label: String, pid: String, unit: String, decimals: Int, dataBytes: Int,
        convert: (IntArray) -> Double,
    ) = SignalDefinition(id, label, "01$pid", unit, decimals) { lines ->
        val answers = Mode01.answers(lines, pid.toInt(16)).filter { it.data.size >= dataBytes }
        val chosen = answers.firstOrNull { it.ecu == ENGINE_ECU } ?: answers.firstOrNull()
        if (chosen == null) Decoded.Failure("risposta non valida")
        else Decoded.Value(convert(chosen.data), chosen.ecu)
    }
}

object Mode01 {
    data class Answer(val ecu: String?, val data: IntArray)

    /**
     * Mode 01 answers for [pid] found in the response lines. Accepts CAN 11-bit frames with
     * headers on (`7E8 04 41 0C 13 60`, PCI byte gives the length) or headers off (`41 0C 13 60`).
     */
    // ponytail: single-frame CAN 11-bit only (Yaris 2026 is CAN 11/500); add 29-bit/multi-frame when a signal needs it.
    fun answers(lines: List<String>, pid: Int): List<Answer> = lines.mapNotNull { line ->
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        val (ecu, payload) = when {
            tokens.size >= 2 && tokens[0].length == 3 -> {
                val length = tokens[1].toIntOrNull(16) ?: return@mapNotNull null
                tokens[0] to tokens.drop(2).take(length)
            }
            else -> null to tokens
        }
        val bytes = payload.map { it.toIntOrNull(16) ?: return@mapNotNull null }
        if (bytes.size < 2 || bytes[0] != 0x41 || bytes[1] != pid) return@mapNotNull null
        Answer(ecu, bytes.drop(2).toIntArray())
    }
}
