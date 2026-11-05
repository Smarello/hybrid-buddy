package it.techreloaded.hybridbuddy.obdlink

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException

/** One request/response with the adapter, as recorded and shown in the log. */
data class Exchange(
    val startedAtEpochMs: Long,
    val command: String,
    val durationMs: Long,
    /** Text received before the `>` prompt (also partial text on timeout); null if nothing arrived. */
    val raw: String?,
    /** Null on success, otherwise TIMEOUT, DISCONNECTED or the adapter's error (NO DATA, ?, CAN ERROR...). */
    val error: String?,
) {
    val lines: List<String> get() = AdapterResponse.lines(raw, command)
}

/**
 * ELM327/STN command channel: one command at a time, waits for the `>` prompt,
 * reassembles responses split across several reads, enforces a timeout per command.
 * Protocol reference: OBDLink Family Reference and Programming Manual (FRPM).
 */
class ElmConnection(
    private val link: AdapterLink,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val scope = CoroutineScope(io)
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val mutex = Mutex()

    init {
        scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (true) {
                    val count = link.read(buffer)
                    if (count < 0) break
                    incoming.send(String(buffer, 0, count, Charsets.US_ASCII))
                }
                incoming.close()
            } catch (e: IOException) {
                incoming.close(e)
            }
        }
    }

    /** @throws IllegalArgumentException for any command outside [ReadOnlyCommands], before anything is sent. */
    suspend fun execute(command: String, timeoutMs: Long): Exchange = mutex.withLock {
        ReadOnlyCommands.requireAllowed(command)
        // Leftovers of a previous timed-out command must not be attributed to this one.
        while (incoming.tryReceive().isSuccess) { /* dropped */ }
        val startedAt = clock()
        val startNanos = System.nanoTime()
        val received = StringBuilder()
        val error = try {
            withContext(io) { link.write("$command\r") }
            withTimeout(timeoutMs) {
                while (PROMPT !in received) received.append(incoming.receive())
            }
            null
        } catch (e: TimeoutCancellationException) {
            TIMEOUT
        } catch (e: ClosedReceiveChannelException) {
            DISCONNECTED
        } catch (e: IOException) {
            DISCONNECTED
        }
        val raw = received.toString().substringBefore(PROMPT).ifEmpty { null }
        Exchange(
            startedAtEpochMs = startedAt,
            command = command,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
            raw = raw,
            error = error ?: AdapterResponse.errorOf(AdapterResponse.lines(raw, command)),
        )
    }

    override fun close() {
        link.close()
        scope.cancel()
    }

    companion object {
        const val PROMPT = '>'
        const val TIMEOUT = "TIMEOUT"
        const val DISCONNECTED = "DISCONNECTED"
    }
}

object AdapterResponse {
    private val ERRORS = listOf(
        "NO DATA", "?", "CAN ERROR", "UNABLE TO CONNECT", "STOPPED", "BUS INIT", "BUS BUSY",
        "BUS ERROR", "BUFFER FULL", "DATA ERROR", "FB ERROR", "LV RESET", "ACT ALERT", "ERR",
    )

    /** Meaningful lines: no blanks, no echo of the command, no `SEARCHING...`. */
    fun lines(raw: String?, command: String): List<String> =
        raw.orEmpty().split('\r', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && it != command && it != "SEARCHING..." }

    /** The adapter's error text when every line is an error, else null. */
    fun errorOf(lines: List<String>): String? {
        if (lines.isEmpty()) return "EMPTY"
        val errors = lines.filter { line -> ERRORS.any { line.startsWith(it) } }
        return if (errors.size == lines.size) errors.joinToString(" / ") else null
    }
}
