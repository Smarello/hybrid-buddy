package it.techreloaded.hybridbuddy.obdlink

/**
 * Allow-list of everything the app may send to the adapter: the adapter setup it uses and the diagnostic
 * read services 01 (OBD-II), 21 and 22 (Toyota). Anything else is refused before it reaches the car, so a
 * write, reset, DTC clear, diagnostic session or security access cannot be sent, not even by mistake.
 * Data identifiers F1xx (VIN and ECU identification) are refused too. Service 22 accepts up to two DIDs in one request
 * (ISO 14229 ReadDataByIdentifier), still a pure read: M and R of the brake read at the same instant (D-028).
 */
object ReadOnlyCommands {
    private val allowed = Regex(
        listOf(
            "ATZ", "ATE0", "ATL0", "ATH1", "ATSP0", "ATI", "ATDP", "ATRV", "STI",
            "ATSH [0-9A-F]{3}", "ATCRA [0-9A-F]{3}", "ATFCSH [0-9A-F]{3}", "ATFCSD 30 00 00", "ATFCSM 1",
            "01[0-9A-F]{2}", "21[0-9A-F]{2}", "22(?:(?!F1)[0-9A-F]{4}){1,2}",
        ).joinToString("|", "^(", ")$"),
    )

    fun isAllowed(command: String): Boolean = allowed.matches(command)

    fun requireAllowed(command: String) = require(isAllowed(command)) { "comando non consentito (sola lettura): $command" }
}
