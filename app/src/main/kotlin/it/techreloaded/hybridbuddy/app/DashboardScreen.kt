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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.techreloaded.hybridbuddy.interpretation.BrakeIndicator
import it.techreloaded.hybridbuddy.interpretation.DriveState
import it.techreloaded.hybridbuddy.interpretation.EngineState
import it.techreloaded.hybridbuddy.interpretation.Fresh
import it.techreloaded.hybridbuddy.interpretation.LinkLevel
import it.techreloaded.hybridbuddy.interpretation.brakeIndicator
import it.techreloaded.hybridbuddy.interpretation.brakeReadsPerSecond
import it.techreloaded.hybridbuddy.interpretation.driveEcuStatus
import it.techreloaded.hybridbuddy.interpretation.enginePowerKw
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SessionState
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val GreenTrack = Color(0xFF23432E)
private val RedTrack = Color(0xFF4A2526)
private val DimTrack = Color(0xFF2A2E35)
private val ScreenBackground = Color(0xFF111316)
private val Bright = Color(0xFFE6E8EB)
private val Aqua = Color(0xFF4FC3F7)
private val FutureBackground = Color(0xFF15171B)
private val FutureText = Color(0xFF4F555E)

/** Full scale of the engine bar: 67 kW, the 1.5 l engine of the Yaris Hybrid 115 on the data sheet. */
private const val ENGINE_MAX_KW = 67.0

/** Full scale of the battery bar: −30…+27 kW seen on the first drive (2026-11-05). ponytail: tune on the car, with the limits. */
private const val BATTERY_MAX_KW = 30.0

private const val NOT_STARTED = "non avviato"

/**
 * Main screen, laid out as the Hybrid Assistant dashboard (user guide v1.1, §8.1, figure 8.1): speed, brake, temperatures and
 * power meter on top; consumption, battery temperature; EV, trip, engine and state of charge; bottom bar with the car and
 * the settings (here: the debug mode). A tile whose signals Hybrid Buddy does not read yet is greyed out, never faked.
 */
@Composable
internal fun DashboardScreen(onDebug: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("adapter", Context.MODE_PRIVATE) }
    val session by AcquisitionController.session.collectAsState()
    val current = session.takeIf { AcquisitionController.isDrive(it) }
    val state = current?.state?.collectAsState()?.value
    val running = state != null && state.phoneLink != PhoneLinkState.FINISHED
    var choosingDevice by remember { mutableStateOf(false) }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(200)
        }
    }
    // Read while driving: keep the screen on while the dashboard is open.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    fun startLive() {
        val address = preferences.getString("address", null)
        if (address == null) choosingDevice = true
        else AcquisitionService.start(context, address, preferences.getString("name", null) ?: "OBDLink", drive = true)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) startLive()
        else Toast.makeText(context, "Serve il permesso Bluetooth per collegarsi all'LX", Toast.LENGTH_LONG).show()
    }
    fun startWithPermission() {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) startLive()
        else permission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
    }
    // As Hybrid Assistant: opening the app connects to the car, unless a drive (live, simulated or replayed) is already running.
    LaunchedEffect(Unit) {
        val bluetoothGranted = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!running && bluetoothGranted && preferences.getString("address", null) != null) startLive()
    }

    val car = state?.let { DriveState(it, now) }
    Column(
        Modifier.fillMaxSize().background(ScreenBackground).safeDrawingPadding().padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SpeedTile(car, Modifier.weight(1.2f))
                FutureTile("Consumo", "L/100 km", Modifier.weight(0.8f))
                EvTile(car, Modifier.weight(1f))
            }
            Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1.5f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrakeTile(state, now, Modifier.weight(1f))
                    FutureTile("Temperature", "termico · motori elettrici · inverter", Modifier.weight(1f))
                }
                FutureTile("Batteria HV", "temperatura · ventola", Modifier.weight(0.7f))
                FutureTile("Viaggio", "tempo · distanza · quota EV", Modifier.weight(0.9f))
            }
            Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PowerMeterTile(state, car, now, Modifier.weight(2f))
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineTile(car, Modifier.weight(1f))
                    FutureTile("Batteria HV", "% di carica", Modifier.weight(1f))
                }
            }
        }
        BottomBar(
            state, now, running,
            onStart = ::startWithPermission,
            onStop = { if (state?.source is SessionSource.Live) AcquisitionService.stop(context) else AcquisitionController.stop() },
            onDebug = onDebug,
        )
    }

    if (choosingDevice) {
        DevicePicker(
            onChosen = { address, name ->
                preferences.edit().putString("address", address).putString("name", name).apply()
                choosingDevice = false
                startLive()
            },
            onDismiss = { choosingDevice = false },
        )
    }
}

@Composable
private fun Tile(modifier: Modifier, background: Color = CardBackground, content: @Composable BoxScope.() -> Unit) =
    Box(modifier.fillMaxWidth().background(background, RoundedCornerShape(10.dp)).padding(8.dp), content = content)

/** A tile with its caption on top and the content centred in the rest. */
@Composable
private fun CaptionedTile(caption: String, modifier: Modifier, content: @Composable BoxScope.() -> Unit) = Tile(modifier) {
    Column(Modifier.fillMaxSize()) {
        Caption(caption)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center, content = content)
    }
}

/** A tile of the Hybrid Assistant dashboard whose signals are not read yet: greyed out, never a fake value. */
@Composable
private fun FutureTile(title: String, detail: String, modifier: Modifier) = Tile(modifier.semantics { contentDescription = "$title: in arrivo" }, FutureBackground) {
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title.uppercase(), color = FutureText, fontSize = 11.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center, maxLines = 1)
        Text("$detail · in arrivo", color = FutureText, fontSize = 10.sp, fontStyle = FontStyle.Italic, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) = Text(text.uppercase(), color = Grey, fontSize = 10.sp, letterSpacing = 1.sp, modifier = modifier)

/** "Non disponibile" with its cause: never zero, never the last value. */
@Composable
private fun Unavailable(reason: String, modifier: Modifier = Modifier) = Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Text("Non disponibile", color = Grey, fontSize = 10.sp, maxLines = 1, softWrap = false)
    Text(reason, color = Amber, fontSize = 9.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

private fun Fresh?.missingReason() = (this as? Fresh.Missing)?.reason ?: NOT_STARTED

@Composable
private fun SpeedTile(car: DriveState?, modifier: Modifier) = CaptionedTile("Velocità", modifier) {
    when (val speed = car?.speed) {
        is Fresh.Value -> {
            Text(
                String.format(Locale.ITALY, "%.0f", speed.value), color = Bright, fontSize = 44.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("km/h", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd))
        }
        else -> Unavailable(speed.missingReason())
    }
}

/** EV icon of Hybrid Assistant: yellow when running electric-only, grey on petrol (red with a low charge needs the SOC, not read yet). */
@Composable
private fun EvTile(car: DriveState?, modifier: Modifier) = Tile(modifier) {
    val (color, label) = when (car?.electricOnly) {
        null -> FutureText to "Non disponibile: ${car?.engineRpm.missingReason()}"
        true -> Amber to "solo elettrico"
        false -> Grey to "termico in uso"
    }
    Column(Modifier.fillMaxSize().semantics { contentDescription = "EV: $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.weight(1f).aspectRatio(1.8f, matchHeightConstraintsFirst = true).background(color.copy(alpha = 0.2f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { Text("EV", color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        Text(label, color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        HistorySquares()
    }
}

/** The five squares of Hybrid Assistant (last brakings, consumption history): not computed yet, so all grey. */
@Composable
private fun HistorySquares() = Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
    repeat(5) { Box(Modifier.size(width = 14.dp, height = 8.dp).background(FutureText, RoundedCornerShape(2.dp))) }
}

@Composable
private fun BrakeTile(state: SessionState?, now: Long, modifier: Modifier) {
    val indicator = if (state == null) BrakeIndicator.Unavailable(NOT_STARTED) else brakeIndicator(state, now)
    val (color, label) = when (indicator) {
        is BrakeIndicator.Idle -> Grey to indicator.label
        is BrakeIndicator.Regenerative ->
            Green to (if (indicator.liftOff) "Rilascio" else "Rigenerativa") + (indicator.intensityPercent?.let { " $it %" } ?: "")
        is BrakeIndicator.Friction -> Red to "Attrito ${indicator.frictionPercent} %"
        is BrakeIndicator.Unavailable -> Amber to "Non disponibile: ${indicator.reason}"
    }
    val unavailable = indicator is BrakeIndicator.Unavailable
    Tile(modifier.semantics { contentDescription = "Frenata: $label" }) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            BrakeGauge(indicator.fill, if (unavailable) Grey else color, dim = unavailable, Modifier.weight(1f))
            Text(label, color = color, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            HistorySquares()
        }
    }
}

/** Stylised brake disc with caliper; the ring fills green (regeneration) then red (friction share). */
@Composable
private fun BrakeGauge(fill: Double, color: Color, dim: Boolean, modifier: Modifier) {
    Canvas(modifier.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
        val stroke = size.minDimension * 0.09f
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
        drawCircle(CardBackground, radius * 0.07f, hole)
    }
    drawCircle(CardBackground, radius * 0.32f, center)
    drawCircle(color, radius * 0.12f, center)
    rotate(35f, center) {
        drawRoundRect(
            Bright, Offset(center.x + radius * 0.45f, center.y - radius * 0.95f), Size(radius * 0.55f, radius * 0.9f),
            CornerRadius(radius * 0.15f),
        )
    }
}

/** Engine icon of Hybrid Assistant: rpm, grey stopped, green running, yellow spinning without fuel. The S-state is not read. */
@Composable
private fun EngineTile(car: DriveState?, modifier: Modifier) = CaptionedTile("Termico", modifier) {
    val rpm = car?.engineRpm
    if (car != null && rpm is Fresh.Value) {
        val (color, label) = when (car.engineState) {
            EngineState.OFF -> Grey to "spento"
            EngineState.RUNNING -> Green to "acceso"
            EngineState.SPINNING -> Amber to "trascinato"
            null -> Bright to "coppia: ${car.engineTorque.missingReason()}"
        }
        Column(Modifier.semantics { contentDescription = "Termico: $label" }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(String.format(Locale.ITALY, "%.0f", rpm.value), color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text("rpm · $label", color = color, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Unavailable(rpm.missingReason())
    }
}

/**
 * Power meter of Hybrid Assistant: two vertical bars, each with its own full scale. Engine from the bottom; battery from the
 * centre, up while depleting (aqua), down while charging (green), with the charge limit marked on the right. The efficiency
 * colours of the engine bar and the discharge limit need signals not read yet.
 */
@Composable
private fun PowerMeterTile(state: SessionState?, car: DriveState?, now: Long, modifier: Modifier) = Tile(modifier) {
    val engine = state?.let { enginePowerKw(it, now) }
    val battery = car?.batteryPower
    val chargeLimit = car?.chargeLimit
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PowerBar("Termico", engine, if (engine is Fresh.Value && engine.value > 0) Bright else Grey, Modifier.weight(1f)) { kw ->
            val fraction = (kw / ENGINE_MAX_KW).coerceIn(0.0, 1.0).toFloat()
            drawRect(Bright, Offset(0f, size.height * (1 - fraction)), Size(size.width, size.height * fraction))
        }
        Text("kW", color = Grey, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        PowerBar("Batteria", battery, if (battery is Fresh.Value && battery.value < 0) Green else Aqua, Modifier.weight(1f)) { kw ->
            val half = size.height / 2
            val extent = (abs(kw) / BATTERY_MAX_KW).coerceIn(0.0, 1.0).toFloat() * half
            if (kw < 0) drawRect(Green, Offset(0f, half), Size(size.width, extent))
            else drawRect(Aqua, Offset(0f, half - extent), Size(size.width, extent))
            drawRect(Grey, Offset(0f, half - 1f), Size(size.width, 2f))
            if (chargeLimit is Fresh.Value) {
                val y = half + (abs(chargeLimit.value) / BATTERY_MAX_KW).coerceIn(0.0, 1.0).toFloat() * half
                val mark = size.width * 0.25f
                drawPath(Path().apply { moveTo(size.width, y); lineTo(size.width + mark, y - mark / 2); lineTo(size.width + mark, y + mark / 2); close() }, Green)
            }
        }
    }
}

@Composable
private fun PowerBar(title: String, power: Fresh?, valueColor: Color, modifier: Modifier, draw: DrawScope.(kw: Double) -> Unit) =
    Column(modifier.fillMaxHeight().semantics { contentDescription = "$title: ${(power as? Fresh.Value)?.value?.let { String.format(Locale.ITALY, "%.1f kW", it) } ?: "non disponibile"}" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Caption(title)
        Row(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Spacer(Modifier.weight(1f))
            Canvas(Modifier.fillMaxHeight().width(26.dp)) {
                drawRect(DimTrack)
                if (power is Fresh.Value) draw(power.value)
            }
            Spacer(Modifier.weight(1f))
        }
        if (power is Fresh.Value) {
            Text(String.format(Locale.ITALY, "%.1f", power.value), color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        } else {
            Unavailable(power.missingReason())
        }
    }

/** Bottom bar of Hybrid Assistant: battery check (not yet), the car with its control units, the wrench (debug mode), start/stop. */
@Composable
private fun BottomBar(state: SessionState?, now: Long, running: Boolean, onStart: () -> Unit, onStop: () -> Unit, onDebug: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.fillMaxHeight().background(FutureBackground, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp)
                .semantics { contentDescription = "Controllo batteria: in arrivo" },
            contentAlignment = Alignment.Center,
        ) { Text("Check", color = FutureText, fontSize = 13.sp) }
        val links = state?.let { driveEcuStatus(it, now) }.orEmpty()
        val carReached = links.getOrNull(1)?.level == LinkLevel.OK
        Row(
            Modifier.weight(1f).fillMaxHeight().background(if (carReached) Green.copy(alpha = 0.22f) else CardBackground, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Yaris", color = if (carReached) Green else Grey, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            links.forEach { link ->
                val (color, symbol) = when (link.level) {
                    LinkLevel.OK -> Green to "●"
                    LinkLevel.WARNING -> Amber to "●"
                    LinkLevel.ERROR -> Red to "✖"
                    LinkLevel.IDLE -> Grey to "○"
                }
                Text("$symbol ${link.label}", color = color, fontSize = 12.sp)
            }
            if (state != null) Text(String.format(Locale.ITALY, "freno %.1f/s", brakeReadsPerSecond(state, now)), color = Grey, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            SourceChip(state?.source)
        }
        TextButton(
            onClick = onDebug,
            modifier = Modifier.fillMaxHeight().background(CardBackground, RoundedCornerShape(10.dp)).semantics { contentDescription = "Modalità debug e prove" },
        ) { Text("🔧", fontSize = 18.sp) }
        if (running) {
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2626), contentColor = Color(0xFFFFD6D6))) { Text("■ Ferma") }
        } else {
            Button(onClick = onStart) { Text("▶ Avvia") }
        }
    }
}

@Composable
private fun SourceChip(source: SessionSource?) {
    val (text, color) = when (source) {
        null -> "FERMO" to Grey
        is SessionSource.Live -> "LIVE" to Green
        is SessionSource.Replay -> (if (source.simulated) "SIMULATA" else "REPLAY") to Amber
    }
    Text(
        text, color = color, fontSize = 11.sp, maxLines = 1,
        modifier = Modifier.background(Color(0xFF2A2F36), RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
