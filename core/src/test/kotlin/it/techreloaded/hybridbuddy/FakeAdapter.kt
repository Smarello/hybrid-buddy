package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterReply

/** Answers like an OBDLink LX on a CAN 11/500 car with headers on; [override] can replace any answer. */
fun fakeCarReply(override: (String) -> AdapterReply? = { null }): (String) -> AdapterReply = { command ->
    override(command) ?: AdapterReply.Text(
        listOf(
            when (command) {
                "ATZ" -> "\r\rELM327 v1.4b\r\r>"
                "ATI" -> "ELM327 v1.4b\r\r>"
                "STI" -> "STN1155 v5.10.3\r\r>"
                "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)\r\r>"
                "ATRV" -> "14.1V\r\r>"
                "0100" -> "SEARCHING...\r7E8 06 41 00 BE 3F A8 13 \r\r>"
                "010C" -> "7E8 04 41 0C 13 60 \r\r>"
                "010D" -> "7E8 03 41 0D 00 \r\r>"
                "0105" -> "7E8 03 41 05 76 \r\r>"
                "0104" -> "7E8 03 41 04 80 \r\r>"
                else -> "OK\r\r>"
            },
        ),
    )
}
