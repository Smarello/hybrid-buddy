package it.techreloaded.hybridbuddy.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.techreloaded.hybridbuddy.interpretation.BrakeIndicator
import it.techreloaded.hybridbuddy.interpretation.Fresh
import it.techreloaded.hybridbuddy.interpretation.LinkLevel
import it.techreloaded.hybridbuddy.interpretation.batteryPowerKw
import it.techreloaded.hybridbuddy.interpretation.brakeIndicator
import it.techreloaded.hybridbuddy.interpretation.brakeReadsPerSecond
import it.techreloaded.hybridbuddy.interpretation.driveEcuStatus
import it.techreloaded.hybridbuddy.interpretation.enginePowerKw
import it.techreloaded.hybridbuddy.interpretation.freshValue
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.signals.DriveSignals
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GreenTrack = Color(0xFF23432E)
private val RedTrack = Color(0xFF4A2526)
private val DimTrack = Color(0xFF2A2E35)
private val ScreenBackground = Color(0xFF111316)
private val Caliper = Color(0xFFE6E8EB)

/** Guida screen (variant C): brake disc with a ring filling green → red, compact power meter. */
@Composable
internal fun DriveScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("adapter", Context.MODE_PRIVATE) }
    val session by AcquisitionController.session.collectAsState()
    val current = session.takeIf { AcquisitionController.isDrive(it) }
    val state = current?.state?.collectAsState()?.value
    val running = state != null && state.phoneLink != PhoneLinkState.FINISHED

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    // Read while driving: keep the screen on while the screen is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun startLive() {
        val address = preferences.getString("address", null)
        if (address == null) {
            Toast.makeText(context, "Scegli prima l'LX con «Dispositivo» nella diagnostica", Toast.LENGTH_LONG).show()
        } else {
            AcquisitionService.start(context, address, preferences.getString("name", null) ?: "OBDLink", drive = true)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) startLive()
        else Toast.makeText(context, "Serve il permesso Bluetooth per collegarsi all'LX", Toast.LENGTH_LONG).show()
    }
    fun startWithPermission() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) startLive()
        else permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }
    // Opening the screen starts the live drive, unless a drive (live, simulated or replayed) is already running.
    LaunchedEffect(Unit) {
        val bluetoothGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!running && bluetoothGranted && preferences.getString("address", null) != null) startLive()
    }
    val openRecording = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startReplay(context, displayName(context, uri)) { context.contentResolver.openInputStream(uri)!! }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 20.sp, color = Grey) }
            Text("Guida", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            SourceChip(state?.source)
        }
        EcuLine(state, now)
        BrakeCard(state, now)
        PowerCard(state, now)
        MissingCard(state, now)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(
                    onClick = { if (state.source is SessionSource.Live) AcquisitionService.stop(context) else AcquisitionController.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2626), contentColor = Color(0xFFFFD6D6)),
                ) { Text("■ Ferma") }
            } else {
                Button(onClick = ::startWithPermission) { Text("▶ Avvia") }
                OutlinedButton(onClick = {
                    AcquisitionService.stop(context)
                    AcquisitionController.startSimulatedDrive()
                }) { Text("Simulazione (SIMULATA)") }
                OutlinedButton(onClick = { openRecording.launch(arrayOf("*/*")) }) { Text("Apri registrazione") }
            }
            OutlinedButton(onClick = { exportLatest(context, AcquisitionController.DRIVE_PREFIX) }) { Text("Esporta guida") }
        }
        Text(
            "Sola lettura. Segnali candidati da Hybrid Assistant (Yaris MY20), non ancora provati su questa Yaris.",
            color = Grey, fontSize = 11.sp,
        )
    }
}

@Composable
private fun SourceChip(source: SessionSource?) {
    val (text, color) = when (source) {
        null -> "FERMO" to Grey
        is SessionSource.Live -> "LIVE" to Green
        is SessionSource.Replay -> (if (source.simulated) "SIMULATA" else "REPLAY · ${source.fileName}") to Amber
    }
    Text(
        text, color = color, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier.background(Color(0xFF2A2F36), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun EcuLine(state: SessionState?, now: Long) {
    val links = if (state == null) emptyList() else driveEcuStatus(state, now)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        links.forEach { link ->
            val (color, symbol) = when (link.level) {
                LinkLevel.OK -> Green to "●"
                LinkLevel.WARNING -> Amber to "●"
                LinkLevel.ERROR -> Red to "✖"
                LinkLevel.IDLE -> Grey to "○"
            }
            Text("$symbol ${link.label}", color = color, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BrakeCard(state: SessionState?, now: Long) {
    val indicator = if (state == null) BrakeIndicator.Unavailable("non avviato") else brakeIndicator(state, now)
    val (color, title, below) = when (indicator) {
        is BrakeIndicator.Idle -> Triple(Grey, indicator.label, "—")
        is BrakeIndicator.Regenerative -> Triple(Green, "Rigenerativa", "rigenerativa ${indicator.intensityPercent?.let { "$it %" } ?: "—"}")
        is BrakeIndicator.Friction -> Triple(Red, "Attrito", "attrito ${indicator.frictionPercent} %")
        is BrakeIndicator.Unavailable -> Triple(Amber, "Non disponibile", indicator.reason)
    }
    Row(
        Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(14.dp)).padding(14.dp)
            .semantics { contentDescription = "Frenata: $title, $below" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrakeGauge(indicator.fill, if (indicator is BrakeIndicator.Unavailable) Grey else color, dim = indicator is BrakeIndicator.Unavailable)
            Text(below, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
        }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title.uppercase(), color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val speed = state?.let { freshValue(DriveSignals.vehicleSpeed, it, now) }
            Text(
                if (speed is Fresh.Value) String.format(Locale.ITALY, "%.0f km/h", speed.value) else "velocità —",
                color = Grey, fontSize = 14.sp,
            )
            if (state != null) {
                Text(String.format(Locale.ITALY, "freno %.1f letture/s", brakeReadsPerSecond(state, now)), color = Grey, fontSize = 11.sp)
            }
        }
    }
}

/** Stylised brake disc with caliper; the ring fills green (regeneration) then red (friction share). */
@Composable
private fun BrakeGauge(fill: Double, color: Color, dim: Boolean) {
    Canvas(Modifier.size(140.dp)) {
        val stroke = 12.dp.toPx()
        val ringRadius = size.minDimension / 2 - stroke / 2
        val topLeft = Offset(center.x - ringRadius, center.y - ringRadius)
        val ringSize = Size(ringRadius * 2, ringRadius * 2)
        fun arc(color: Color, start: Float, sweep: Float) =
            drawArc(color, start, sweep, useCenter = false, topLeft = topLeft, size = ringSize, style = Stroke(stroke, cap = StrokeCap.Round))
        // 270° ring from bottom-left (135°) through the top to bottom-right: first half green, second half red.
        arc(if (dim) DimTrack else GreenTrack, 135f, 135f)
        arc(if (dim) DimTrack else RedTrack, 270f, 135f)
        val swept = (fill.coerceIn(0.0, 1.0) * 270).toFloat()
        if (swept > 0) arc(Green, 135f, minOf(swept, 135f))
        if (swept > 135) arc(Red, 270f, swept - 135f)
        brakeDisc(ringRadius - stroke * 1.4f, color)
    }
}

private fun DrawScope.brakeDisc(radius: Float, color: Color) {
    drawCircle(color.copy(alpha = 0.9f), radius, center)
    for (index in 0 until 8) {
        val angle = index * PI / 4
        val hole = Offset(center.x + radius * 0.62f * cos(angle).toFloat(), center.y + radius * 0.62f * sin(angle).toFloat())
        drawCircle(ScreenBackground, radius * 0.07f, hole)
    }
    drawCircle(ScreenBackground, radius * 0.32f, center)
    drawCircle(color, radius * 0.12f, center)
    rotate(35f, center) {
        drawRoundRect(
            Caliper, Offset(center.x + radius * 0.45f, center.y - radius * 0.95f), Size(radius * 0.55f, radius * 0.9f),
            CornerRadius(radius * 0.15f),
        )
    }
}

@Composable
private fun PowerCard(state: SessionState?, now: Long) {
    Row(Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(14.dp)).padding(14.dp)) {
        PowerValue("Termico", state?.let { enginePowerKw(it, now) }, Modifier.weight(1f)) { Caliper }
        PowerValue("Batteria", state?.let { batteryPowerKw(it, now) }, Modifier.weight(1f)) { if (it < 0) Green else Caliper }
    }
}

@Composable
private fun PowerValue(label: String, power: Fresh?, modifier: Modifier, color: (Double) -> Color) {
    Column(modifier) {
        val suffix = if (label == "Batteria" && power is Fresh.Value) if (power.value < 0) " · in carica" else " · in scarica" else ""
        Text((label + suffix).uppercase(), color = Grey, fontSize = 11.sp)
        if (power is Fresh.Value) {
            Text(
                String.format(Locale.ITALY, "%.1f kW", power.value), color = color(power.value),
                fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            )
        } else {
            Text("Non disponibile", color = Grey, fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Shown only when something is missing: the cause of every "Non disponibile". */
@Composable
private fun MissingCard(state: SessionState?, now: Long) {
    if (state == null) return
    val missing = listOfNotNull(
        (brakeIndicator(state, now) as? BrakeIndicator.Unavailable)?.let { "Frenata: ${it.reason}" },
        (enginePowerKw(state, now) as? Fresh.Missing)?.let { "Termico: ${it.reason}" },
        (batteryPowerKw(state, now) as? Fresh.Missing)?.let { "Batteria: ${it.reason}" },
    )
    if (missing.isEmpty()) return
    Column(Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(14.dp)).padding(14.dp)) {
        Text("SE UN DATO MANCA", color = Grey, fontSize = 11.sp)
        missing.forEach { Text(it, color = Amber, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
    }
}
