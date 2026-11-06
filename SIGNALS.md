# SIGNALS.md — Matrice dei segnali

Stati: **candidato** (definizione da fonte, mai provata) · **risposta ricevuta** (l'auto risponde, significato non
confermato) · **validato** (significato, unità e segno confermati con un riferimento indipendente).

Impostazioni adattatore usate: `ATZ`, `ATE0`, `ATL0`, `ATH1` (intestazioni CAN visibili, per sapere quale centralina
risponde), `ATSP0` (protocollo automatico), identificazione `ATI` / `STI`, protocollo letto con `ATDP`.

## Parametri di base (fase 2)

| Segnale | Centralina | Richiesta | Formato risposta | Conversione | Unità | Fonte | Prove | Stato |
|---|---|---|---|---|---|---|---|---|
| Giri motore termico | 7E8 (preferita se rispondono più centraline) | `010C` | `7E8 04 41 0C A B` | (A·256+B)/4 | rpm | SAE J1979 PID 0x0C | test JVM; prova reale 0.1.1; guida 0.3.0: 22/22 `NO DATA` con `ATSH 7E0` (fisico), risposte con 7DF (D-023) | risposta ricevuta |
| Velocità veicolo | 7E8 | `010D` | `7E8 03 41 0D A` | A | km/h | SAE J1979 PID 0x0D | test JVM; prova reale 0.1.1 (solo 0, da fermo); guida 0.3.0: 22/22 `NO DATA` con `ATSH 7E0` (fisico), risposte con 7DF (D-023) | risposta ricevuta |
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

## Candidati da Hybrid Assistant (analisi statica, ramo "Yaris MY20")

Fonte: decompilazione di Hybrid Assistant 3.322.0 ricontrollata con rea (`docs/hybrid-assistant-hyball-analisi.md`,
§4a e §8). L'app li ottiene tramite DID dinamici (`2C01` + `22F301`), che Hybrid Buddy non può inviare; qui sono
riportati come **letture dirette** `22` dei DID sorgente, mai provate sulla Yaris 2026. Byte numerati dal primo byte
dopo `62 DID DID`. Centralina **7D2 → 7DA** (ibrida, mai interrogata finora: l'esplorazione 0.2.x ha provato 7E2) e
**747 → 74F** (batteria Li-ion).

| Segnale | Centralina | Richiesta | Byte | Conversione | Unità | Fonte | Prove | Stato |
|---|---|---|---|---|---|---|---|---|
| Coppia richiesta dal freno (`brake_mastcyl_trq`) | 7D2 | `221067` | b1-b2 | (raw−32768)/8 | Nm (≤ 0 in frenata) | HA `scanner._v243_3` | guida 2026-11-05 (0.3.0): 622 risposte `7DA 05 62 10 67 ..`; 0 senza pedale (anche in rilascio con batteria in carica), −5…−192 Nm frenando in marcia, −500…−1024 da fermo col pedale premuto | risposta ricevuta |
| Coppia rigenerativa (`brake_regen_trq`) | 7D2 | `221069` | b3-b4 | (raw−32768)/8 | Nm (≤ 0 in frenata) | HA `scanner._v243_3` | guida 2026-11-05: 622 risposte; 0 senza pedale, −3…−195 Nm col pedale, spesso uguale a M (frenata solo rigenerativa), 0 da fermo | risposta ricevuta |
| Coppie del freno M e R insieme (D-028) | 7D2 | `2210671069` | `62 10 67 m1 m2 10 69 r1 r2 r3 r4`: M = m1-m2, R = r3-r4 | come sopra | Nm | ISO 14229 (ReadDataByIdentifier con più DID); HA le legge insieme via DDDI F301 | nessuna; se rifiutata, l'app torna a `221067` + `221069` | candidato |
| Pedale acceleratore (dall'uso) | 7D2 | `221060` | b1 | raw/2 | % | HA (`_v4_5`, "premuto" se > 0) | letto dalla Guida 0.3.2 per lo stato «Rilascio» (D-026); mai provato | candidato |
| Giri / coppia MG1 | 7D2 | `2210A0` | b1-b2 / b7-b8 | raw−32768 / (raw−32768)/8 | rpm / Nm | HA (anche lettura diretta nel sequencer `china`) | nessuna | candidato |
| Giri / coppia MG2 | 7D2 | `2210A1` | b1-b2 / b7-b8 | raw−32768 / (raw−32768)/8 | rpm / Nm | HA (idem) | nessuna | candidato |
| Tensione HV | 747 (Li-ion) o 7D2 | `221F9A` | b3-b4 | raw/64 | V | HA | guida 2026-11-05: 747 e 7D2 rispondono (multi-frame), 168–189 V | risposta ricevuta |
| Corrente HV | 747 o 7D2 | `221F9A` | b5-b6 | **complemento a due a 16 bit, /10** (la forma (raw−32768)/10 della lettura diretta in HA darebbe ±3270 A; la DDDI a 12 bit non si applica) | A (< 0 in carica, confermato dal segno in frenata) | HA; forma decisa sulla registrazione (D-024) | guida 2026-11-05: +3 A da fermo in READY, −159…+156 A in marcia; P = V·I = −30…+27 kW | risposta ricevuta |
| Limite di carica / scarica (dall'uso) | 747 o 7D2 | `221807` | b1-b2 / b3-b4 | (raw−32768)/100 | kW | HA | guida 2026-11-05: 747 risponde (b1-b2 −35,0…−22,7 kW, varia con la carica; b3-b4 `87D0` = +20,0 kW fisso); **7D2 rifiuta (7F 22 31)** | risposta ricevuta (747) |
| SOC batteria | 747 o 7D2 | `221F5B` | b1 | raw/2.55 | % | HA | nessuna | candidato |

Potenza termica (dall'uso in HA, `carstate._v123_4`): coppia `2210C2` su **700 → 708**, b1-b2, raw − 32768 Nm (forse coppia
richiesta, non effettiva), per i giri: P = coppia·2π·giri/60000 kW. Guida 2026-11-05: 708 risponde (0 a termico fermo,
−108…+86 Nm in marcia): **risposta ricevuta**; il significato (richiesta o effettiva) resta un'ipotesi.

**Letti dalla schermata Guida (0.3.2, D-019/D-020/D-023/D-025/D-026)**: `221067`, `221069`, `221060` (7D2, ogni giro); `221F9A`, `221807`
su 747 e su 7D2 (ogni 2 giri, preferita 747); `2210C2` su 700 (ogni 2 giri); `010C`, `010D` con richiesta funzionale 7DF
filtrata su 7E8 (ogni 2 giri). **Prima guida 2026-11-05** (0.3.0, 232 s, `core/src/test/resources/recordings/2026-11-05-a34-guida-0.3.0.jsonl`):
7D2, 747 e 700 rispondono a tutte le letture dirette (7D2 rifiuta solo `221807`); `010C`/`010D` indirizzati fisicamente a
7E0 hanno dato 44/44 `NO DATA`, mentre 27 s prima la sessione di base (7DF dopo `ATSP0`) riceveva le risposte di 7E8.
Frequenze misurate: freno 2,7 letture/s (giro 0,33 s mediano, 0,6 s p90; lettura `22` ~75 ms; ogni cambio centralina = 3
comandi AT da ~25 ms). Limite emerso: `221067`/`221069` valgono 0 anche quando, al rilascio dell'acceleratore, la batteria
si carica a −2…−8 kW con il termico a coppia 0: la rigenerazione **senza pedale** non è visibile da questi DID.

Formula di Hybrid Assistant (ramo Yaris), con M e R troncati all'intero come in HA (D-029): frenata se `M < 0 o R < 0`; attrito se `M − R < 0`; quota d'attrito
`(M − R)·100/M`; campioni validi sopra 15 km/h (`010D`). Senza pedale (stato «Rilascio», D-026): velocità ≥ 2 km/h,
`221060` = 0, termico spento (giri < 800) o trascinato (coppia `2210C2` < 0), potenza batteria < 0 → anello verde con
intensità min(1, |P/limite|), come fa la HyBall con la potenza HV negli stati EV / termico trascinato. Le richieste sono già ammesse da `ReadOnlyCommands`
(`ATSH 7D2`, `ATCRA 7DA`, `22xxxx`); non sappiamo se la centralina le accetta senza sessione estesa e DID dinamici
(decisione aperta in §8 del documento).

## Fonti
- OBDLink Family Reference and Programming Manual (FRPM): https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- SAE J1979 / ISO 15031-5 (PID standard mode 01).
- Prius Gen2 (2009) CAN/OBD2 PID Codes: https://attachments.priuschat.com/attachment-files/2021/09/211662_Prius22009_CAnCodes.pdf
- Gen 2 Prius PIDs (Oguchi): https://www.oguchi-rd.com/Accomplishment/Automobile/07%20Toyota%20Prius/Parameter%20IDs/Toyota%20Gen%202%20Prius%20PIDs.pdf
- GR Yaris, reverse engineering PIDs: https://www.gr-yaris.co.uk/threads/reverse-engineering-pids.4680/
- Segnali ibridi Toyota interrogabili (mode 21 → 22, indirizzi): https://github.com/jwu02/esp32-car-telemetry/issues/9
- Hybrid Assistant 3.322.0 (`priusfan.info.bthsd10`), analisi statica con jadx/apktool e rea 6.8.0: `docs/hybrid-assistant-hyball-analisi.md`.
