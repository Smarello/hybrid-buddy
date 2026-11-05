package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Recordings exported from the real phone, kept as permanent test cases. */
class RealRecordingsTest {
    private fun load(name: String) =
        Recording.parse(javaClass.getResource("/recordings/$name")!!.readText().lineSequence())

    @Test
    fun `A34 2026-11-05 - every connection attempt refused for Bluetooth permission`() = runBlocking {
        val (header, lines) = load("2026-11-05-a34-permesso-bluetooth.jsonl")
        assertEquals(RecordingHeader.LIVE, header.source)
        val replay = AcquisitionSession(SessionSource.Replay("a34", simulated = false), ReplayConnector(lines), timeouts = fastTimeouts)
        val job = launch(Dispatchers.Default) { replay.run() }
        val states = withTimeout(10_000) { replay.state.takeWhile { it.phoneLink != PhoneLinkState.FINISHED }.toList() }
        job.join()

        // The failure is shown as a reconnection with its reason, never as data.
        assertTrue(states.any { it.phoneLink == PhoneLinkState.RECONNECTING && it.phoneLinkDetail == "permesso Bluetooth mancante" })
        assertTrue(replay.state.value.readings.isEmpty())
        assertTrue(replay.state.value.recentExchanges.isEmpty())
    }
}
