package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElmConnectionTest {
    @Test
    fun `response split across several reads is reassembled up to the prompt`() = runBlocking {
        val link = InMemoryAdapterLink { AdapterReply.Text(listOf("7E8 04 4", "1 0C 13", " 60 \r", "\r>")) }
        ElmConnection(link).use { connection ->
            val exchange = connection.execute("010C", 500)
            assertNull(exchange.error)
            assertEquals(listOf("7E8 04 41 0C 13 60"), exchange.lines)
        }
    }

    @Test
    fun `silent adapter gives TIMEOUT and late answers are not attributed to the next command`() = runBlocking {
        var first = true
        val link = InMemoryAdapterLink { command ->
            if (first) {
                first = false
                AdapterReply.Silence
            } else {
                AdapterReply.Text(listOf("$command-answer\r>"))
            }
        }
        ElmConnection(link).use { connection ->
            val timedOut = connection.execute("010C", 100)
            assertEquals(ElmConnection.TIMEOUT, timedOut.error)
            val next = connection.execute("010D", 500)
            assertEquals(listOf("010D-answer"), next.lines)
        }
    }

    @Test
    fun `adapter error text becomes the exchange error`() = runBlocking {
        ElmConnection(InMemoryAdapterLink { AdapterReply.Text(listOf("NO DATA\r\r>")) }).use { connection ->
            assertEquals("NO DATA", connection.execute("0104", 500).error)
        }
    }

    @Test
    fun `link closing mid-response gives DISCONNECTED and keeps the partial text`() = runBlocking {
        lateinit var link: InMemoryAdapterLink
        link = InMemoryAdapterLink {
            Thread { Thread.sleep(50); link.close() }.start()
            AdapterReply.Text(listOf("7E8 04 41"))
        }
        ElmConnection(link).use { connection ->
            val exchange = connection.execute("010C", 2_000)
            assertEquals(ElmConnection.DISCONNECTED, exchange.error)
            assertEquals("7E8 04 41", exchange.raw)
        }
    }
}
