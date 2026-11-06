# STATUS.md

**Aggiornato:** 2026-11-06 · versione app 0.4.1

## Ultimo risultato verificato
- **0.4.1 — triangoli solo per errori, frenata classificata come Hybrid Assistant** (D-028, D-029), dopo la guida del
  proprietario con la 0.4.0 (troppi triangoli da fermo; sfiorando il freno l'anello andava in rosso).
  - **Triangolo giallo solo** se la lettura è fallita (rifiuto, risposta non valida, timeout, disconnessione). Dato in
    attesa o in ritardo: trattino grigio, causa al tocco. Scadenza dei dati da 2 a 3 s.
  - **M e R troncati all'intero come HA**: sulla prima guida reale le frenate solo rigenerative passano da 22 a 31; prima
    9 letture a frazioni di Nm l'una dall'altra davano «attrito 0 %» in rosso.
  - **M e R in una sola lettura** `2210671069`, come il campione unico di HA; se 7D2 la rifiuta, si torna da soli alle
    due letture separate.
  - Disegno dell'anello e «Rilascio» invariati.
  - Verificato automaticamente: 57 test JVM, tra cui troncamento, errore o attesa, richiesta doppia con ripiego (auto
    simulata che la rifiuta) e replay della guida 0.3.0 con il nuovo calendario. Build e lint con i controlli
    dell'ambiente.
  - **Non verificato sull'auto**: se 7D2 accetta la richiesta a due DID, se da fermo i triangoli spariscono, se le frenate
    leggere restano verdi.
- **0.4.0 — schermata principale = cruscotto di Hybrid Assistant** (D-027): stessa griglia della figura 8.1 del manuale
  utente (`docs/HA-User-Guide - v1.1r - 20170204.pdf`, §8.1), in landscape. Attivi: velocità, freno (anello della 0.3.x),
  EV, powermeter a barre (termico, batteria con tacca del limite di carica), termico (giri e stato: spento / acceso /
  trascinato, nuovo `DriveState.engineState` con test). Grigi «in arrivo»: consumo, temperature, batteria HV (temperatura,
  ventola, % di carica), viaggio, storico frenate e consumi, Check. La vecchia schermata principale è «Debug e prove» (🔧).
  Al lancio l'app si collega da sola come HA. Quasi niente testo sui riquadri attivi (richiesta del proprietario): un dato
  mancante è un triangolo giallo con la causa in un tooltip al tocco; in basso 📱 — LX — 🚗 con un segmento colorato per
  collegamento e il dettaglio per centralina solo nel tooltip. Verificato automaticamente (52 test JVM), build, APK installato
  sul Galaxy A34 e controllato con screenshot (2026-11-06): a riposo senza LX, con la guida simulata, tooltip del triangolo
  e del collegamento. **Non verificato**: tutto ciò che la 0.3.2 lasciava da provare sull'auto (sotto).
- **0.3.2 — stati di guida e «Rilascio»** (D-026): `DriveState` deriva a ogni valutazione gli stati di `carstate` di Hybrid
  Assistant (EV, termico trascinato o spento, in movimento, frenata, attrito, rilascio) dai segnali freschi; l'anello
  mostra in verde anche la rigenerazione al rilascio dell'acceleratore (potenza batteria < 0 con `221060` = 0, velocità
  ≥ 2 km/h, termico spento), con la stessa scala della frenata rigenerativa. Nuovo segnale `221060` su 7D2 a ogni giro.
  Verificato automaticamente (51 test JVM): rilascio nel simulatore e per stato (termico trascinato sì, acceso no,
  pedale premuto no), «Non disponibile» con il primo segnale mancante, registrazione reale rianalizzata. Build, APK.
- **Prima guida con la 0.3.0 (2026-11-05, 232 s)**: registrazioni `2026-11-05-a34-guida-0.3.0.jsonl` e
  `…-sessione-0.3.0.jsonl` ora casi di test. **7D2, 747 e 700 rispondono alle letture dirette** `221067`/`221069`,
  `221F9A`/`221807`, `2210C2` (7D2 rifiuta solo `221807`). Tre difetti trovati e corretti nella **0.3.1**:
  1. `010C`/`010D` indirizzati fisicamente a 7E0: 44/44 `NO DATA` → velocità mai disponibile, attrito mai classificato
     («Non disponibile: velocità»), catena «LX→Auto» fallita. Ora richiesta funzionale 7DF filtrata su 7E8 (D-023), che la
     sessione di base di 27 s prima dimostra.
  2. Corrente HV decodificata con offset 32768 → potenza batteria ±580 kW. Ora complemento a due (D-024): −30…+27 kW.
  3. Coppia termica letta ogni 1,2–2,2 s con scadenza a 2 s → «700» spesso assente. Calendario a due giri (D-025).
  Sulla registrazione reale: 22 frenate rigenerative col pedale (intensità massima 40 %), 94 con attrito ma senza
  velocità, 506 letture a pedale rilasciato indecidibili (manca la velocità, e la 0.3.0 non leggeva `221060`).
- **Non verificato**: la 0.3.2 sull'auto. In particolare: 7DF dentro la Guida, risposta di 7D2 a `221060` (se la rifiuta,
  a pedale rilasciato l'anello dice «Non disponibile: rilascio: acceleratore: …» e si decide un'alternativa, es. il solo
  termico spento), «Rilascio» al rilascio dell'acceleratore, anello rosso con la velocità, riga delle centraline stabile.

## Problema aperto
**Rilascio grigio (0.4.0)**: nella prima guida, con pedali rilasciati e batteria in carica, la coppia termica `2210C2` era
positiva in 186 campioni su 253 (termico acceso, forse in riscaldamento). Per HA, come per l'app, quello non è
«rilascio» e l'anello resta grigio. Coerente con HA, ma non dimostrato: serve la registrazione della 0.4.x (`221060` mai
verificato). Nelle frenate gli «attriti» grandi al rilascio del pedale potrebbero sparire con la lettura unica: da vedere.

Il rilascio dipende da `221060` (significato dedotto dall'uso in Hybrid Assistant, mai letto sulla Yaris) e dal termico
spento (giri < 800 o coppia `10C2` < 0): un termico in riscaldamento che carica la batteria in rilascio non conta come
rigenerazione, come in Hybrid Assistant. Nelle frenate col pedale la registrazione mostra spesso M = R (solo rigenerativa,
anche con barra CHG piena) e quote d'attrito piccole (M − R di pochi Nm): con la velocità disponibile l'anello andrà in
rosso in quei momenti; da confrontare col cruscotto.

## Prossimo passo
**Guida con la 0.4.1**: da fermo niente triangoli gialli con l'auto collegata; frenate leggere sopra i 15 km/h (attese
verdi); rilasci dell'acceleratore; esportare `guida-….jsonl`. Nella registrazione: risposta a `2210671069`.

Precedente (0.4.0):
**Seconda guida con la 0.4.0** (`README.md`, sezione "Cruscotto"): da fermo la barra in basso deve dare Auto verde; poi
rilasci dell'acceleratore (atteso «Rilascio» verde) e frenate leggere e decise sopra i 15 km/h; esportare `guida-….jsonl`.
