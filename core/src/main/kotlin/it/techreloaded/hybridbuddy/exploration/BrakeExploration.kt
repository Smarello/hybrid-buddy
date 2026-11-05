package it.techreloaded.hybridbuddy.exploration

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.obdlink.IsoTp
import it.techreloaded.hybridbuddy.session.RecordedEvent
import it.techreloaded.hybridbuddy.session.SessionRecorder
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.session.Timeouts
import it.techreloaded.hybridbuddy.session.initializeAdapter
import it.techreloaded.hybridbuddy.obdlink.AdapterResponse
import it.techreloaded.hybridbuddy.session.RecordedExchange
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.RecordingHeader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException

/** A control unit addressed physically: requests go to [requestHeader], answers come from [responseHeader]. */
data class ExplorationTarget(val requestHeader: String, val responseHeader: String, val label: String)

/** One read request to one control unit, e.g. 7B0 / `221043`. */
data class ProbedIdentifier(val target: ExplorationTarget, val request: String)

enum class PedalPhase(val label: String, val instruction: String, val pressed: Boolean) {
    RELEASED("Rilasciato", "Piede lontano dal pedale", pressed = false),
    LIGHT("Leggero", "Premi leggermente", pressed = true),
    FULL("A fondo", "Premi a fondo", pressed = true),
    RELEASED_AGAIN("Rilasciato", "Rilascia il pedale", pressed = false),
}

enum class ExplorationStep { CONNECTING, MAPPING, AWAITING_PEDAL, PEDAL, DONE, STOPPED, FAILED }

/** A completed mapping taken from an earlier exploration recording, so the pedal can be repeated without it. */
data class ReusedMapping(val fileName: String, val mappedAtEpochMs: Long, val identifiers: List<ProbedIdentifier>)

enum class TargetStatus { PENDING, MAPPING, SILENT, DONE }

data class TargetProgress(
    val target: ExplorationTarget,
    val status: TargetStatus = TargetStatus.PENDING,
    val mode21Responding: Int = 0,
    val mode22Responding: Int = 0,
) {
    val responding: Int get() = mode21Responding + mode22Responding
}

data class ExplorationState(
    val targets: List<TargetProgress>,
    val mappingTotal: Int,
    val step: ExplorationStep = ExplorationStep.CONNECTING,
    /** Why the exploration failed, null otherwise. */
    val failure: String? = null,
    val adapterIdentity: String? = null,
    val protocol: String? = null,
    val mappingSent: Int = 0,
    val mappingStartedAtMs: Long? = null,
    val mappingEndedAtMs: Long? = null,
    /** Current pedal phase; after a stop or failure, the phase that was interrupted. */
    val pedalPhase: PedalPhase? = null,
    val pedalPhaseEndsAtMs: Long? = null,
    /** True when no identifier answered, so there was nothing to read with the pedal. */
    val pedalSkipped: Boolean = false,
    val changedWithPedal: Int? = null,
    /** Set when the mapping was not run but taken from an earlier recording. */
    val reusedMapping: ReusedMapping? = null,
    /** Newest last, capped like the acquisition log. */
    val recentExchanges: List<Exchange> = emptyList(),
)

/** Requests and control units probed by the brake exploration; sources and rationale in SIGNALS.md / DECISIONS.md. */
object BrakeExplorationPlan {
    val targets = listOf(ExplorationTarget("7E2", "7EA", "ibrida"), ExplorationTarget("7B0", "7B8", "freni"))
    val requests: List<String> = (0x00..0xFF).map { "21%02X".format(it) } + (0x1000..0x1FFF).map { "22%04X".format(it) }

    /** A control unit giving no answer at all (positive or negative) to this many requests is skipped. */
    const val SILENCE_PROBE = 16
    const val PEDAL_PHASE_MS = 15_000L
}

/**
 * Guided, read-only exploration of the brake signals with the car stationary in READY:
 * 1. map which mode 21/22 requests each target control unit answers (or reuse [reusedMapping]);
 * 2. wait for the owner's [confirmPedal], then read only those identifiers in a loop while the owner
 *    follows four timed pedal phases (each phase start is recorded);
 * 3. count the identifiers whose answer changes with the pedal pressed.
 * Every exchange is recorded; the exported file is the evidence analysed offline.
 */
class BrakeExploration(
    private val connector: AdapterConnector,
    private val recorder: SessionRecorder? = null,
    private val targets: List<ExplorationTarget> = BrakeExplorationPlan.targets,
    private val requests: List<String> = BrakeExplorationPlan.requests,
    private val pedalPhaseMs: Long = BrakeExplorationPlan.PEDAL_PHASE_MS,
    private val reusedMapping: ReusedMapping? = null,
    private val timeouts: Timeouts = Timeouts(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(
        ExplorationState(targets = targets.map { TargetProgress(it) }, mappingTotal = targets.size * requests.size),
    )
    val state: StateFlow<ExplorationState> = mutableState

    private val responding = mutableListOf<ProbedIdentifier>()
    private val pedalAnswers = mutableMapOf<ProbedIdentifier, MutableMap<PedalPhase, MutableList<List<Int>>>>()
    private var consecutiveTimeouts = 0
    private val pedalConfirmed = CompletableDeferred<Unit>()

    /** The owner is ready: the pedal phases may start. No phase ever starts without it. */
    fun confirmPedal() {
        pedalConfirmed.complete(Unit)
    }

    private class ConnectionLost(reason: String) : Exception(reason)

    /** Runs to the end, or until cancelled (state STOPPED). Never reconnects: a lost link ends it as FAILED. */
    suspend fun run() {
        try {
            val link = try {
                connector.connect()
            } catch (e: IOException) {
                val reason = e.message ?: "connessione non riuscita"
                recorder?.event(clock(), RecordedEvent.CONNECT_FAILED, reason)
                mutableState.update { it.copy(step = ExplorationStep.FAILED, failure = reason) }
                return
            }
            recorder?.event(clock(), RecordedEvent.CONNECTED)
            ElmConnection(link, io, clock).use { connection ->
                try {
                    val (identity, protocol) = initializeAdapter(timeouts) { command, timeout -> execute(connection, command, timeout) }
                    mutableState.update { it.copy(adapterIdentity = identity, protocol = protocol) }
                    if (reusedMapping == null) map(connection) else reuse(reusedMapping)
                    pedal(connection)
                    // Back to the standard setup (functional addressing, automatic flow control).
                    execute(connection, "ATZ", timeouts.resetMs)
                    val changed = PedalAnalysis.changedWithPedal(pedalAnswers).size
                    recorder?.event(clock(), RecordedEvent.EXPLORATION, "fine: $changed identificativi cambiano col pedale")
                    mutableState.update { it.copy(step = ExplorationStep.DONE, pedalPhase = null, changedWithPedal = changed) }
                } catch (e: ConnectionLost) {
                    recorder?.event(clock(), RecordedEvent.DISCONNECTED, e.message)
                    mutableState.update { it.copy(step = ExplorationStep.FAILED, failure = e.message) }
                }
            }
        } finally {
            if (state.value.step !in setOf(ExplorationStep.DONE, ExplorationStep.FAILED)) {
                mutableState.update { it.copy(step = ExplorationStep.STOPPED) }
            }
        }
    }

    private suspend fun map(connection: ElmConnection) {
        mutableState.update { it.copy(step = ExplorationStep.MAPPING, mappingStartedAtMs = clock()) }
        for ((index, target) in targets.withIndex()) {
            updateTarget(index) { it.copy(status = TargetStatus.MAPPING) }
            select(connection, target)
            var heardAnything = false
            for ((sent, request) in requests.withIndex()) {
                if (sent == BrakeExplorationPlan.SILENCE_PROBE && !heardAnything) {
                    recorder?.event(clock(), RecordedEvent.EXPLORATION, "${target.requestHeader} non risponde")
                    updateTarget(index) { it.copy(status = TargetStatus.SILENT) }
                    mutableState.update { it.copy(mappingTotal = it.mappingTotal - (requests.size - sent)) }
                    break
                }
                val answer = ask(connection, target, request)
                if (answer != Answer.Silent) heardAnything = true
                if (answer is Answer.Positive) {
                    responding += ProbedIdentifier(target, request)
                    updateTarget(index) {
                        if (request.startsWith("21")) it.copy(mode21Responding = it.mode21Responding + 1)
                        else it.copy(mode22Responding = it.mode22Responding + 1)
                    }
                }
                mutableState.update { it.copy(mappingSent = it.mappingSent + 1) }
            }
            updateTarget(index) { if (it.status == TargetStatus.MAPPING) it.copy(status = TargetStatus.DONE) else it }
        }
        recorder?.event(clock(), RecordedEvent.MAPPING_COMPLETED)
        mutableState.update { it.copy(mappingEndedAtMs = clock()) }
    }

    private fun reuse(mapping: ReusedMapping) {
        recorder?.event(clock(), RecordedEvent.MAPPING_REUSED, mapping.fileName)
        responding += mapping.identifiers
        mutableState.update { state ->
            state.copy(
                reusedMapping = mapping,
                mappingTotal = 0,
                targets = state.targets.map { progress ->
                    val requests = mapping.identifiers.filter { it.target == progress.target }.map { it.request }
                    progress.copy(
                        status = TargetStatus.DONE,
                        mode21Responding = requests.count { it.startsWith("21") },
                        mode22Responding = requests.count { it.startsWith("22") },
                    )
                },
            )
        }
    }

    private suspend fun pedal(connection: ElmConnection) {
        if (responding.isEmpty()) {
            mutableState.update { it.copy(pedalSkipped = true) }
            return
        }
        mutableState.update { it.copy(step = ExplorationStep.AWAITING_PEDAL) }
        pedalConfirmed.await()
        val byTarget = responding.groupBy { it.target }
        for (phase in PedalPhase.entries) {
            val endsAt = clock() + pedalPhaseMs
            recorder?.event(clock(), RecordedEvent.PEDAL_PHASE, phase.name)
            mutableState.update { it.copy(step = ExplorationStep.PEDAL, pedalPhase = phase, pedalPhaseEndsAtMs = endsAt) }
            while (clock() < endsAt) {
                for ((target, identifiers) in byTarget) {
                    if (clock() >= endsAt) break
                    select(connection, target)
                    for (identifier in identifiers) {
                        if (clock() >= endsAt) break
                        val answer = ask(connection, target, identifier.request)
                        if (answer is Answer.Positive) {
                            pedalAnswers.getOrPut(identifier) { mutableMapOf() }.getOrPut(phase) { mutableListOf() } += answer.payload
                        }
                    }
                }
            }
        }
    }

    /** Physical addressing of [target], with flow control for its multi-frame answers (OBDLink FRPM). */
    private suspend fun select(connection: ElmConnection, target: ExplorationTarget) {
        for (command in listOf(
            "ATSH ${target.requestHeader}", "ATCRA ${target.responseHeader}",
            "ATFCSH ${target.requestHeader}", "ATFCSD 30 00 00", "ATFCSM 1",
        )) execute(connection, command, timeouts.atCommandMs)
    }

    private suspend fun ask(connection: ElmConnection, target: ExplorationTarget, request: String): Answer {
        val exchange = execute(connection, request, timeouts.obdRequestMs)
        return Answer.of(exchange.lines, exchange.error, target, request)
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

    private fun updateTarget(index: Int, change: (TargetProgress) -> TargetProgress) =
        mutableState.update { state -> state.copy(targets = state.targets.toMutableList().also { it[index] = change(it[index]) }) }
}

/** How a control unit answered one read request. */
internal sealed interface Answer {
    data class Positive(val payload: List<Int>) : Answer
    data object Negative : Answer
    data object Silent : Answer

    companion object {
        fun of(lines: List<String>, error: String?, target: ExplorationTarget, request: String): Answer {
            if (error != null) return Silent
            val last = IsoTp.messages(lines)
                .filter { it.ecu == target.responseHeader }
                // 7F xx 78 = "response pending": the real answer follows.
                .lastOrNull { !(it.payload.size >= 3 && it.payload[0] == 0x7F && it.payload[2] == 0x78) }
                ?: return Silent
            val service = request.substring(0, 2).toInt(16)
            val identifier = request.substring(2).chunked(2).map { it.toInt(16) }
            return when {
                last.payload.firstOrNull() == service + 0x40 && last.payload.drop(1).take(identifier.size) == identifier ->
                    Positive(last.payload)
                last.payload.firstOrNull() == 0x7F -> Negative
                else -> Silent
            }
        }
    }
}

/** Reads the mapping back from an exploration recording, so the pedal can be repeated without mapping again. */
object MappingRecording {
    /**
     * The identifiers that answered positively during the mapping of [lines] (a JSONL exploration recording),
     * or null when the mapping did not complete, was itself reused, or found nothing to read.
     * Complete = `mapping_completed` event, or (recordings from 0.2.0) the start of a pedal phase.
     * Invalid lines (e.g. cut by an abrupt stop) are skipped.
     */
    fun read(
        fileName: String,
        lines: Sequence<String>,
        targets: List<ExplorationTarget> = BrakeExplorationPlan.targets,
    ): ReusedMapping? {
        val parsed = lines.filter { it.isNotBlank() }.mapNotNull(Recording::decodeOrNull).iterator()
        val header = (if (parsed.hasNext()) parsed.next() else null) as? RecordingHeader ?: return null
        var target: ExplorationTarget? = null
        val identifiers = linkedSetOf<ProbedIdentifier>()
        for (line in parsed) {
            if (line is RecordedEvent) when (line.event) {
                RecordedEvent.MAPPING_REUSED -> return null
                RecordedEvent.MAPPING_COMPLETED, RecordedEvent.PEDAL_PHASE ->
                    return identifiers.takeIf { it.isNotEmpty() }?.let { ReusedMapping(fileName, header.startedAtEpochMs, it.toList()) }
            }
            if (line !is RecordedExchange) continue
            if (line.cmd.startsWith("ATSH ")) target = targets.firstOrNull { it.requestHeader == line.cmd.removePrefix("ATSH ") }
            val current = target ?: continue
            if (!(line.cmd.startsWith("21") || line.cmd.startsWith("22"))) continue
            if (Answer.of(AdapterResponse.lines(line.raw, line.cmd), line.error, current, line.cmd) is Answer.Positive) {
                identifiers += ProbedIdentifier(current, line.cmd)
            }
        }
        return null
    }
}

object PedalAnalysis {
    /**
     * Identifiers with at least one data byte that is steady (at most two values) with the pedal released
     * and never takes one of those values with the pedal pressed fully.
     */
    // ponytail: byte-level triage heuristic for the on-screen summary; the exported recording is the evidence.
    fun changedWithPedal(answers: Map<ProbedIdentifier, Map<PedalPhase, List<List<Int>>>>): Set<ProbedIdentifier> =
        answers.filterValues { phases ->
            val released = phases[PedalPhase.RELEASED].orEmpty() + phases[PedalPhase.RELEASED_AGAIN].orEmpty()
            val full = phases[PedalPhase.FULL].orEmpty()
            if (released.isEmpty() || full.isEmpty()) return@filterValues false
            val width = (released + full).minOf { it.size }
            (0 until width).any { index ->
                val releasedValues = released.map { it[index] }.toSet()
                releasedValues.size <= 2 && full.none { it[index] in releasedValues }
            }
        }.keys
}
