# SIGNALS.md — Matrice dei segnali

Stati: **candidato** (definizione da fonte, mai provata) · **risposta ricevuta** (l'auto risponde, significato non
confermato) · **validato** (significato, unità e segno confermati con un riferimento indipendente).

Impostazioni adattatore usate: `ATZ`, `ATE0`, `ATL0`, `ATH1` (intestazioni CAN visibili, per sapere quale centralina
risponde), `ATSP0` (protocollo automatico), identificazione `ATI` / `STI`, protocollo letto con `ATDP`.

## Parametri di base (fase 2)

| Segnale | Centralina | Richiesta | Formato risposta | Conversione | Unità | Fonte | Prove | Stato |
|---|---|---|---|---|---|---|---|---|
| Giri motore termico | 7E8 (preferita se rispondono più centraline) | `010C` | `7E8 04 41 0C A B` | (A·256+B)/4 | rpm | SAE J1979 PID 0x0C | test JVM; prova reale 0.1.1 | risposta ricevuta |
| Velocità veicolo | 7E8 | `010D` | `7E8 03 41 0D A` | A | km/h | SAE J1979 PID 0x0D | test JVM; prova reale 0.1.1 (solo 0, da fermo) | risposta ricevuta |
| Temperatura refrigerante | 7E8 | `0105` | `7E8 03 41 05 A` | A−40 | °C | SAE J1979 PID 0x05 | test JVM; prova reale 0.1.1 | risposta ricevuta |
| Carico calcolato | 7E8 | `0104` | `7E8 03 41 04 A` | A·100/255 | % | SAE J1979 PID 0x04 | test JVM; prova reale 0.1.1 | risposta ricevuta — solo diagnostica, **non** usabile per la potenza termica |
| Tensione all'adattatore | OBDLink (non centralina) | `ATRV` | `14.1V` | numero prima di `V` | V | OBDLink FRPM | test JVM; prova reale 0.1.1 | risposta ricevuta |

Prova reale 2026-11-05 (Galaxy A34, APK 0.1.1, auto ferma al minimo; `core/src/test/resources/recordings/2026-11-05-a34-prova-da-fermo-0.1.1.jsonl`):
adattatore ELM327 v1.4b / STN1155 v5.6.19, protocollo ISO 15765-4 CAN 11/500. Al `0100` rispondono **4 centraline**:
`7E8` (bitmap `BE1FA013`), `7EA` e `7EE` (`981A8003`), `7EB` (`98188003`); tutte rispondono anche a `010C/0D/05/04`.
Valori osservati: giri 1250–1475 (7E8 un po' diversa dalle altre), velocità 0, refrigerante 23→43 °C, carico 16–49 %,
`ATRV` 14,0–14,5 V. Nessun errore in 138 s; ciclo dei 5 comandi ~0,43 s. Per i giri si sceglie `7E8`. Il ruolo di
7EA/7EB/7EE (es. ibrida = 7EA, richieste a 7E2) **non è confermato**. I parametri base passano a **risposta ricevuta**
(significato plausibile, nessun riferimento indipendente).

## Segnali essenziali dell'MVP (fase 3 — da cercare)

| Funzione | Dati necessari | Candidati | Stato |
|---|---|---|---|
| Potenza batteria | Tensione e corrente HV | Car Scanner li legge già: definizioni da individuare e documentare con la fonte | da cercare |
| Potenza termica | Potenza dalla centralina, oppure coppia **effettiva** del termico + giri | — | da cercare |
| Indicatore frenata | Segnali che distinguano rigenerazione e intervento dell'attrito | 7B0: `221022` pressione alle ruote, `221070` corsa del pedale, `22104C`, `22102D` (sotto) | risposta ricevuta da fermo; prova in marcia da fare |
| Contesto | Velocità, pedale freno, altri | `010D` (sopra) | da cercare |

## Esplorazione freni (fase 3, primo passo — APK 0.2.0)

Metodo (D-015): auto ferma in READY; mappa delle richieste a cui rispondono le centraline candidate, poi lettura
ripetuta di quelle che rispondono durante 4 fasi del pedale (rilasciato → leggero → a fondo → rilasciato, inizio di
ogni fase registrato come evento `pedal_phase`). Un byte che cambia col pedale è al massimo un **candidato** per pedale o
pressione: la distinzione rigenerazione/attrito richiede una prova in marcia.

| Centralina candidata | Richieste | Fonte dell'ipotesi | Stato |
|---|---|---|---|
| 7E2 → 7EA (sistema ibrido) | mode 21 `2100–21FF`, mode 22 `221000–221FFF` | Prius Gen2: regen brake torque effettiva/richiesta in `21C3` su 7E2, 0–186 Nm (PriusChat PDF, Oguchi); Toyota recenti passate da mode 21 a mode 22 (fonte di seconda mano) | candidato |
| 7B0 → 7B8 (freni / ABS) | come sopra | Prius Gen2: 7B0 = ABS brake (PriusChat PDF) | candidato |
| Intervallo mode 22 `1000–1FFF` | — | liste comunitarie GR Yaris (`221xxx`, header 700/7C0): indizio debole | candidato |

### Risultati sulla Yaris (2026-11-05, auto ferma in READY)
- **Mappa delle 18:35** (0.2.0, file sul telefono `esplorazione-freni-20261105-183514.jsonl`, non ancora esportato):
  **66 identificativi rispondono, tutti sulla 7B0 (freni) in mode 22 `221000–2210B6`**. La 7E2 non ha dato nessuna
  risposta positiva nell'intervallo provato (non sappiamo se negativa o assente).
- **Fasi del pedale** (0.2.1, `core/src/test/resources/recordings/2026-11-05-a34-esplorazione-pedale-0.2.1.jsonl`):
  un giro dei 66 identificativi dura ~10 s, quindi 1–2 letture per fase da 15 s; prova unica, da ripetere.

| Richiesta (7B0 → 7B8) | Rilasciato → Leggero → A fondo → Rilasciato | Ipotesi | Stato |
|---|---|---|---|
| `221022` | 4 byte uguali: `00` → `01` → `4E` → `00` | pressione idraulica alle 4 ruote (attrito), unità da trovare | risposta ricevuta |
| `22104C` | `00` → `02` → `0E` → `00` | pressione del cilindro maestro o richiesta di frenata | risposta ricevuta |
| `221070` | 2 byte quasi uguali: `41 3F` → `48 47` → `73 72` → `41 40` | corsa del pedale, due sensori ridondanti | risposta ricevuta |
| `22102D` | bit `0x20` dei byte 2 e 4 acceso solo a pedale premuto | interruttore luci stop / pedale premuto | risposta ricevuta |
| `221011` | `29` → `29` → `00` → `29` | sconosciuto (cambia solo a fondo) | risposta ricevuta |
| `221004`, `221007` | cambiano a fondo e subito dopo il rilascio | forse pompa o accumulatore idraulico | risposta ricevuta |

Variazioni di ±1–2 unità senza legame chiaro col pedale (rumore): `221034`, `221081–221083`, `2210A2`, `221032`;
`221025` sale nel tempo (`0D` → `18`), indipendente dal pedale. `2210AE` contiene il testo ASCII
`53314_2025-05-19_11:42:44` (probabile versione/data del software della centralina, non un VIN).

**Per la frenata**: con l'auto ferma tutta la frenata è ad attrito. Per distinguere la rigenerazione serve una prova in
marcia: nell'impianto Toyota la richiesta del conducente (corsa del pedale, `221070`) è divisa tra rigenerazione e
attrito (pressione alle ruote, `221022`), quindi una richiesta alta con pressione bassa indicherebbe rigenerazione.
È un'ipotesi da verificare, non un segnale validato.

## Fonti
- OBDLink Family Reference and Programming Manual (FRPM): https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- SAE J1979 / ISO 15031-5 (PID standard mode 01).
- Prius Gen2 (2009) CAN/OBD2 PID Codes: https://attachments.priuschat.com/attachment-files/2021/09/211662_Prius22009_CAnCodes.pdf
- Gen 2 Prius PIDs (Oguchi): https://www.oguchi-rd.com/Accomplishment/Automobile/07%20Toyota%20Prius/Parameter%20IDs/Toyota%20Gen%202%20Prius%20PIDs.pdf
- GR Yaris, reverse engineering PIDs: https://www.gr-yaris.co.uk/threads/reverse-engineering-pids.4680/
- Segnali ibridi Toyota interrogabili (mode 21 → 22, indirizzi): https://github.com/jwu02/esp32-car-telemetry/issues/9
