package it.techreloaded.hybridbuddy.app

import android.content.Context
import it.techreloaded.hybridbuddy.exploration.BrakeExploration
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
 * Process-wide owner of the running session or brake exploration, shared by the UI and [AcquisitionService].
 * Live sessions and explorations run under the foreground service; replays run without it.
 */
object AcquisitionController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var jobUsesAdapter = false
    private var runningExploration = false

    private val mutableSession = MutableStateFlow<AcquisitionSession?>(null)
    val session: StateFlow<AcquisitionSession?> = mutableSession

    private val mutableExploration = MutableStateFlow<BrakeExploration?>(null)
    val exploration: StateFlow<BrakeExploration?> = mutableExploration

    fun recordingsDir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    const val SESSION_PREFIX = "sessione-"
    const val EXPLORATION_PREFIX = "esplorazione-freni-"

    fun latestRecording(context: Context, prefix: String): File? =
        recordingsDir(context).listFiles { file -> file.extension == "jsonl" && file.name.startsWith(prefix) }
            ?.maxByOrNull { it.name }

    private fun newRecording(context: Context, prefix: String): File =
        File(recordingsDir(context), "$prefix${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.jsonl")

    private fun liveHeader(note: String? = null) = RecordingHeader(
        startedAtEpochMs = System.currentTimeMillis(),
        source = RecordingHeader.LIVE,
        appVersion = BuildConfig.VERSION_NAME,
        note = note,
    )

    fun startLive(context: Context, address: String, adapterName: String) {
        stop()
        val writer = newRecording(context, SESSION_PREFIX).bufferedWriter()
        val session = AcquisitionSession(
            SessionSource.Live(adapterName),
            BluetoothConnector(context.applicationContext, address),
            recorder = JsonlSessionRecorder(writer, liveHeader()),
        )
        start(session, usesAdapter = true) { writer.close() }
    }

    /** Runs under [AcquisitionService], which is stopped when the exploration ends on its own. */
    fun startExploration(context: Context, address: String) {
        stop()
        val writer = newRecording(context, EXPLORATION_PREFIX).bufferedWriter()
        val exploration = BrakeExploration(
            BluetoothConnector(context.applicationContext, address),
            recorder = JsonlSessionRecorder(writer, liveHeader(note = "esplorazione freni")),
        )
        mutableExploration.value = exploration
        val appContext = context.applicationContext
        jobUsesAdapter = true
        runningExploration = true
        job = scope.launch {
            try {
                exploration.run()
            } finally {
                writer.close()
            }
            // Ended on its own (not cancelled by a newer session): release the notification and the wake lock.
            AcquisitionService.stop(appContext)
        }
    }

    fun startReplay(fileName: String, header: RecordingHeader, lines: List<RecordingLine>) {
        stop()
        val simulated = header.source == RecordingHeader.SIMULATED
        start(AcquisitionSession(SessionSource.Replay(fileName, simulated), ReplayConnector(lines)), usesAdapter = false) {}
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Stops only work that uses the adapter (live session or exploration), so a replay survives the service shutdown. */
    fun stopLive() {
        if (jobUsesAdapter) stop()
    }

    val isRunning: Boolean get() = job?.isActive == true

    /** True while a brake exploration is running (the screen reopens on it). */
    val isExplorationRunning: Boolean get() = isRunning && runningExploration

    private fun start(session: AcquisitionSession, usesAdapter: Boolean, onEnd: () -> Unit) {
        mutableSession.value = session
        jobUsesAdapter = usesAdapter
        runningExploration = false
        job = scope.launch {
            try {
                session.run()
            } finally {
                onEnd()
            }
        }
    }
}
