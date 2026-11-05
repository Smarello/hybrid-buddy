# AGENTS.md — Hybrid Buddy

App Android (Kotlin + Jetpack Compose) che legge, in sola lettura, i dati di una **Toyota Yaris Hybrid 115 (2026)**
tramite **OBDLink LX** (Bluetooth) su **Samsung Galaxy A34 / Android 16**. Obiettivo MVP e fasi: `PLAN.md`.

## Prima di lavorare
Leggi `STATUS.md` (ultimo risultato verificato, problema aperto, prossimo passo), poi `PLAN.md`, `SIGNALS.md`, `DECISIONS.md`.
Ciclo per ogni incremento: leggere lo stato → modifica circoscritta → build → test pertinenti → APK se serve una prova
sull'auto → aggiornare `STATUS.md` (e `SIGNALS.md` / `DECISIONS.md` / `PLAN.md` se cambiano).

## Comandi
Il toolchain (JDK 21, Gradle 9.8.2, Android SDK 36, build-tools 36.0.0) è fissato in `scripts/archetipo/toolchain.sh`
e installato in `~/.cache/archetipo/toolchains`. Ogni comando Gradle passa da lì:

```bash
scripts/archetipo/toolchain.sh install                                   # una volta per macchina
scripts/archetipo/toolchain.sh run ./gradlew :core:test                  # test JVM (protocollo, segnali, sessione, replay)
scripts/archetipo/toolchain.sh run ./gradlew assembleDebug               # APK: app/build/outputs/apk/debug/app-debug.apk
scripts/archetipo/toolchain.sh run adb install -r app/build/outputs/apk/debug/app-debug.apk
python3 tools/generate_sample_recording.py                               # rigenera la registrazione SIMULATA di esempio
```

## Struttura
| Componente | Dove | Note |
|---|---|---|
| Collegamento OBDLink | `core/.../obdlink` | `AdapterLink` (byte), `ElmConnection` (un comando alla volta, prompt `>`, timeout, errori) |
| Definizioni dei segnali | `core/.../signals` | `SignalDefinition`, `BaseSignals` (PID OBD-II standard), parser mode 01 |
| Interpretazione | `core/.../interpretation` | valore / "Non disponibile", scadenza dei dati, catena Telefono→LX→Auto→Segnali |
| Sessione e registrazioni | `core/.../session` | `AcquisitionSession` (connessione, init, polling, riconnessione), JSONL, `ReplayConnector`, mascheramento VIN |
| Esplorazione segnali | `core/.../exploration` | `BrakeExploration` (mappa mode 21/22 per centralina, fasi del pedale, riepilogo); risposte multi-frame con `IsoTp` |
| Interfaccia e Android | `app/` | Compose, foreground service, Bluetooth SPP, esportazione via FileProvider |

`core` è Kotlin/JVM puro: tutto ciò che non tocca Android si testa lì, senza telefono né auto.

## Vincoli
- **Sola lettura**: solo comandi AT/ST di configurazione dell'adattatore e richieste diagnostiche di lettura. Mai comandi
  che scrivono, cancellano errori, attivano attuatori o modificano centraline. `ReadOnlyCommands` (lista dei consentiti)
  lo impone in `ElmConnection`: un nuovo comando va aggiunto lì, con test e decisione in `DECISIONS.md`.
- **Mai richiedere il VIN** (`0902`) né registrare indirizzo MAC o nome del telefono: un test lo verifica.
- Un dato mancante, in errore o scaduto si mostra come **"Non disponibile"** con la causa, mai come zero o ultimo valore.
- Dati simulati sempre marcati (`"source":"simulated"` nell'intestazione, bollino "SIMULATA" in app). Non dimostrano
  nulla sulla Yaris.
- `SIGNALS.md`: ogni segnale ha centralina, richiesta, formato, conversione, unità, fonte, prove e stato
  **candidato / risposta ricevuta / validato**. Un nome come "Motor torque" non implica il motore termico; una coppia
  richiesta non è una coppia effettiva.
- Le librerie AndroidX devono compilare con `compileSdk 36` (vedi `DECISIONS.md`, D-007): controlla `minCompileSdk`
  prima di aggiornarle.

## Regole per l'agente
- Ogni consegna dichiara: cosa è verificato automaticamente, cosa sull'auto, cosa resta ipotetico.
- Le registrazioni reali ricevute dal proprietario diventano casi di test permanenti (in `core/src/test/resources/`).
- Raggruppa le richieste di prove sull'auto (connessione; segnali essenziali; due indicatori; collaudo finale).
- Registra le decisioni in `DECISIONS.md` con la motivazione e le fonti.
