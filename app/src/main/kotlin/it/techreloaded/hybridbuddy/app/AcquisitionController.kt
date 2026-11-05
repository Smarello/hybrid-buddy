package it.techreloaded.hybridbuddy.app

import android.content.Context
import it.techreloaded.hybridbuddy.exploration.BrakeExploration
import it.techreloaded.hybridbuddy.exploration.MappingRecording
import it.techreloaded.hybridbuddy.exploration.ReusedMapping
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.JsonlSessionRecorder
import it.techreloaded.hybridbuddy.session.RecordingHeader
import it.techreloaded.hybridbuddy.session.RecordingLine
import it.techreloaded.hybridbuddy.session.ReplayConnector
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SimulatedCar
import it.techreloaded.hybridbuddy.signals.BaseSignals
import it.techreloaded.hybridbuddy.signals.DriveSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val mutableLatestMapping = MutableStateFlow<ReusedMapping?>(null)

    /** Most recent completed mapping saved on the phone, refreshed by [refreshLatestMapping]. */
    val latestMapping: StateFlow<ReusedMapping?> = mutableLatestMapping

    /** Scans the exploration recordings, newest first, for the most recent completed mapping. */
    suspend fun refreshLatestMapping(context: Context) {
        mutableLatestMapping.value = withContext(Dispatchers.IO) {
            recordingsDir(context).listFiles { file -> file.extension == "jsonl" && file.name.startsWith(EXPLORATION_PREFIX) }
                .orEmpty().sortedByDescending { it.name }.asSequence()
                .firstNotNullOfOrNull { file -> file.bufferedReader().useLines { MappingRecording.read(file.name, it) } }
        }
    }

    fun recordingsDir(context: Context) = File(context.filesDir, "recordings").apply { mkdirs() }

    const val SESSION_PREFIX = "sessione-"
    const val EXPLORATION_PREFIX = "esplorazione-freni-"
    const val DRIVE_PREFIX = "guida-"

    /** Header note of drive recordings: their replay uses the drive schedule. */
    private const val DRIVE_NOTE = "guida"

    fun isDrive(session: AcquisitionSession?) = session?.schedule == DriveSignals.schedule

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

    /** With [drive], reads the Guida signals (brake torques, powers) and records to a `guida-` file. */
    fun startLive(context: Context, address: String, adapterName: String, drive: Boolean = false) {
        stop()
        val writer = newRecording(context, if (drive) DRIVE_PREFIX else SESSION_PREFIX).bufferedWriter()
        val session = AcquisitionSession(
            SessionSource.Live(adapterName),
            BluetoothConnector(context.applicationContext, address),
            schedule = if (drive) DriveSignals.schedule else listOf(BaseSignals.all),
            recorder = JsonlSessionRecorder(writer, liveHeader(note = if (drive) DRIVE_NOTE else null)),
        )
        start(session, usesAdapter = true) { writer.close() }
    }

    /** SIMULATED car for trying the Guida screen without the Yaris; nothing is recorded. */
    fun startSimulatedDrive() {
        stop()
        val session = AcquisitionSession(
            SessionSource.Replay("Guida simulata", simulated = true),
            SimulatedCar(replyDelayMs = SIMULATED_REPLY_MS),
            schedule = DriveSignals.schedule,
        )
        start(session, usesAdapter = false) {}
    }

    private const val SIMULATED_REPLY_MS = 25L

    /**
     * Runs under [AcquisitionService], which is stopped when the exploration ends on its own.
     * With [reuseMapping] the mapping is skipped and [latestMapping] is used instead.
     */
    fun startExploration(context: Context, address: String, reuseMapping: Boolean) {
        val mapping = if (reuseMapping) latestMapping.value ?: return AcquisitionService.stop(context) else null
        stop()
        val writer = newRecording(context, EXPLORATION_PREFIX).bufferedWriter()
        val exploration = BrakeExploration(
            BluetoothConnector(context.applicationContext, address),
            recorder = JsonlSessionRecorder(writer, liveHeader(note = "esplorazione freni")),
            reusedMapping = mapping,
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
        val schedule = if (header.note == DRIVE_NOTE) DriveSignals.schedule else listOf(BaseSignals.all)
        start(AcquisitionSession(SessionSource.Replay(fileName, simulated), ReplayConnector(lines), schedule), usesAdapter = false) {}
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
