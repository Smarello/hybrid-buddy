package it.techreloaded.hybridbuddy.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.techreloaded.hybridbuddy.exploration.ExplorationState
import it.techreloaded.hybridbuddy.exploration.ExplorationStep
import it.techreloaded.hybridbuddy.exploration.PedalPhase
import it.techreloaded.hybridbuddy.exploration.TargetProgress
import it.techreloaded.hybridbuddy.exploration.TargetStatus
import kotlinx.coroutines.delay

private const val VISIBLE_EXCHANGES = 5

private enum class StepStatus { PENDING, ACTIVE, DONE, INTERRUPTED }

/** Guided, read-only exploration of the brake signals (Variante B: every step on one page). */
@Composable
internal fun BrakeExplorationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("adapter", Context.MODE_PRIVATE) }
    val exploration by AcquisitionController.exploration.collectAsState()
    val state = exploration?.state?.collectAsState()?.value
    val running = state != null && state.step in setOf(ExplorationStep.CONNECTING, ExplorationStep.MAPPING, ExplorationStep.PEDAL)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    // The owner follows the pedal phases on screen: keep it on while the exploration runs.
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    fun start() {
        val address = preferences.getString("address", null)
        if (address == null) {
            Toast.makeText(context, "Scegli prima l'LX con «Dispositivo» nella diagnostica", Toast.LENGTH_LONG).show()
        } else {
            AcquisitionService.startExploration(context, address)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) start()
        else Toast.makeText(context, "Serve il permesso Bluetooth per collegarsi all'LX", Toast.LENGTH_LONG).show()
    }
    fun startWithPermission() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 20.sp, color = Grey) }
            Text("Esplora freni", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            val adapterName = preferences.getString("name", null) ?: "OBDLink"
            Text(
                if (running) "LIVE · $adapterName" else "SOLA LETTURA",
                color = if (running) Green else Grey, fontSize = 12.sp,
                modifier = Modifier.background(Color(0xFF2A2F36), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        listOfNotNull(state?.adapterIdentity, state?.protocol).takeIf { it.isNotEmpty() }?.let {
            Text(it.joinToString(" · "), color = Grey, fontSize = 12.sp)
        }

        Column(Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 4.dp)) {
            PrepareStep(state, onStart = ::startWithPermission)
            HorizontalDivider(color = Color(0xFF23272D))
            MappingStep(state, now)
            HorizontalDivider(color = Color(0xFF23272D))
            PedalStep(state, now)
            HorizontalDivider(color = Color(0xFF23272D))
            EndStep(state)
        }

        SectionLabel("Ultimi scambi")
        ExchangeLog(state?.recentExchanges.orEmpty().takeLast(VISIBLE_EXCHANGES))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(
                    onClick = { AcquisitionService.stop(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2626), contentColor = Color(0xFFFFD6D6)),
                ) { Text("■ Interrompi") }
            } else if (state != null) {
                OutlinedButton(onClick = ::startWithPermission) { Text("Ripeti esplorazione") }
            }
            OutlinedButton(
                onClick = { exportLatest(context, AcquisitionController.EXPLORATION_PREFIX) },
                enabled = !running,
            ) { Text("Esporta esplorazione") }
        }
    }
}

@Composable
private fun PrepareStep(state: ExplorationState?, onStart: () -> Unit) {
    StepRow(1, if (state == null) StepStatus.ACTIVE else StepStatus.DONE, "Prepara l'auto") {
        Detail("READY, leva in P, freno di stazionamento, piede lontano dal pedale. Durata: 5–10 minuti di mappa, poi 1 minuto col pedale.")
        if (state == null) Button(onClick = onStart, modifier = Modifier.padding(top = 6.dp)) { Text("▶ Inizia") }
    }
}

@Composable
private fun MappingStep(state: ExplorationState?, now: Long) {
    val started = state?.mappingStartedAtMs
    val ended = state?.mappingEndedAtMs
    val status = when {
        state == null -> StepStatus.PENDING
        ended != null -> StepStatus.DONE
        state.step == ExplorationStep.CONNECTING || state.step == ExplorationStep.MAPPING -> StepStatus.ACTIVE
        else -> StepStatus.INTERRUPTED
    }
    val title = "Mappa delle centraline" + if (started != null && ended != null) " · ${formatDuration(ended - started)}" else ""
    StepRow(2, status, title) {
        when {
            state == null -> Detail("7E2 ibrida e 7B0 freni: mode 21 completo e mode 22 1000–1FFF, sola lettura.")
            state.step == ExplorationStep.CONNECTING -> Detail("Collegamento all'LX…")
            else -> {
                state.targets.forEach { Detail(targetLine(it)) }
                if (status == StepStatus.ACTIVE && started != null) {
                    val left = if (state.mappingSent == 0) null
                    else (now - started) * (state.mappingTotal - state.mappingSent) / state.mappingSent
                    LinearProgressIndicator(
                        progress = { state.mappingSent.toFloat() / state.mappingTotal.coerceAtLeast(1) },
                        color = Amber, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Detail("${state.mappingSent} / ${state.mappingTotal} richieste" + (left?.let { " · restano ~${formatDuration(it)}" } ?: ""))
                }
            }
        }
    }
}

@Composable
private fun PedalStep(state: ExplorationState?, now: Long) {
    val phase = state?.pedalPhase
    val status = when {
        state == null -> StepStatus.PENDING
        state.step == ExplorationStep.PEDAL -> StepStatus.ACTIVE
        state.step == ExplorationStep.DONE -> StepStatus.DONE
        state.mappingEndedAtMs != null -> StepStatus.INTERRUPTED // stopped or failed during the pedal phases
        else -> StepStatus.PENDING
    }
    val title = when {
        state?.pedalSkipped == true -> "Pedale del freno · saltato"
        status == StepStatus.ACTIVE -> "Pedale del freno · in corso"
        else -> "Pedale del freno"
    }
    StepRow(3, status, title) {
        when {
            state?.pedalSkipped == true -> Detail("Nessun identificativo risponde: non c'è niente da leggere col pedale.")
            status == StepStatus.ACTIVE && phase != null -> {
                val secondsLeft = ((state.pedalPhaseEndsAtMs ?: now) - now).coerceAtLeast(0) / 1000 + 1
                Column(Modifier.fillMaxWidth().background(Amber.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("${phase.instruction} · $secondsLeft s", color = Amber, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Detail(if (phase.pressed) "Tienilo fermo fino al prossimo avviso" else "Non toccare il pedale fino al prossimo avviso")
                    PhaseChips(phase)
                }
            }
            status == StepStatus.DONE -> Detail("4 fasi registrate: rilasciato, leggero, a fondo, rilasciato.")
            else -> Detail("4 fasi guidate da 15 s: rilasciato, leggero, a fondo, rilasciato.")
        }
    }
}

@Composable
private fun PhaseChips(current: PedalPhase) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
        PedalPhase.entries.forEach { phase ->
            val (text, color) = when {
                phase.ordinal < current.ordinal -> "✔ ${phase.label}" to Green
                phase == current -> "● ${phase.label}" to Amber
                else -> "○ ${phase.label}" to Grey
            }
            Text(
                text, color = color, fontSize = 11.sp, fontWeight = if (phase == current) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.background(Color(0xFF23272D), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun EndStep(state: ExplorationState?) {
    val (status, text) = when (state?.step) {
        ExplorationStep.DONE -> StepStatus.DONE to
            "${state.changedWithPedal ?: 0} identificativi cambiano col pedale (stima indicativa: l'analisi vera si fa sul file). " +
            "Premi «Esporta esplorazione» e invia il file."
        ExplorationStep.STOPPED -> StepStatus.INTERRUPTED to "Interrotta: quanto registrato resta esportabile."
        ExplorationStep.FAILED -> StepStatus.INTERRUPTED to "Non riuscita: ${state.failure}. Quanto registrato resta esportabile."
        else -> StepStatus.PENDING to "Riepilogo di cosa cambia col pedale, poi «Esporta esplorazione»."
    }
    StepRow(4, status, "Fine") {
        Detail(text, color = if (state?.step == ExplorationStep.FAILED) Red else Grey)
    }
}

@Composable
private fun StepRow(number: Int, status: StepStatus, title: String, content: @Composable () -> Unit) {
    val (color, symbol) = when (status) {
        StepStatus.DONE -> Green to "✔"
        StepStatus.ACTIVE -> Amber to "$number"
        StepStatus.INTERRUPTED -> Red to "!"
        StepStatus.PENDING -> Grey to "$number"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(24.dp).background(color.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Text(symbol, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (status == StepStatus.PENDING) Grey else Color.Unspecified)
            content()
        }
    }
}

@Composable
private fun Detail(text: String, color: Color = Grey) {
    Text(text, color = color, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
}

private fun targetLine(progress: TargetProgress): String {
    val name = "${progress.target.requestHeader} ${progress.target.label}"
    return when (progress.status) {
        TargetStatus.PENDING -> "$name: in attesa"
        TargetStatus.MAPPING -> "$name: ${progress.responding} rispondono finora"
        TargetStatus.SILENT -> "$name: non risponde"
        TargetStatus.DONE ->
            "$name: ${progress.responding} rispondono (mode 21: ${progress.mode21Responding} · mode 22: ${progress.mode22Responding})"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
}
