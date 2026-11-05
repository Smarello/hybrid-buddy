package it.techreloaded.hybridbuddy

import it.techreloaded.hybridbuddy.obdlink.AdapterResponse
import it.techreloaded.hybridbuddy.signals.BaseSignals
import it.techreloaded.hybridbuddy.signals.Decoded
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SignalsTest {
    private fun decode(signal: it.techreloaded.hybridbuddy.signals.SignalDefinition, raw: String) =
        signal.decode(AdapterResponse.lines(raw, signal.command))

    @Test
    fun `standard PID conversions`() {
        assertEquals(Decoded.Value(1240.0, "7E8"), decode(BaseSignals.engineSpeed, "7E8 04 41 0C 13 60 \r"))
        assertEquals(Decoded.Value(87.0, "7E8"), decode(BaseSignals.vehicleSpeed, "7E8 03 41 0D 57"))
        assertEquals(Decoded.Value(78.0, "7E8"), decode(BaseSignals.coolantTemperature, "7E8 03 41 05 76"))
        assertEquals(Decoded.Value(100.0, "7E8"), decode(BaseSignals.calculatedLoad, "7E8 03 41 04 FF"))
        assertEquals(Decoded.Value(14.1, null), decode(BaseSignals.adapterVoltage, "14.1V"))
    }

    @Test
    fun `padding bytes after the PCI length are ignored and headers off is accepted`() {
        assertEquals(Decoded.Value(1240.0, "7E8"), decode(BaseSignals.engineSpeed, "7E8 04 41 0C 13 60 AA AA AA"))
        assertEquals(Decoded.Value(1240.0, null), decode(BaseSignals.engineSpeed, "41 0C 13 60"))
    }

    @Test
    fun `engine ECU is preferred when several ECUs answer`() {
        val raw = "7E2 04 41 0C 00 00\r7E8 04 41 0C 13 60\r"
        assertEquals(Decoded.Value(1240.0, "7E8"), decode(BaseSignals.engineSpeed, raw))
    }

    @Test
    fun `wrong PID, short or garbled answers are failures`() {
        assertIs<Decoded.Failure>(decode(BaseSignals.engineSpeed, "7E8 03 41 0D 00"))
        assertIs<Decoded.Failure>(decode(BaseSignals.engineSpeed, "7E8 03 41 0C 13"))
        assertIs<Decoded.Failure>(decode(BaseSignals.engineSpeed, "7E8 04 41 0C ZZ 60"))
        assertIs<Decoded.Failure>(decode(BaseSignals.adapterVoltage, "OK"))
    }

    @Test
    fun `adapter errors are recognised only when every line is an error`() {
        assertEquals("NO DATA", AdapterResponse.errorOf(AdapterResponse.lines("NO DATA\r\r", "010C")))
        assertEquals("UNABLE TO CONNECT", AdapterResponse.errorOf(AdapterResponse.lines("SEARCHING...\rUNABLE TO CONNECT\r", "0100")))
        assertEquals("?", AdapterResponse.errorOf(AdapterResponse.lines("?\r", "STI")))
        assertEquals(null, AdapterResponse.errorOf(AdapterResponse.lines("010C\r7E8 04 41 0C 13 60\r", "010C")))
    }
}
