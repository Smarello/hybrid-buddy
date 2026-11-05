package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.interpretation.LinkLevel
import it.techreloaded.hybridbuddy.interpretation.STALE_AFTER_MS
import it.techreloaded.hybridbuddy.interpretation.SignalPresentation
import it.techreloaded.hybridbuddy.interpretation.connectionChain
import it.techreloaded.hybridbuddy.interpretation.presentSignal
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.session.SignalReading
import it.techreloaded.hybridbuddy.signals.BaseSignals
import kotlin.test.Test
import kotlin.test.assertEquals

class PresentationTest {
    private val now = 100_000L
    private val rpm = BaseSignals.engineSpeed

    private fun stateWith(reading: SignalReading?) =
        SessionState(live, phoneLink = PhoneLinkState.CONNECTED, readings = listOfNotNull(reading).associateBy { rpm.id })

    @Test
    fun `fresh value is shown with unit and age`() {
        val shown = presentSignal(rpm, stateWith(SignalReading(now - 300, 1240.0, "7E8", null)), now)
        assertEquals(SignalPresentation.Available("1.240 rpm", 300), shown)
    }

    @Test
    fun `zero is a value, missing is not available`() {
        assertEquals(SignalPresentation.Available("0 rpm", 0), presentSignal(rpm, stateWith(SignalReading(now, 0.0, "7E8", null)), now))
        assertEquals(SignalPresentation.Unavailable("in attesa"), presentSignal(rpm, stateWith(null), now))
        assertEquals(SignalPresentation.Unavailable("timeout"), presentSignal(rpm, stateWith(SignalReading(now, null, null, "TIMEOUT")), now))
    }

    @Test
    fun `stale value is never shown as current`() {
        val old = SignalReading(now - STALE_AFTER_MS - 1, 1240.0, "7E8", null)
        assertEquals(SignalPresentation.Unavailable("dato scaduto"), presentSignal(rpm, stateWith(old), now))
    }

    @Test
    fun `chain shows car not responding when the adapter answers but OBD requests fail`() {
        val state = SessionState(
            live,
            phoneLink = PhoneLinkState.CONNECTED,
            recentExchanges = listOf(Exchange(now - 100, "0100", 4000, "UNABLE TO CONNECT", "UNABLE TO CONNECT")),
        )
        val chain = connectionChain(state, BaseSignals.all, now)
        assertEquals(LinkLevel.OK, chain.phoneToAdapter.level)
        assertEquals(LinkLevel.ERROR, chain.adapterToCar.level)
        assertEquals(LinkLevel.ERROR, chain.signals.level)
    }

    @Test
    fun `chain is green end to end with fresh signals`() {
        val readings = BaseSignals.all.associate { it.id to SignalReading(now - 100, 1.0, "7E8", null) }
        val exchanges = List(10) { Exchange(now - 900 + it * 100L, "010C", 40, "7E8 04 41 0C 13 60", null) }
        val state = SessionState(live, phoneLink = PhoneLinkState.CONNECTED, readings = readings, recentExchanges = exchanges)
        val chain = connectionChain(state, BaseSignals.all, now)
        assertEquals(LinkLevel.OK, chain.adapterToCar.level)
        assertEquals("5/5 · 2,0 risposte/s", chain.signals.label)
    }

    @Test
    fun `while reconnecting car and signals are not claimed`() {
        val state = SessionState(live, phoneLink = PhoneLinkState.RECONNECTING)
        val chain = connectionChain(state, BaseSignals.all, now)
        assertEquals(LinkLevel.WARNING, chain.phoneToAdapter.level)
        assertEquals(LinkLevel.IDLE, chain.adapterToCar.level)
    }
}
