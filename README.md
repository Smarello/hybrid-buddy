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

## Cruscotto (fasi 4–5, prove in marcia)
Sola lettura, come sempre. La schermata principale (in landscape) ha la struttura del cruscotto di Hybrid Assistant
(manuale utente v1.1, §8.1): stessi riquadri, nelle stesse posizioni. Quelli grigi con «in arrivo» (consumo, temperature,
batteria HV, viaggio, % di carica, Check) aspettano segnali non ancora letti e non mostrano mai valori finti. Quelli attivi:
- **Velocità** (km/h, da `010D`).
- **Freno**: un disco con un anello che si riempie (vuoto e grigio = nessuna frenata; verde = solo rigenerativa, anche al
  rilascio dell'acceleratore, in proporzione al limite di carica; rosso = interviene l'attrito, in proporzione alla quota
  d'attrito). Sotto 15 km/h l'attrito per fermarsi non viene contato. I 5 riquadri dello storico sono grigi (in arrivo).
- **EV**: giallo quando il termico è sotto gli 800 giri (solo elettrico), grigio quando è in uso.
- **Powermeter**: barra del **termico** (dal basso, fondo scala 67 kW) e della **batteria** (dal centro: in alto scarica,
  in basso carica in verde, fondo scala 30 kW, tacca verde = limite di carica), con i kW sotto (batteria negativa = in carica).
- **Termico**: giri, grigio spento / verde acceso / giallo trascinato senza carburante.
- Barra in basso: 📱 — LX — 🚗 con un segmento per collegamento (verde ok, giallo in corso o parziale, rosso fallito,
  grigio non attivo; un tocco mostra il dettaglio per centralina), bollino LIVE / SIMULATA / REPLAY, 🔧 = **Debug e
  prove**, **▶** / **■**.

Quasi niente testo: un dato mancante mostra un **triangolo giallo** al posto del valore, e un tocco sul triangolo dice la
causa (es. «7D2: rifiuta 221067 (7F 22 31)»). Mai zero né l'ultimo valore. Lo schermo resta acceso.
1. LX inserito. Apri l'app: si collega da sola all'LX già scelto (la prima volta **▶** chiede quale).
2. Già da fermo in READY i due segmenti in basso devono essere verdi; se il secondo è giallo, tocca l'icona per vedere
   quale centralina non risponde.
3. Guida normalmente: rilasci dell'acceleratore (atteso «Rilascio» verde) e frenate **sopra i 15 km/h**, leggere (verde)
   e decise. Annota a voce o a memoria le frenate e cosa ha mostrato l'anello.
4. **■ Ferma** → 🔧 → **Esporta guida** → condividi il file `guida-….jsonl` con lo sviluppatore.

## Debug e prove (🔧)
Catena Telefono → LX → Auto → Segnali, parametri OBD standard, ultimi scambi, **Dispositivo**, **Avvia acquisizione** (sessione
di base), **Esporta sessione** / **Esporta guida**, **Esplora freni**, **Guida simulata (SIMULATA)** (auto inventata, bollino
SIMULATA: non dimostra nulla sulla Yaris) e **Apri registrazione** → "Esempio SIMULATO" o "Da file…" per una registrazione
esportata (una `guida-…` si apre nel cruscotto). In replay il bollino lo segnala sempre.

## Limiti conosciuti
- Guida: i segnali vengono da Hybrid Assistant (Yaris MY20); dalla prima guida (2026-11-05) 7D2, 747 e 700 rispondono e la
  corrente della batteria è confermata (D-024). Il significato della coppia termica e del pedale acceleratore (`221060`,
  mai letto sulla Yaris) restano ipotesi; «Rilascio» dipende da quel pedale (D-026).
- I parametri di base usano risposte CAN 11-bit a frame singolo; l'esplorazione ricompone anche le risposte multi-frame.
- L'esplorazione non si riproduce in replay: il file si analizza fuori dall'app.
- Il riepilogo "cambiano col pedale" è indicativo (D-016).
- Timeout, scadenza (2 s) e attese di riconnessione sono valori iniziali da tarare sull'auto.
- Se si preme Ferma mentre il telefono sta ancora cercando di collegarsi all'LX, l'arresto può richiedere ~12 s.
