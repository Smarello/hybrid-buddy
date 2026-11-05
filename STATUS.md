# STATUS.md

**Aggiornato:** 2026-11-05 · versione app 0.3.2

## Ultimo risultato verificato
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
Il rilascio dipende da `221060` (significato dedotto dall'uso in Hybrid Assistant, mai letto sulla Yaris) e dal termico
spento (giri < 800 o coppia `10C2` < 0): un termico in riscaldamento che carica la batteria in rilascio non conta come
rigenerazione, come in Hybrid Assistant. Nelle frenate col pedale la registrazione mostra spesso M = R (solo rigenerativa,
anche con barra CHG piena) e quote d'attrito piccole (M − R di pochi Nm): con la velocità disponibile l'anello andrà in
rosso in quei momenti; da confrontare col cruscotto.

## Prossimo passo
**Seconda guida con la 0.3.2** (`README.md`, sezione "Guida"): da fermo la riga delle centraline deve dare Auto verde; poi
rilasci dell'acceleratore (atteso «Rilascio» verde) e frenate leggere e decise sopra i 15 km/h; esportare `guida-….jsonl`.
