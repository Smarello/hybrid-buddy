# DECISIONS.md — Decisioni tecniche

| # | Data | Decisione | Motivazione |
|---|---|---|---|
| D-001 | 2026-11-05 | Primo incremento = fasi 1+2 complete | Scelta del proprietario: base solida e connessione affidabile prima della ricerca dei segnali. |
| D-002 | 2026-11-05 | Due moduli Gradle: `core` (Kotlin/JVM puro: obdlink, signals, interpretation, session) e `app` (Android) | I quattro componenti sono separati per package; tutto il non-Android è testabile senza dispositivo. Più moduli non servono ancora. |
| D-003 | 2026-11-05 | Bluetooth Classic SPP/RFCOMM verso un LX già associato da Android | L'LX è un dispositivo Bluetooth Classic; l'associazione dalle impostazioni evita permessi di scansione e posizione. |
| D-004 | 2026-11-05 | Inizializzazione `ATZ, ATE0, ATL0, ATH1, ATSP0, ATI, STI, 0100, ATDP`; spazi lasciati attivi | `ATH1` mostra quale centralina risponde (serve a non confondere termico e sistema ibrido). Spazi attivi: log leggibili e parsing semplice. Fonte: OBDLink FRPM. Diverge dall'ipotesi iniziale `ATS0`, che era un dettaglio reversibile. |
| D-005 | 2026-11-05 | Timeout: ATZ 5 s, AT 2 s, primo OBD 10 s (ricerca protocollo), OBD 3 s; 3 timeout consecutivi = connessione persa; riconnessione con attesa 1→10 s | L'adattatore muto con socket aperto va trattato come perso; la ricerca del protocollo con `ATSP0` richiede secondi. Valori da tarare sull'auto. |
| D-006 | 2026-11-05 | Registrazioni JSONL (`session`, `exchange`, `event`) e replay attraverso la stessa pipeline (`ReplayConnector` risponde come l'adattatore) | Il replay esercita protocollo, timeout e riconnessioni reali; le registrazioni reali diventano test. |
| D-007 | 2026-11-05 | Compose BOM 2026.06.01 (Compose UI 1.11.x), activity-compose 1.13.0, compileSdk/targetSdk 36, minSdk 31 | Compose UI 1.12 e core 1.19 richiedono compileSdk 37, non presente nel toolchain fissato. Android 16 = API 36. |
| D-008 | 2026-11-05 | Scadenza dei valori a 2 s (`STALE_AFTER_MS`) | Un valore vecchio non deve sembrare attuale. Da tarare sull'auto. |
| D-009 | 2026-11-05 | Acquisizione live in foreground service `connectedDevice` + wake lock parziale; replay senza servizio | Continuità a schermo spento; il replay non ha bisogno di girare in background. |
| D-010 | 2026-11-05 | Esportazione: menu di condivisione Android dell'ultima sessione live (`text/plain`, FileProvider) | Azione esplicita; il file contiene solo intestazione, scambi ed eventi (niente VIN, MAC, nome telefono). |
| D-011 | 2026-11-05 | ELM327-emulator (https://github.com/Ircama/ELM327-emulator) solo come candidato per prove manuali | I test automatici usano `InMemoryAdapterLink`, più veloce e deterministico; l'emulatore richiederebbe un trasporto TCP non ancora necessario. |
