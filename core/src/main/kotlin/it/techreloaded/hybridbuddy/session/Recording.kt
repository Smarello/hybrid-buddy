package it.techreloaded.hybridbuddy.session

import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.AdapterLink
import it.techreloaded.hybridbuddy.obdlink.AdapterReply
import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.obdlink.EndOfRecording
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.obdlink.InMemoryAdapterLink
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.io.Writer

/**
 * JSONL session recording: one [RecordingLine] per line, `type` discriminator.
 * Contains only what the analysis needs: no VIN (never requested), no Bluetooth address, no phone name.
 */
@Serializable
sealed interface RecordingLine

@Serializable
@SerialName("session")
data class RecordingHeader(
    val formatVersion: Int = 1,
    val startedAtEpochMs: Long,
    /** [LIVE] for real adapter data, [SIMULATED] for synthetic data that proves nothing about the car. */
    val source: String,
    val appVersion: String,
    val note: String? = null,
) : RecordingLine {
    companion object {
        const val LIVE = "live"
        const val SIMULATED = "simulated"
    }
}

@Serializable
@SerialName("exchange")
data class RecordedExchange(
    val t: Long,
    val cmd: String,
    val durationMs: Long,
    val raw: String? = null,
    val error: String? = null,
) : RecordingLine

@Serializable
@SerialName("event")
data class RecordedEvent(val t: Long, val event: String, val detail: String? = null) : RecordingLine {
    companion object {
        const val CONNECTED = "connected"
        const val CONNECT_FAILED = "connect_failed"
        const val DISCONNECTED = "disconnected"
    }
}

object Recording {
    private val json = Json { explicitNulls = false; ignoreUnknownKeys = true }

    fun encode(line: RecordingLine): String = json.encodeToString(RecordingLine.serializer(), line)

    /** @throws IllegalArgumentException when the first line is not a session header. */
    fun parse(lines: Sequence<String>): Pair<RecordingHeader, List<RecordingLine>> {
        val parsed = lines.filter { it.isNotBlank() }.map { json.decodeFromString(RecordingLine.serializer(), it) }.toList()
        val header = parsed.firstOrNull() as? RecordingHeader
            ?: throw IllegalArgumentException("la registrazione non inizia con l'intestazione di sessione")
        return header to parsed.drop(1)
    }
}

interface SessionRecorder {
    fun exchange(exchange: Exchange)
    fun event(atEpochMs: Long, event: String, detail: String? = null)
}

/** Writes and flushes one line per call, so an abrupt stop loses at most the line being written. */
class JsonlSessionRecorder(private val writer: Writer, header: RecordingHeader) : SessionRecorder {
    init {
        write(header)
    }

    override fun exchange(exchange: Exchange) = write(
        RecordedExchange(exchange.startedAtEpochMs, exchange.command, exchange.durationMs, exchange.raw, exchange.error),
    )

    override fun event(atEpochMs: Long, event: String, detail: String?) = write(RecordedEvent(atEpochMs, event, detail))

    private fun write(line: RecordingLine) = synchronized(writer) {
        writer.write(Recording.encode(line))
        writer.write("\n")
        writer.flush()
    }
}

/**
 * Plays a recording back as if it were the adapter: each command the session sends gets the next
 * recorded answer for that command, with its recorded duration. Connections, timeouts and
 * disconnections are reproduced, so replay runs through the same pipeline as the car.
 */
class ReplayConnector(private val lines: List<RecordingLine>) : AdapterConnector {
    private var cursor = 0

    override suspend fun connect(): AdapterLink {
        while (cursor < lines.size) {
            val line = lines[cursor++]
            if (line is RecordedEvent && line.event == RecordedEvent.CONNECTED) return InMemoryAdapterLink(::replyTo)
            if (line is RecordedEvent && line.event == RecordedEvent.CONNECT_FAILED) {
                throw IOException(line.detail ?: "connessione fallita")
            }
        }
        throw EndOfRecording()
    }

    private fun replyTo(command: String): AdapterReply {
        while (cursor < lines.size) {
            val line = lines[cursor]
            if (line is RecordedEvent && line.event != RecordedEvent.DISCONNECTED) return AdapterReply.Disconnect
            cursor++
            if (line is RecordedEvent) return AdapterReply.Disconnect
            if (line is RecordedExchange && line.cmd == command) return when (line.error) {
                ElmConnection.TIMEOUT -> AdapterReply.Silence
                ElmConnection.DISCONNECTED -> AdapterReply.Disconnect
                else -> AdapterReply.Text(listOf(line.raw.orEmpty() + ElmConnection.PROMPT), line.durationMs)
            }
        }
        return AdapterReply.Disconnect
    }
}
