package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import it.techreloaded.hybridbuddy.obdlink.ReadOnlyCommands
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadOnlyCommandsTest {
    @Test
    fun `allows the adapter setup and the read services 01, 21, 22`() {
        listOf(
            "ATZ", "ATE0", "ATL0", "ATH1", "ATSP0", "ATI", "STI", "ATDP", "ATRV", "0100", "010C",
            "ATSH 7E2", "ATCRA 7EA", "ATFCSH 7B0", "ATFCSD 30 00 00", "ATFCSM 1", "21C3", "2100", "221043", "221FFF",
        ).forEach { assertTrue(ReadOnlyCommands.isAllowed(it), it) }
    }

    @Test
    fun `refuses writes, resets, DTC clear, sessions, security access, VIN and arbitrary frames`() {
        listOf(
            "04", "0902", "1101", "1003", "2701", "2E1001AA", "3101FF00", "3E00", "14FFFFFF", "22F190", "22F18C",
            "2210", "21C3 01", "STPX H:7E2, D:1101", "ATMA", "ATWM 81 10 F1 3E", "ATSH 7E2 ; 04",
        ).forEach { assertFalse(ReadOnlyCommands.isAllowed(it), it) }
    }

    @Test
    fun `a refused command never reaches the adapter`() = runBlocking {
        val written = mutableListOf<String>()
        ElmConnection(InMemoryAdapterLink { written += it; AdapterReply.Text(listOf("OK\r\r>")) }).use { connection ->
            assertFailsWith<IllegalArgumentException> { connection.execute("1101", 500) }
            assertFailsWith<IllegalArgumentException> { connection.execute("04", 500) }
        }
        assertTrue(written.isEmpty())
    }
}
