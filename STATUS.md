# STATUS.md

**Aggiornato:** 2026-11-05 · versione app 0.1.1

## Ultimo risultato verificato
- **Prima prova sul Galaxy A34 (0.1.0)**: installazione e avvio riusciti; l'esportazione funziona. Ogni tentativo di
  connessione falliva con "permesso Bluetooth mancante" (registrazione:
  `core/src/test/resources/recordings/2026-11-05-a34-permesso-bluetooth.jsonl`). Causa: `cancelDiscovery()` richiede
  `BLUETOOTH_SCAN` da Android 12; corretto in 0.1.1 (D-012). **La correzione non è ancora provata sul telefono.**
- `./gradlew :core:test`: 24 test JVM superati (3 esecuzioni consecutive): conversioni PID, risposte spezzate, timeout,
  errori dell'adattatore, disconnessione a metà risposta, riconnessione, adattatore muto, dati mancanti/scaduti,
  catena di collegamento, registrazione → replay, assenza di VIN/MAC, esempio SIMULATO riproducibile.
- `./gradlew assembleDebug`: APK debug generato.
- **Non verificato**: rendering della schermata su un dispositivo (nessun emulatore/telefono collegato all'ambiente),
  connessione Bluetooth reale, comportamento della Yaris. Tutto ciò che riguarda l'auto è ipotetico.

## Problema aperto
Connessione reale all'LX mai riuscita finora: va ripetuta la prova da fermo con la 0.1.1 (vedi `README.md`).

## Prossimo passo
1. Il proprietario installa l'APK ed esegue la prova da fermo; esporta la registrazione e la consegna.
2. L'agente analizza la registrazione, la aggiunge ai test e aggiorna `SIGNALS.md` (risposte ricevute, centraline).
3. Fase 3: ricerca e prova dei segnali HV, potenza termica e frenata.
