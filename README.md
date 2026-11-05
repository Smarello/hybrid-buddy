# Hybrid Buddy

Monitor ibrido per Toyota Yaris Hybrid 115 (2026) via OBDLink LX. Stato attuale: fasi 1–2 (diagnostica di connessione,
parametri OBD di base, registrazione ed esportazione) e, per la fase 3, l'esplorazione guidata dei segnali dei freni. Per sviluppatori e agenti: `AGENTS.md`.

## Installazione
1. Build: `scripts/archetipo/toolchain.sh run ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
2. Copia l'APK sul Galaxy A34 e installalo (consenti l'installazione da origine sconosciuta), oppure
   `scripts/archetipo/toolchain.sh run adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Associa l'OBDLink LX dalle impostazioni Bluetooth di Android (se non lo è già).

## Prova da fermo (fase 2)
1. Auto in READY o quadro acceso, LX inserito. Apri Hybrid Buddy → **Dispositivo** → scegli l'LX → **Avvia acquisizione**
   (concedi Bluetooth e notifiche).
2. Attendi che la catena sia verde: `Telefono → LX` collegato, `LX → Auto` auto raggiunta, `Segnali 5/5`.
3. Spegni lo schermo per 1 minuto, riaccendi e riapri l'app: i valori devono essere aggiornati (età < 1 s).
4. Stacca l'LX per ~10 s e reinseriscilo: la catena passa a "riconnessione…", i valori a "Non disponibile", poi
   l'acquisizione riprende da sola. La notifica resta presente.
5. Spegni il quadro: `LX → Auto` deve indicare "auto non risponde".
6. **Ferma** (o "Termina" dalla notifica) → **Esporta sessione** → condividi il file `.jsonl` con lo sviluppatore.

Annota cosa hai visto ai punti 2–5: insieme alla registrazione è l'evidenza della prova.

## Esplora freni (fase 3, ~10–15 minuti da fermo)
Sola lettura: l'app può inviare solo richieste di lettura (mode 01/21/22); ogni altro comando è bloccato nel codice.
1. Auto in **READY**, leva in **P**, freno di stazionamento tirato, piede lontano dal pedale. LX inserito e già scelto
   con **Dispositivo**.
2. Diagnostica → **Esplora freni** → **▶ Inizia**. Lo schermo resta acceso; l'esplorazione continua anche se si spegne.
3. **Mappa** (5–10 minuti, avanzamento e tempo stimato a schermo): non toccare il pedale.
4. **Pedale**: finita la mappa l'app **aspetta**: quando sei al posto di guida premi **▶ Inizia il pedale**, poi segui
   le istruzioni con il conto alla rovescia (15 s ciascuna): piede lontano → premi leggermente → premi a fondo →
   rilascia. Tieni il pedale fermo fino al cambio di istruzione.
5. **Fine** → **Esporta esplorazione** → condividi il file `esplorazione-freni-….jsonl` con lo sviluppatore.
   **Interrompi** è sempre possibile: quanto registrato resta esportabile.
6. Per riprovare: **↻ Ripeti solo il pedale** usa la mappa completata più recente salvata sul telefono (anche quelle
   della 0.2.0), circa 1 minuto; **Rifai tutto** rifà anche la mappa.

## Replay
**Apri registrazione** → "Esempio SIMULATO" (dati inventati, non dell'auto) o "Da file…" per una sessione esportata.
In replay il bollino in alto e la riga "Sorgente: … (non è l'auto)" lo segnalano sempre.

## Limiti conosciuti
- Mostra solo PID OBD-II standard: potenze e frenata arrivano nelle fasi 3–5.
- I parametri di base usano risposte CAN 11-bit a frame singolo; l'esplorazione ricompone anche le risposte multi-frame.
- L'esplorazione non si riproduce in replay: il file si analizza fuori dall'app.
- Il riepilogo "cambiano col pedale" è indicativo (D-016).
- Timeout, scadenza (2 s) e attese di riconnessione sono valori iniziali da tarare sull'auto.
- Se si preme Ferma mentre il telefono sta ancora cercando di collegarsi all'LX, l'arresto può richiedere ~12 s.
