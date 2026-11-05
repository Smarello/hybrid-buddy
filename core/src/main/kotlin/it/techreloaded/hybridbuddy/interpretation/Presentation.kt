package it.techreloaded.hybridbuddy.interpretation

import it.techreloaded.hybridbuddy.obdlink.ElmConnection
import it.techreloaded.hybridbuddy.session.PhoneLinkState
import it.techreloaded.hybridbuddy.session.SessionState
import it.techreloaded.hybridbuddy.signals.SignalDefinition
import java.util.Locale

/** A value older than this is no longer shown as current. To be tuned on the car. */
const val STALE_AFTER_MS = 2_000L
private const val RATE_WINDOW_MS = 5_000L
private val ITALIAN = Locale.ITALY

sealed interface SignalPresentation {
    data class Available(val text: String, val ageMs: Long) : SignalPresentation
    data class Unavailable(val reason: String) : SignalPresentation
}

/** "Non disponibile" is never shown as zero nor as the last known value. */
fun presentSignal(signal: SignalDefinition, state: SessionState, nowEpochMs: Long): SignalPresentation {
    val reading = state.readings[signal.id] ?: return SignalPresentation.Unavailable("in attesa")
    reading.failure?.let { return SignalPresentation.Unavailable(failureLabel(it)) }
    val age = nowEpochMs - reading.atEpochMs
    if (age > STALE_AFTER_MS || reading.value == null) return SignalPresentation.Unavailable("non aggiornato")
    val number = String.format(ITALIAN, "%,.${signal.decimals}f", reading.value)
    return SignalPresentation.Available("$number ${signal.unit}", age)
}

fun failureLabel(error: String): String = when (error) {
    ElmConnection.TIMEOUT -> "timeout"
    ElmConnection.DISCONNECTED -> "disconnesso"
    else -> error
}

fun formatAge(ageMs: Long): String = String.format(ITALIAN, "%.1f s fa", ageMs / 1000.0)

enum class LinkLevel { IDLE, OK, WARNING, ERROR }

data class ChainLink(val level: LinkLevel, val label: String)

/** Telefono → LX → Auto → Segnali, each with a level and a text label (colour is never the only cue). */
data class ConnectionChain(val phoneToAdapter: ChainLink, val adapterToCar: ChainLink, val signals: ChainLink)

fun connectionChain(state: SessionState, signals: List<SignalDefinition>, nowEpochMs: Long): ConnectionChain {
    val phone = when (state.phoneLink) {
        PhoneLinkState.CONNECTING -> ChainLink(LinkLevel.WARNING, "connessione…")
        PhoneLinkState.CONNECTED -> ChainLink(LinkLevel.OK, "collegato")
        PhoneLinkState.RECONNECTING -> ChainLink(LinkLevel.WARNING, "riconnessione…")
        PhoneLinkState.FINISHED -> ChainLink(LinkLevel.IDLE, state.phoneLinkDetail ?: "fermato")
    }
    if (state.phoneLink != PhoneLinkState.CONNECTED) {
        return ConnectionChain(phone, ChainLink(LinkLevel.IDLE, "—"), ChainLink(LinkLevel.IDLE, "—"))
    }

    val obdExchanges = state.recentExchanges.filter { it.command.startsWith("01") }
    val lastObdSuccess = obdExchanges.lastOrNull { it.error == null }
    val car = when {
        obdExchanges.isEmpty() -> ChainLink(LinkLevel.WARNING, "verifica…")
        lastObdSuccess != null && nowEpochMs - lastObdSuccess.startedAtEpochMs <= STALE_AFTER_MS ->
            ChainLink(LinkLevel.OK, "auto raggiunta")
        obdExchanges.last().error != null ->
            ChainLink(LinkLevel.ERROR, "auto non risponde (${failureLabel(obdExchanges.last().error!!)})")
        else -> ChainLink(LinkLevel.WARNING, "nessuna risposta recente")
    }

    val fresh = signals.count { presentSignal(it, state, nowEpochMs) is SignalPresentation.Available }
    val rate = state.recentExchanges.count { it.error == null && nowEpochMs - it.startedAtEpochMs <= RATE_WINDOW_MS } /
        (RATE_WINDOW_MS / 1000.0)
    val level = when (fresh) {
        signals.size -> LinkLevel.OK
        0 -> LinkLevel.ERROR
        else -> LinkLevel.WARNING
    }
    return ConnectionChain(phone, car, ChainLink(level, String.format(ITALIAN, "%d/%d · %.1f risposte/s", fresh, signals.size, rate)))
}
