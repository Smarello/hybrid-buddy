package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.IsoTp
import it.techreloaded.hybridbuddy.session.Privacy
import kotlin.test.Test
import kotlin.test.assertEquals

class IsoTpTest {
    private fun hex(text: String) = text.split(' ').map { it.toInt(16) }

    @Test
    fun `single frames, negative answers and noise lines`() {
        val messages = IsoTp.messages(listOf("7EA 03 7F 21 12", "NO DATA", "CAN ERROR", "7B8 05 62 10 43 00 C8 00 00"))
        assertEquals(listOf("7EA" to hex("7F 21 12"), "7B8" to hex("62 10 43 00 C8")), messages.map { it.ecu to it.payload })
    }

    @Test
    fun `multi-frame answers are reassembled per ECU even when interleaved`() {
        val messages = IsoTp.messages(
            listOf(
                "7EA 10 0B 61 47 01 02 03 04",
                "7EB 03 7F 21 31",
                "7EA 21 05 06 07 08 09 AA AA",
                "7E8 10 20 61 01 00 00 00 00", // never completed: dropped
            ),
        )
        assertEquals(
            listOf("7EB" to hex("7F 21 31"), "7EA" to hex("61 47 01 02 03 04 05 06 07 08 09")),
            messages.map { it.ecu to it.payload },
        )
    }

    @Test
    fun `answers holding a VIN-shaped text are masked, ordinary data is kept`() {
        // "JTDKBRFU9J3012345" in ASCII, as a multi-frame answer.
        val vin = listOf(
            "7EA 10 14 61 99 4A 54 44 4B",
            "7EA 21 42 52 46 55 39 4A 33",
            "7EA 22 30 31 32 33 34 35 00",
        )
        assertEquals(Privacy.MASKED, Privacy.maskVinLike("raw", vin))
        val data = listOf("7EA 10 0B 61 47 01 02 03 04", "7EA 21 05 06 07 08 09 AA AA")
        assertEquals("raw", Privacy.maskVinLike("raw", data))
    }
}
