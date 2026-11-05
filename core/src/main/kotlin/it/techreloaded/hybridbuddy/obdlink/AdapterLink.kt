package it.techreloaded.hybridbuddy.obdlink

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

/** Raw byte channel to the adapter (Bluetooth socket, replay or test fake). Blocking calls. */
interface AdapterLink : Closeable {
    /** @throws IOException when the link is gone. */
    fun write(text: String)

    /** Blocks until some bytes arrive; returns -1 at end of stream. @throws IOException when the link is gone. */
    fun read(buffer: ByteArray): Int
}

/** Opens a fresh link; called again on every reconnection. */
fun interface AdapterConnector {
    /** @throws IOException when the adapter cannot be reached; [EndOfRecording] when a replay is over. */
    suspend fun connect(): AdapterLink
}

class EndOfRecording : IOException("fine della registrazione")

sealed interface AdapterReply {
    /** Text sent back in [chunks] (as separate reads, to mimic split Bluetooth packets) after [delayMs]. */
    data class Text(val chunks: List<String>, val delayMs: Long = 0) : AdapterReply
    data object Silence : AdapterReply
    data object Disconnect : AdapterReply
}

/** In-memory adapter answering each written command with [reply]. Used by replay and by tests. */
class InMemoryAdapterLink(private val reply: (command: String) -> AdapterReply) : AdapterLink {
    private val incoming = LinkedBlockingQueue<ByteArray>()
    private val pendingCommand = StringBuilder()
    private var leftover: ByteArray? = null

    @Volatile
    private var closed = false

    override fun write(text: String) {
        if (closed) throw IOException("link chiuso")
        pendingCommand.append(text)
        while (true) {
            val end = pendingCommand.indexOf("\r")
            if (end < 0) return
            val command = pendingCommand.substring(0, end).trim()
            pendingCommand.delete(0, end + 1)
            when (val answer = reply(command)) {
                is AdapterReply.Text -> {
                    if (answer.delayMs > 0) Thread.sleep(answer.delayMs)
                    answer.chunks.forEach { incoming.put(it.toByteArray(Charsets.US_ASCII)) }
                }
                AdapterReply.Silence -> Unit
                AdapterReply.Disconnect -> close()
            }
        }
    }

    override fun read(buffer: ByteArray): Int {
        val chunk = leftover ?: incoming.take()
        if (chunk === END_OF_STREAM) {
            incoming.put(END_OF_STREAM)
            return -1
        }
        val count = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, 0, 0, count)
        leftover = if (count < chunk.size) chunk.copyOfRange(count, chunk.size) else null
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        incoming.put(END_OF_STREAM)
    }

    private companion object {
        val END_OF_STREAM = ByteArray(0)
    }
}
