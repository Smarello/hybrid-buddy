package it.techreloaded.hybridbuddy.obdlink

/** A diagnostic message from one control unit, reassembled from its CAN frames. */
class CanMessage(val ecu: String, val payload: List<Int>)

object IsoTp {
    /**
     * Reassembles ISO 15765-2 messages from CAN 11-bit frames shown with headers on (ATH1), e.g.
     * `7EA 03 7F 21 12` (single frame) or `7EA 10 0B 61 47 ...` + `7EA 21 ...` (first + consecutive frames).
     * Frames of different ECUs may interleave; an incomplete message is dropped.
     */
    // ponytail: consecutive-frame sequence numbers are not checked; ELM327 already discards out-of-order frames.
    fun messages(lines: List<String>): List<CanMessage> {
        val messages = mutableListOf<CanMessage>()
        val pending = mutableMapOf<String, Pair<Int, MutableList<Int>>>()
        for (line in lines) {
            val tokens = line.split(' ').filter { it.isNotEmpty() }
            if (tokens.size < 2 || !Regex("[0-9A-F]{3}").matches(tokens[0])) continue
            val ecu = tokens[0]
            val bytes = tokens.drop(1).map { it.toIntOrNull(16) ?: -1 }
            if (bytes.any { it !in 0..0xFF }) continue
            when (bytes[0] shr 4) {
                0 -> {
                    val length = bytes[0] and 0x0F
                    if (length in 1..bytes.size - 1) messages += CanMessage(ecu, bytes.subList(1, 1 + length))
                }
                1 -> if (bytes.size >= 2) {
                    pending[ecu] = (((bytes[0] and 0x0F) shl 8) or bytes[1]) to bytes.drop(2).toMutableList()
                }
                2 -> pending[ecu]?.let { (length, received) ->
                    received += bytes.drop(1)
                    if (received.size >= length) {
                        messages += CanMessage(ecu, received.take(length))
                        pending.remove(ecu)
                    }
                }
            }
        }
        return messages
    }
}
