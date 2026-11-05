package it.techreloaded.hybridbuddy.app

import android.content.Context
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.RecordingLine
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Process-wide owner of the running session, shared by the UI and [AcquisitionService].
 * Live sessions run under the foreground service; replays run without it.
 */
object AcquisitionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val mutableSession = MutableStateFlow<AcquisitionSession?>(null)
    val session: StateFlow<AcquisitionSession?> = mutableSession

    fun recordingsDir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    fun latestRecording(context: Context): File? =
        recordingsDir(context).listFiles { file -> file.extension == "jsonl" }?.maxByOrNull { it.name }

    fun startLive(context: Context, address: String, adapterName: String) {
        stop()
        val now = LocalDateTime.now()
        val file = File(recordingsDir(context), "sessione-${now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.jsonl")
        val writer = file.bufferedWriter()
        val header = RecordingHeader(
            startedAtEpochMs = System.currentTimeMillis(),
            source = RecordingHeader.LIVE,
            appVersion = BuildConfig.VERSION_NAME,
        )
        val session = AcquisitionSession(
            SessionSource.Live(adapterName),
            BluetoothConnector(context.applicationContext, address),
            recorder = JsonlSessionRecorder(writer, header),
        )
        start(session) { writer.close() }
    }

    fun startReplay(fileName: String, header: RecordingHeader, lines: List<RecordingLine>) {
        stop()
        val simulated = header.source == RecordingHeader.SIMULATED
        start(AcquisitionSession(SessionSource.Replay(fileName, simulated), ReplayConnector(lines))) {}
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Stops only a live session, so a replay started meanwhile survives the service shutdown. */
    fun stopLive() {
        if (session.value?.state?.value?.source is SessionSource.Live) stop()
    }

    val isRunning: Boolean get() = job?.isActive == true

    private fun start(session: AcquisitionSession, onEnd: () -> Unit) {
        mutableSession.value = session
        job = scope.launch {
            try {
                session.run()
            } finally {
                onEnd()
            }
        }
    }
}
