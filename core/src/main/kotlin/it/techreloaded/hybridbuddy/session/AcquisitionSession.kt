package it.techreloaded.hybridbuddy.session

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.EndOfRecording
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.signals.BaseSignals
import it.techreloaded.hybridbuddy.signals.Decoded
import it.techreloaded.hybridbuddy.signals.EcuTarget
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
 * Acquisition loop: connect, initialize the adapter, poll the rounds of [schedule] in turn, record every
 * exchange, and reconnect with backoff whenever the link is lost. Runs until cancelled or,
 * for a replay, until the recording ends. Read-only: only AT/ST setup and read requests are sent (enforced by ReadOnlyCommands).
 * Signals with a target control unit are requested with physical addressing; one failing is retried less and less
 * often (1, 2, 4, 8, 16 skipped turns) so a refusing or absent unit does not slow the others down.
 * A signal with a fallback whose read fails is replaced by its fallback signals until the next connection.
 * A schedule mixes targeted and untargeted (broadcast) signals only if every untargeted one comes before the first targeted.
 */
class AcquisitionSession(
    source: SessionSource,
    private val connector: AdapterConnector,
    val schedule: List<List<SignalDefinition>> = listOf(BaseSignals.all),
    private val recorder: SessionRecorder? = null,
    private val timeouts: Timeouts = Timeouts(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(SessionState(source))
    val state: StateFlow<SessionState> = mutableState

    /** Signals that carry readings: one with a fallback stores its values under the fallback ids. */
    val signals: List<SignalDefinition> = schedule.flatten().flatMap { it.fallback.ifEmpty { listOf(it) } }.distinct()

    private var consecutiveTimeouts = 0
    private var selectedTarget: EcuTarget? = null
    private var flowControlSet = false
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private val turnsToSkip = mutableMapOf<String, Int>()
    /** Ids of the signals replaced by their fallback on this connection. */
    private val replacedByFallback = mutableSetOf<String>()

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
        selectedTarget = null
        flowControlSet = false
        replacedByFallback.clear()
        val (identity, protocol) = initializeAdapter(timeouts) { command, timeout -> execute(connection, command, timeout) }
        mutableState.update { it.copy(adapterIdentity = identity, protocol = protocol) }
    }

    private suspend fun poll(connection: ElmConnection) {
        while (currentCoroutineContext().isActive) {
            for (round in schedule) for (scheduled in round) for (signal in if (scheduled.id in replacedByFallback) scheduled.fallback else listOf(scheduled)) {
                val target = signal.target
                if (target != null && skipTurn(signal.id)) continue
                if (target != null && target != selectedTarget) select(connection, target)
                val timeout = if (signal.command.startsWith("AT")) timeouts.atCommandMs else timeouts.obdRequestMs
                val exchange = execute(connection, signal.command, timeout)
                val at = exchange.startedAtEpochMs
                val readings = when (val error = exchange.error) {
                    null -> when (val decoded = signal.decode(exchange.lines)) {
                        is Decoded.Value -> mapOf(signal.id to SignalReading(at, decoded.value, decoded.ecu, null))
                        is Decoded.Values -> decoded.values.mapValues { (_, value) -> SignalReading(at, value, decoded.ecu, null) }
                        is Decoded.Failure -> mapOf(signal.id to SignalReading(at, null, null, decoded.reason))
                    }
                    else -> mapOf(signal.id to SignalReading(at, null, null, if (target != null) "non risponde ($error)" else error))
                }
                val failed = readings.values.any { it.failure != null }
                if (failed && signal.fallback.isNotEmpty()) {
                    // The exchange stays in the recording; the fallback signals are read from the next turn on.
                    replacedByFallback += signal.id
                    continue
                }
                if (target != null) recordOutcome(signal.id, !failed)
                mutableState.update { it.copy(readings = it.readings + readings) }
            }
        }
    }

    /** Physical addressing of [target], with flow control for its multi-frame answers (OBDLink FRPM, as in D-015). */
    private suspend fun select(connection: ElmConnection, target: EcuTarget) {
        val commands = mutableListOf("ATSH ${target.requestHeader}", "ATCRA ${target.responseHeader}", "ATFCSH ${target.requestHeader}")
        if (!flowControlSet) commands += listOf("ATFCSD 30 00 00", "ATFCSM 1")
        for (command in commands) execute(connection, command, timeouts.atCommandMs)
        selectedTarget = target
        flowControlSet = true
    }

    private fun skipTurn(signalId: String): Boolean {
        val skips = turnsToSkip[signalId] ?: 0
        if (skips > 0) turnsToSkip[signalId] = skips - 1
        return skips > 0
    }

    private fun recordOutcome(signalId: String, success: Boolean) {
        if (success) {
            consecutiveFailures.remove(signalId)
            return
        }
        val failures = (consecutiveFailures[signalId] ?: 0) + 1
        consecutiveFailures[signalId] = failures
        turnsToSkip[signalId] = 1 shl (failures - 1).coerceAtMost(4)
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

/**
 * Adapter setup shared by acquisition and exploration (D-004): reset, echo/linefeeds off, CAN headers on,
 * automatic protocol, identification, first OBD request (protocol search), protocol read-back.
 * @return adapter identity and protocol, null when unknown.
 */
internal suspend fun initializeAdapter(
    timeouts: Timeouts,
    execute: suspend (command: String, timeoutMs: Long) -> Exchange,
): Pair<String?, String?> {
    execute("ATZ", timeouts.resetMs)
    for (command in listOf("ATE0", "ATL0", "ATH1", "ATSP0")) execute(command, timeouts.atCommandMs)
    val identity = listOf("ATI", "STI").mapNotNull { command ->
        execute(command, timeouts.atCommandMs).takeIf { it.error == null }?.lines?.firstOrNull()
    }
    execute("0100", timeouts.firstObdRequestMs)
    val protocol = execute("ATDP", timeouts.atCommandMs)
    return identity.joinToString(" · ").ifEmpty { null } to protocol.lines.firstOrNull().takeIf { protocol.error == null }
}
