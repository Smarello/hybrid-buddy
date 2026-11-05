# SIGNALS.md — Matrice dei segnali

Stati: **candidato** (definizione da fonte, mai provata) · **risposta ricevuta** (l'auto risponde, significato non
confermato) · **validato** (significato, unità e segno confermati con un riferimento indipendente).

Impostazioni adattatore usate: `ATZ`, `ATE0`, `ATL0`, `ATH1` (intestazioni CAN visibili, per sapere quale centralina
risponde), `ATSP0` (protocollo automatico), identificazione `ATI` / `STI`, protocollo letto con `ATDP`.

## Parametri di base (fase 2)

| Segnale | Centralina | Richiesta | Formato risposta | Conversione | Unità | Fonte | Prove | Stato |
|---|---|---|---|---|---|---|---|---|
| Giri motore termico | 7E8 (preferita se rispondono più centraline) | `010C` | `7E8 04 41 0C A B` | (A·256+B)/4 | rpm | SAE J1979 PID 0x0C | test JVM su risposte sintetiche | candidato |
| Velocità veicolo | 7E8 | `010D` | `7E8 03 41 0D A` | A | km/h | SAE J1979 PID 0x0D | test JVM | candidato |
| Temperatura refrigerante | 7E8 | `0105` | `7E8 03 41 05 A` | A−40 | °C | SAE J1979 PID 0x05 | test JVM | candidato |
| Carico calcolato | 7E8 | `0104` | `7E8 03 41 04 A` | A·100/255 | % | SAE J1979 PID 0x04 | test JVM | candidato — solo diagnostica, **non** usabile per la potenza termica |
| Tensione all'adattatore | OBDLink (non centralina) | `ATRV` | `14.1V` | numero prima di `V` | V | OBDLink FRPM | test JVM | candidato |

Nota: sulle Toyota ibride anche la centralina ibrida (es. `7E2`) può rispondere ad alcuni PID mode 01. Con `ATH1` la
centralina che risponde resta nel log; per i giri si sceglie `7E8`. Da confermare sulla Yaris 2026.

## Segnali essenziali dell'MVP (fase 3 — da cercare)

| Funzione | Dati necessari | Candidati | Stato |
|---|---|---|---|
| Potenza batteria | Tensione e corrente HV | Car Scanner li legge già: definizioni da individuare e documentare con la fonte | da cercare |
| Potenza termica | Potenza dalla centralina, oppure coppia **effettiva** del termico + giri | — | da cercare |
| Indicatore frenata | Segnali che distinguano rigenerazione e intervento dell'attrito | — | da cercare |
| Contesto | Velocità, pedale freno, altri | `010D` (sopra) | da cercare |

## Fonti
- OBDLink Family Reference and Programming Manual (FRPM): https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf
- SAE J1979 / ISO 15031-5 (PID standard mode 01).
