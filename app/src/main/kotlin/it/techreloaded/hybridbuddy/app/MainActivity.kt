package it.techreloaded.hybridbuddy.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import it.techreloaded.hybridbuddy.interpretation.ChainLink
import it.techreloaded.hybridbuddy.interpretation.LinkLevel
import it.techreloaded.hybridbuddy.interpretation.SignalPresentation
import it.techreloaded.hybridbuddy.interpretation.connectionChain
import it.techreloaded.hybridbuddy.interpretation.failureLabel
import it.techreloaded.hybridbuddy.interpretation.formatAge
import it.techreloaded.hybridbuddy.interpretation.presentSignal
import it.techreloaded.hybridbuddy.obdlink.Exchange
import it.techreloaded.hybridbuddy.session.AcquisitionSession
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.Recording
import it.techreloaded.hybridbuddy.session.SessionSource
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.signals.BaseSignals
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val Green = Color(0xFF5BD27A)
internal val Amber = Color(0xFFF2B84B)
internal val Red = Color(0xFFEF6461)
internal val Grey = Color(0xFF8A9099)
internal val CardBackground = Color(0xFF1B1E23)
private const val SAMPLE_ASSET = "sample-simulated.jsonl"
private const val VISIBLE_EXCHANGES = 8
private val exchangeTime = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private enum class Screen { DIAGNOSTICS, EXPLORATION, DRIVE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var screen by rememberSaveable {
                        mutableStateOf(
                            when {
                                AcquisitionController.isExplorationRunning -> Screen.EXPLORATION
                                AcquisitionController.isRunning && AcquisitionController.isDrive(AcquisitionController.session.value) -> Screen.DRIVE
                                else -> Screen.DIAGNOSTICS
                            },
                        )
                    }
                    if (screen != Screen.DIAGNOSTICS) BackHandler { screen = Screen.DIAGNOSTICS }
                    when (screen) {
                        Screen.EXPLORATION -> BrakeExplorationScreen(onBack = { screen = Screen.DIAGNOSTICS })
                        Screen.DRIVE -> DriveScreen(onBack = { screen = Screen.DIAGNOSTICS })
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(
                            onExploreBrakes = { screen = Screen.EXPLORATION },
                            onDrive = { screen = Screen.DRIVE },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsScreen(onExploreBrakes: () -> Unit, onDrive: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("adapter", Context.MODE_PRIVATE) }
    var adapterAddress by remember { mutableStateOf(preferences.getString("address", null)) }
    var adapterName by remember { mutableStateOf(preferences.getString("name", null)) }
    var choosingDevice by remember { mutableStateOf(false) }
    var startAfterChoice by remember { mutableStateOf(false) }

    val session by AcquisitionController.session.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    fun startLive() {
        val address = adapterAddress
        if (address == null) {
            startAfterChoice = true
            choosingDevice = true
        } else {
            AcquisitionService.start(context, address, adapterName ?: "OBDLink")
        }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            if (startAfterChoice && !choosingDevice) startLive() else choosingDevice = true
        } else {
            Toast.makeText(context, "Serve il permesso Bluetooth per collegarsi all'LX", Toast.LENGTH_LONG).show()
        }
    }
    fun withBluetoothPermission(action: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    val openRecording = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startReplay(context, displayName(context, uri)) { context.contentResolver.openInputStream(uri)!! }
    }

    val current = session
    val state = current?.state?.collectAsState()?.value
    val running = state != null && state.phoneLink != PhoneLinkState.FINISHED

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Header(state?.source)
        ChainCard(current, state, now)
        DetailLine(state)
        SectionLabel("Parametri di base (OBD standard)")
        BaseSignals.all.forEach { signal ->
            val shown = if (state == null) SignalPresentation.Unavailable("—") else presentSignal(signal, state, now)
            ParameterRow(signal.label, shown)
        }
        SectionLabel("Ultimi scambi")
        ExchangeLog(state?.recentExchanges.orEmpty().takeLast(VISIBLE_EXCHANGES))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) {
                Button(
                    onClick = {
                        if (state.source is SessionSource.Live) AcquisitionService.stop(context) else AcquisitionController.stop()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A2626), contentColor = Color(0xFFFFD6D6)),
                ) { Text("■ Ferma") }
            } else {
                Button(onClick = { startAfterChoice = true; withBluetoothPermission(::startLive) }) { Text("▶ Avvia acquisizione") }
            }
            OutlinedButton(onClick = { exportLatest(context, AcquisitionController.SESSION_PREFIX) }) { Text("Esporta sessione") }
            OutlinedButton(onClick = { startAfterChoice = false; withBluetoothPermission { choosingDevice = true } }) {
                Text("Dispositivo")
            }
            Button(onClick = onDrive) { Text("Guida") }
            OutlinedButton(onClick = onExploreBrakes) { Text("Esplora freni") }
            ReplayMenu(
                onSample = { startReplay(context, "$SAMPLE_ASSET (SIMULATA)") { context.assets.open(SAMPLE_ASSET) } },
                onFile = { openRecording.launch(arrayOf("*/*")) },
            )
        }
        Text(
            "Adattatore: ${adapterName ?: "nessuno scelto"}",
            color = Grey, fontSize = 12.sp,
        )
    }

    if (choosingDevice) {
        DevicePicker(
            onChosen = { address, name ->
                preferences.edit().putString("address", address).putString("name", name).apply()
                adapterAddress = address
                adapterName = name
                choosingDevice = false
                if (startAfterChoice) startLive()
            },
            onDismiss = { choosingDevice = false },
        )
    }
}

@Composable
private fun Header(source: SessionSource?) {
    val (chip, chipColor) = when (source) {
        null -> "NESSUNA SORGENTE" to Grey
        is SessionSource.Live -> "LIVE · ${source.adapterName}" to Green
        is SessionSource.Replay -> (if (source.simulated) "REPLAY · SIMULATA" else "REPLAY · dati registrati") to Amber
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Hybrid Buddy", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        Text(
            chip, color = chipColor, fontSize = 12.sp,
            modifier = Modifier.background(Color(0xFF2A2F36), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ChainCard(session: AcquisitionSession?, state: SessionState?, now: Long) {
    val idle = ChainLink(LinkLevel.IDLE, "non avviato")
    val chain = if (session == null || state == null) null else connectionChain(state, session.signals, now)
    Row(
        Modifier.fillMaxWidth().background(CardBackground, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ChainNode("Telefono → LX", chain?.phoneToAdapter ?: idle, Modifier.weight(1f))
        ChainNode("LX → Auto", chain?.adapterToCar ?: ChainLink(LinkLevel.IDLE, "—"), Modifier.weight(1f))
        ChainNode("Segnali", chain?.signals ?: ChainLink(LinkLevel.IDLE, "—"), Modifier.weight(1f))
    }
}

@Composable
private fun ChainNode(title: String, link: ChainLink, modifier: Modifier) {
    val (color, symbol) = when (link.level) {
        LinkLevel.OK -> Green to "✔"
        LinkLevel.WARNING -> Amber to "!"
        LinkLevel.ERROR -> Red to "✖"
        LinkLevel.IDLE -> Grey to "–"
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(36.dp).background(color.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Text(symbol, color = color, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        Text(title, fontSize = 12.sp, textAlign = TextAlign.Center)
        Text(link.label, fontSize = 11.sp, color = color, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DetailLine(state: SessionState?) {
    if (state == null) {
        Text("Scegli l'LX e avvia l'acquisizione, oppure apri una registrazione.", color = Grey, fontSize = 12.sp)
        return
    }
    val parts = listOfNotNull(state.adapterIdentity, state.protocol, state.phoneLinkDetail?.takeIf { state.phoneLink != PhoneLinkState.CONNECTED })
    Column {
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Grey, fontSize = 12.sp)
        val source = state.source
        if (source is SessionSource.Replay) {
            Text("Sorgente: ${source.fileName} (non è l'auto)", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(text.uppercase(), color = Grey, fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ParameterRow(label: String, shown: SignalPresentation) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        when (shown) {
            is SignalPresentation.Available -> {
                Text(shown.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(formatAge(shown.ageMs), color = Grey, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
            }
            is SignalPresentation.Unavailable -> {
                Text("Non disponibile", color = Grey, fontSize = 14.sp)
                Text(shown.reason, color = Red, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
            }
        }
    }
    HorizontalDivider(color = Color(0xFF23272D))
}

@Composable
internal fun ExchangeLog(exchanges: List<Exchange>) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF0C0D0F), RoundedCornerShape(8.dp)).padding(8.dp)) {
        if (exchanges.isEmpty()) Text("Nessuno scambio", color = Grey, fontSize = 11.sp)
        exchanges.forEach { exchange ->
            val answer = exchange.error?.let { "✖ ${failureLabel(it).uppercase()}" } ?: ("← " + exchange.lines.joinToString(" | "))
            Text(
                "${exchangeTime.format(Instant.ofEpochMilli(exchange.startedAtEpochMs))} → ${exchange.command.padEnd(5)}" +
                    "${exchange.durationMs.toString().padStart(5)} ms $answer",
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = if (exchange.error == null) Color(0xFFAAAABB) else Red,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReplayMenu(onSample: () -> Unit, onFile: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text("Apri registrazione") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Esempio SIMULATO (non è l'auto)") }, onClick = { open = false; onSample() })
            DropdownMenuItem(text = { Text("Da file…") }, onClick = { open = false; onFile() })
        }
    }
}

@SuppressLint("MissingPermission") // only shown after BLUETOOTH_CONNECT is granted
@Composable
private fun DevicePicker(onChosen: (address: String, name: String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty() }
            .getOrDefault(emptySet())
            .map { it.address to (it.name ?: "Senza nome") }
            .sortedByDescending { (_, name) -> name.contains("OBDLink", ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli l'adattatore") },
        text = {
            Column {
                if (devices.isEmpty()) Text("Nessun dispositivo associato. Associa l'OBDLink LX dalle impostazioni Bluetooth di Android.")
                devices.forEach { (address, name) ->
                    TextButton(onClick = { onChosen(address, name) }, modifier = Modifier.fillMaxWidth()) {
                        Text(name, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

internal fun startReplay(context: Context, name: String, open: () -> java.io.InputStream) {
    val parsed = runCatching { open().bufferedReader().useLines { Recording.parse(it.toList().asSequence()) } }
    parsed.onSuccess { (header, lines) ->
        AcquisitionService.stop(context)
        AcquisitionController.startReplay(name, header, lines)
    }.onFailure {
        Toast.makeText(context, "Registrazione non valida: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

internal fun displayName(context: Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "registrazione"

/** Shares the latest live recording of a kind through Android's share sheet, only on explicit user action. */
internal fun exportLatest(context: Context, prefix: String) {
    val file = AcquisitionController.latestRecording(context, prefix)
    if (file == null) {
        Toast.makeText(context, "Nessuna registrazione da esportare", Toast.LENGTH_LONG).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, "Hybrid Buddy · ${file.name}")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Esporta ${file.name}"))
}
