package it.techreloaded.hybridbuddy.session

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.EndOfRecording
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.signals.BaseSignals
import it.techreloaded.hybridbuddy.signals.Decoded
import it.techreloaded.hybridbuddy.signals.SignalDefinition
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import java.io.IOException

sealed interface SessionSource {
    data class Live(val adapterName: String) : SessionSource
    data class Replay(val fileName: String, val simulated: Boolean) : SessionSource
}

enum class PhoneLinkState { CONNECTING, CONNECTED, RECONNECTING, FINISHED }

/** Latest attempt for a signal: either a value or the reason it is missing. */
data class SignalReading(val atEpochMs: Long, val value: Double?, val ecu: String?, val failure: String?)

data class SessionState(
    val source: SessionSource,
    val phoneLink: PhoneLinkState = PhoneLinkState.CONNECTING,
    val phoneLinkDetail: String? = null,
    val adapterIdentity: String? = null,
    val protocol: String? = null,
    val readings: Map<String, SignalReading> = emptyMap(),
    /** Newest last, capped at [RECENT_EXCHANGES]. */
    val recentExchanges: List<Exchange> = emptyList(),
) {
    companion object {
        const val RECENT_EXCHANGES = 200
    }
}

data class Timeouts(
    val resetMs: Long = 5_000,
    val atCommandMs: Long = 2_000,
    /** First OBD request: with ATSP0 the adapter searches the protocol, which takes seconds. */
    val firstObdRequestMs: Long = 10_000,
    val obdRequestMs: Long = 3_000,
    val reconnectBaseDelayMs: Long = 1_000,
    val reconnectMaxDelayMs: Long = 10_000,
    /** A silent adapter whose socket stays open is treated as lost after this many timeouts in a row. */
    val maxConsecutiveTimeouts: Int = 3,
)

/**
 * Acquisition loop: connect, initialize the adapter, poll [signals] in turn, record every
 * exchange, and reconnect with backoff whenever the link is lost. Runs until cancelled or,
 * for a replay, until the recording ends. Read-only: only AT/ST setup and mode 01 requests are sent.
 */
class AcquisitionSession(
    source: SessionSource,
    private val connector: AdapterConnector,
    val signals: List<SignalDefinition> = BaseSignals.all,
    private val recorder: SessionRecorder? = null,
    private val timeouts: Timeouts = Timeouts(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(SessionState(source))
    val state: StateFlow<SessionState> = mutableState

    private var consecutiveTimeouts = 0

    private class ConnectionLost(reason: String) : Exception(reason)

    suspend fun run() {
        var consecutiveFailures = 0
        var finishReason = "fermato"
        try {
            while (currentCoroutineContext().isActive) {
                val link = try {
                    connector.connect()
                } catch (e: EndOfRecording) {
                    finishReason = "fine registrazione"
                    return
                } catch (e: IOException) {
                    val reason = e.message ?: "connessione non riuscita"
                    recorder?.event(clock(), RecordedEvent.CONNECT_FAILED, reason)
                    mutableState.update { it.copy(phoneLink = PhoneLinkState.RECONNECTING, phoneLinkDetail = reason) }
                    delay(backoff(consecutiveFailures++))
                    continue
                }
                consecutiveFailures = 0
                recorder?.event(clock(), RecordedEvent.CONNECTED)
                mutableState.update { it.copy(phoneLink = PhoneLinkState.CONNECTED, phoneLinkDetail = null) }
                val reason = ElmConnection(link, io, clock).use { connection ->
                    try {
                        initialize(connection)
                        poll(connection)
                        "fermato"
                    } catch (e: ConnectionLost) {
                        e.message!!
                    }
                }
                recorder?.event(clock(), RecordedEvent.DISCONNECTED, reason)
                mutableState.update { it.copy(phoneLink = PhoneLinkState.RECONNECTING, phoneLinkDetail = reason) }
                delay(backoff(0))
            }
        } finally {
            mutableState.update { it.copy(phoneLink = PhoneLinkState.FINISHED, phoneLinkDetail = finishReason) }
        }
    }

    private suspend fun initialize(connection: ElmConnection) {
        consecutiveTimeouts = 0
        execute(connection, "ATZ", timeouts.resetMs)
        for (command in listOf("ATE0", "ATL0", "ATH1", "ATSP0")) execute(connection, command, timeouts.atCommandMs)
        val identity = listOf("ATI", "STI").mapNotNull { command ->
            execute(connection, command, timeouts.atCommandMs).takeIf { it.error == null }?.lines?.firstOrNull()
        }
        mutableState.update { it.copy(adapterIdentity = identity.joinToString(" · ").ifEmpty { null }) }
        execute(connection, "0100", timeouts.firstObdRequestMs)
        val protocol = execute(connection, "ATDP", timeouts.atCommandMs)
        mutableState.update { it.copy(protocol = protocol.lines.firstOrNull().takeIf { protocol.error == null }) }
    }

    private suspend fun poll(connection: ElmConnection) {
        while (currentCoroutineContext().isActive) {
            for (signal in signals) {
                val timeout = if (signal.command.startsWith("AT")) timeouts.atCommandMs else timeouts.obdRequestMs
                val exchange = execute(connection, signal.command, timeout)
                val reading = when (val error = exchange.error) {
                    null -> when (val decoded = signal.decode(exchange.lines)) {
                        is Decoded.Value -> SignalReading(exchange.startedAtEpochMs, decoded.value, decoded.ecu, null)
                        is Decoded.Failure -> SignalReading(exchange.startedAtEpochMs, null, null, decoded.reason)
                    }
                    else -> SignalReading(exchange.startedAtEpochMs, null, null, error)
                }
                mutableState.update { it.copy(readings = it.readings + (signal.id to reading)) }
            }
        }
    }

    private suspend fun execute(connection: ElmConnection, command: String, timeoutMs: Long): Exchange {
        val exchange = connection.execute(command, timeoutMs)
        recorder?.exchange(exchange)
        mutableState.update { it.copy(recentExchanges = (it.recentExchanges + exchange).takeLast(SessionState.RECENT_EXCHANGES)) }
        when (exchange.error) {
            ElmConnection.DISCONNECTED -> throw ConnectionLost("connessione persa")
            ElmConnection.TIMEOUT -> if (++consecutiveTimeouts >= timeouts.maxConsecutiveTimeouts) {
                throw ConnectionLost("l'adattatore non risponde")
            }
            else -> consecutiveTimeouts = 0
        }
        return exchange
    }

    private fun backoff(failures: Int): Long =
        (timeouts.reconnectBaseDelayMs shl failures.coerceAtMost(4)).coerceAtMost(timeouts.reconnectMaxDelayMs)
}
