package it.techreloaded.hybridbuddy.session

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.AdapterLink
import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import kotlin.math.roundToInt

/**
 * SIMULATED Yaris answering like the OBDLink LX with the DIDs of [it.techreloaded.hybridbuddy.signals.DriveSignals],
 * on a 60 s loop: acceleration, cruise, regenerative braking, braking with friction, stop below 15 km/h, standstill.
 * Invented values for trying the Guida screen and for tests: they prove nothing about the car.
 * Control units in [silentEcus] (request headers) answer NO DATA, those in [refusingEcus] answer `7F 22 31`.
 * With [refusesTwoDids] 7D2 answers `7F 22 13` (incorrect length) to a request with two DIDs.
 */
class SimulatedCar(
    private val clock: () -> Long = System::currentTimeMillis,
    private val silentEcus: Set<String> = emptySet(),
    private val refusingEcus: Set<String> = emptySet(),
    private val replyDelayMs: Long = 0,
    private val refusesTwoDids: Boolean = false,
) : AdapterConnector {
    private val startedAt = clock()

    /** Car state at [seconds] into the loop. Torques in Nm (≤ 0 braking), powers in kW (battery < 0 = charging), pedal in %. */
    data class Moment(
        val speedKmh: Double, val rpm: Double, val engineTorqueNm: Double,
        val brakeRequestNm: Double, val regenNm: Double, val batteryKw: Double, val acceleratorPercent: Double,
    )

    override suspend fun connect(): AdapterLink {
        var header = "7DF"
        return InMemoryAdapterLink { command ->
            if (command.startsWith("ATSH ")) header = command.removePrefix("ATSH ")
            AdapterReply.Text(listOf(reply(command, header) + ElmConnection.PROMPT), replyDelayMs)
        }
    }

    private fun reply(command: String, header: String): String {
        when (command) {
            "ATZ" -> return "\r\rELM327 v1.4b\r\r"
            "ATI" -> return "ELM327 v1.4b\r\r"
            "STI" -> return "STN1155 (simulato)\r\r"
            "ATDP" -> return "AUTO, ISO 15765-4 (CAN 11/500)\r\r"
            "ATRV" -> return "14.2V\r\r"
            "0100" -> return "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 \r\r"
        }
        if (command.startsWith("AT")) return "OK\r\r"
        if (header in silentEcus) return "NO DATA\r\r"
        val response = responseHeader(header) ?: return "NO DATA\r\r"
        if (header in refusingEcus) return frames(response, listOf(0x7F, command.substring(0, 2).toInt(16), 0x31))
        if (refusesTwoDids && command.length > 6) return frames(response, listOf(0x7F, 0x22, 0x13))
        val now = moment(((clock() - startedAt) / 1000.0) % LOOP_SECONDS)
        val payload = when (header to command) {
            "7DF" to "010C" -> listOf(0x41, 0x0C) + word((now.rpm * 4).roundToInt())
            "7DF" to "010D" -> listOf(0x41, 0x0D, now.speedKmh.roundToInt())
            "7D2" to "221067" -> did(0x1067, word(signed(now.brakeRequestNm * 8)) + listOf(0, 0))
            "7D2" to "221069" -> did(0x1069, listOf(0, 0) + word(signed(now.regenNm * 8)))
            "7D2" to "2210671069" ->
                did(0x1067, word(signed(now.brakeRequestNm * 8))) + listOf(0x10, 0x69, 0, 0) + word(signed(now.regenNm * 8))
            "7D2" to "221060" -> did(0x1060, listOf((now.acceleratorPercent * 2).roundToInt()))
            "747" to "221F9A", "7D2" to "221F9A" -> {
                val amps = now.batteryKw * 1000 / VOLTS
                did(0x1F9A, listOf(0, 0) + word((VOLTS * 64).roundToInt()) + word(twosComplement(amps * 10)) + listOf(0))
            }
            "747" to "221807", "7D2" to "221807" -> did(0x1807, word(signed(CHARGE_LIMIT_KW * 100)) + word(signed(DISCHARGE_LIMIT_KW * 100)))
            "700" to "2210C2" -> did(0x10C2, word(signed(now.engineTorqueNm)))
            else -> return "NO DATA\r\r"
        }
        return frames(response, payload)
    }

    companion object {
        const val LOOP_SECONDS = 60.0
        private const val VOLTS = 200.0
        private const val CHARGE_LIMIT_KW = 25.0
        private const val DISCHARGE_LIMIT_KW = 30.0

        /** Acceleration, cruise, accelerator released (coasting), regenerative braking, friction braking, stop, standstill. */
        fun moment(seconds: Double): Moment = when {
            seconds < 15 -> Moment(70 * seconds / 15, 2500.0, 90.0, 0.0, 0.0, 8.0, 40.0)
            seconds < 25 -> Moment(70.0, 1800.0, 60.0, 0.0, 0.0, 1.5, 20.0)
            seconds < 29 -> Moment(70 - 8 * (seconds - 25) / 4, 0.0, 0.0, 0.0, 0.0, -4.0, 0.0)
            seconds < 33 -> Moment(62 - 17 * (seconds - 29) / 4, 0.0, 0.0, -60.0, -60.0, -12.0, 0.0)
            seconds < 41 -> Moment(45 - 27 * (seconds - 33) / 8, 0.0, 0.0, -150.0, -70.0, -18.0, 0.0)
            seconds < 46 -> Moment(18 - 18 * (seconds - 41) / 5, 0.0, 0.0, -80.0, -10.0, -2.0, 0.0)
            else -> Moment(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        }

        private fun responseHeader(header: String) = when (header) {
            "7DF" -> "7E8"
            "7D2" -> "7DA"
            "747" -> "74F"
            "700" -> "708"
            else -> null
        }

        private fun signed(value: Double) = value.roundToInt() + 32768
        private fun twosComplement(value: Double) = value.roundToInt() and 0xFFFF
        private fun word(value: Int) = listOf((value shr 8) and 0xFF, value and 0xFF)
        private fun did(did: Int, data: List<Int>) = listOf(0x62, did shr 8, did and 0xFF) + data

        /** ISO-TP frames as the adapter shows them with headers on: single frame, or first + consecutive frames. */
        fun frames(header: String, payload: List<Int>): String {
            fun hex(bytes: List<Int>) = bytes.joinToString(" ") { "%02X".format(it) }
            if (payload.size <= 7) return "$header ${hex(listOf(payload.size) + payload)} \r\r"
            val lines = mutableListOf("$header ${hex(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))}")
            payload.drop(6).chunked(7).forEachIndexed { index, chunk -> lines += "$header ${hex(listOf(0x20 or ((index + 1) and 0x0F)) + chunk)}" }
            return lines.joinToString(" \r", postfix = " \r\r")
        }
    }
}
