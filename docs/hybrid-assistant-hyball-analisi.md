# Hybrid Assistant 3.322.0 — come decide regen vs attrito (HyBall)

Analisi statica dell'APK `priusfan.info.bthsd10` (XAPK APKPure, versionCode 322), a scopo di studio e interoperabilità.
Strumenti: `unzip`, `apktool 2.x`, `jadx 1.5`, più uno script Python che replica la cifratura stringhe di B4A.

## 1. Quadro generale

- App scritta in **B4A (Basic4Android)**: layout `.bal` in `assets/`, classi `priusfan.info.bthsd10.*`.
- Offuscamento parziale tipico di B4A:
  - nomi di variabili/metodi privati rinominati in `_vvvv…N` (nomi pubblici e stringhe UI leggibili);
  - **tutte le stringhe dei comandi OBD cifrate** con `main.vvv13(byte[], seed)`: XOR sequenziale con package name, `versionName` ("3.322.0"), `versionCode` (322 → byte 0x42) e `(seed/7 + 737958)` big-endian. Replicata in Python: 210 stringhe decifrate, tabella in [hybrid-assistant-stringhe-obd-decifrate.json](hybrid-assistant-stringhe-obd-decifrate.json).
- Manifest: permessi Bluetooth (classic + CONNECT), posizione fine/coarse, Internet, storage, wake lock, foreground service `connectedDevice` e `location`. Componenti: activity `main`, `dash` (cruscotto), `tripviewer`, `stats`, `viewmap`, `viewreport`, varie settings; servizi `scanner` (comunicazione OBD), `starter` (GPS), `fancontroller`, `cloudupdater`.
- Classi chiave:
  - `scanner`: init ELM327, sequenza comandi per ECU, decodifica risposte in variabili globali (`_brake_regen_trq`, `_brake_mastcyl_trq`, `_hv_i`, `_hv_v`, `_mg2_trq`, …).
  - `cartype`: VIN → modello, capacità per modello, scelta del "sequencer" (set di comandi).
  - `carstate`: macchina a stati (Braking, Good brakes, Bad brakes, …) e formula regen/attrito.
  - `dash` + `powerball`: disegno della pallina (nel codice si chiama `PowerBall`, `_pb_pwr_ball`).
  - `sharedvar`: id dei sequencer (`p3`, `g4n`, `g5`, `unknown`, …).

## 2. Comunicazione ELM327

Sequenza di init (`scanner._str_init`): `ATWS`, `ATE0`, `ATI`, `STI`, `AT@1`, **`ATSP6`** (CAN 11 bit 500 kbps), `ATAT1`, `ATH1`, `ATL0`, `ATS0`, `ATCAF1`, `ATRV`, `03`, `STCSEGT 1`, `0902` (VIN), `ATSH 7E0`/`21C1` (calibration ID), `ATSH 7E2`/`2192`, comandi `STSL*` (sleep OBDLink).

Ogni voce della sequenza = (header TX, header RX atteso, comando, priorità). Il comando porta in coda il numero di frame attesi (es. `2101 4`). Prima di ogni comando con header diverso l'app manda `ATSH <header>`.

Indicizzazione dei byte di risposta: `_v3_3[0]` è il **primo byte dopo il byte di servizio** (es. in `62 F3 01 …` → `[0]=F3`, `[1]=01`, dati da `[2]`; in `61 46 …` → `[0]=46`, dati da `[1]`).

## 3. Sequencer e copertura Yaris

| Modello in tabella | Nome UI | Riconoscimento | Sequencer |
|---|---|---|---|
| `_car_yarismy12` | "Yaris MY12" / "Yaris" | codice telaio JDM `NHP13` | `p3` (famiglia Prius 3, header 7E2, mode 21) |
| `_car_yarismy15` | "Yaris MY15+" | VIN pos. 4-7 = `KD3D` | alias di MY12 (`"same"`) |
| `_car_yarismy20` | "Yaris MY20" / "Yaris (exp)" | VIN pos. 4-7 = `KBAC`, `KBAB`, `KCAC` | `g4n` (UDS mode 22 + DDDI, header 700/7D2) |
| `_car_generic` | — | VIN non riconosciuto ("ModelCode not found") | `unknown`: solo PID standard, **`m_brakeavailable = false`** |

Non esiste alcuna voce MY21+ (il modello più recente in tabella è Ioniq MY21): **non c'è un ramo esplicito per la Yaris 2026**. Siamo quindi nel caso: *copertura solo tramite il ramo "Yaris MY20" (marcato sperimentale), a condizione che i caratteri 4-7 del VIN siano uno dei tre codici sopra; altrimenti si cade nel generico senza dati freno.*

Note utili:
- "Yaris MY20" è trattata quasi come Prius 4 / Corolla (stesso `g4n`), con tre differenze: definizione F302 su 7D2 con DID `10A8` invece di `10A4`; temperature lette con `2210011`/`2210021` su 7C4 invece di `2121`; temperatura ambiente non presa dal byte 6 di F302.
- Override possibili senza modificare l'app: file `vin.txt` e `mycar.txt` nella cartella dati dell'app, oppure impostazione "Car detection" (`preferences._cardetect_*`).
- Parametri per Yaris: potenza ICE 55 kW / batteria 19 kW (`_v107_0`), cilindrata 1500, soglia velocità per valutazione freni `m_brakespeed` = 15 km/h.

## 4. PID coinvolti nella frenata

### 4a. Ramo `g4n` (Yaris MY20, Prius 4, Prime, Corolla, Camry MY18, UX, RAV4 MY19)

Per ogni ECU: `3E00`, `1001`, `1003`, `31031002`, `2C03F301`, `2C03F302` (tester present, sessione, routine, cancellazione DDDI), poi definizione di due DID dinamici con il servizio `2C 01` (triplette `DID, byteIniziale, lunghezza`) e lettura ciclica con `22 F301` / `22 F302`.

**ECU ibrida — `ATSH 7D2`, risposta `7DA`** (NiMH; per Li-ion la parte tra parentesi manca):

```
2C01F301 1060 01 01 | 10A0 01 02 | 10A0 07 02 | 10A1 01 02 | 10A1 07 02 | 1067 01 02 | 1069 03 02 (| 1F9A 03 04 | 1807 01 04 | 1F5B 01 01)
2C01F302 103A 03 01 | 103B 03 01 | 10A8 01 01 | 10A5 01 01          (variante Yaris MY20)
22F301 4   22F302 3   221809 4   2F2802030080 (routine ventola)
```

Decodifica di `62 F3 01` (`scanner._v243_3`, caso header 7DA):

| byte | sorgente DID | formula | variabile |
|---|---|---|---|
| [2] | 1060 b1 | /2 | `_v4_5` (uso: pedale/percentuale) |
| [3..4] | 10A0 b1-2 | −32768 | MG1 rpm |
| [5..6] | 10A0 b7-8 | (−32768)/8 | MG1 Nm |
| [7..8] | 10A1 b1-2 | −32768 | MG2 rpm |
| [9..10] | 10A1 b7-8 | (−32768)/8 | MG2 Nm |
| **[11..12]** | **1067 b1-2** | **(−32768)/8** | **`_brake_mastcyl_trq`** (coppia richiesta dal cilindro maestro, Nm, negativa in frenata) |
| **[13..14]** | **1069 b3-4** | **(−32768)/8** | **`_brake_regen_trq`** (coppia rigenerativa effettiva, Nm, negativa) |
| [15..16] | 1F9A b3-4 | /64 | HV V |
| [17..18] | 1F9A b5-6 | 12 bit con segno, /10 | HV A |
| [19..20] | 1807 b1-2 | (−32768)/100 | **CCL** limite potenza di carica, kW (`_lbl_ccl`) |
| [21..22] | 1807 b3-4 | (−32768)/100 | DCL limite di scarica, kW |
| [23] | 1F5B | /2.55 | SOC % |

**ECU motore — `ATSH 700`, risposta `708`**: `2C01F301 1F0C 01 02 | 1F0D 01 01 | 1F04 01 01 | 1F06 01 01 | 1F0E 01 01 | 10C2 01 02 | 1017 01 02 | 1F10 01 02 | 10C8 01 02` (rpm/4, velocità, carico, coolant, anticipo, coppia ICE −32768, iniezione, …) e `2C01F302 1F05 01 01 | 1F07 01 01 | 1F3C 01 02 | 1F3E 01 02 | 0103 02 03` (temperature, catalizzatore, odometro). Nessun dato freno.

**Altri header**: `7C0`→`7C8` (`221641`/`212B` HSI, `221022`/`2129` carburante), `7C4`→`7CC` (temperature), `747`→`74F` solo Li-ion.

### 4b. Ramo `p3` (Yaris MY12 / MY15+)

`ATSH 7E2` (risposta `7EA`), comando **`2146 5`**: `_brake_regen_trq = ([16]·256+[17])/8 − 4096`, `_brake_mastcyl_trq = ([18]·256+[19])/8 − 4096` (altri modelli della famiglia usano offset diversi). Altri comandi: `2101 4` (SOC [22]/2.55, T amb [4]−40), `2128` (odometro), `2161626768`, `2174`, `2198`, `2181 6` (batteria), `216369`, ecc.

### 4c. Modelli vecchi (Prius 2, RX400h, Highlander MY06, Estima, Camry MY07, HS250h, SAI, GS450h, Crown MY08)

Qui sì che la coppia regen è **dedotta**: `_brake_regen_trq = (mastcyl < 0) ? mg2_trq · 5 : 0`. È l'unico caso in cui vale l'ipotesi "incrocio della domanda con la coppia MG2". Su Yaris e su tutta la generazione 4 l'app legge invece un valore di coppia rigenerativa riportato direttamente dalla ECU ibrida.

## 5. Logica di decisione (`carstate`)

Con `R = _brake_regen_trq`, `M = _brake_mastcyl_trq` (entrambi in Nm, ≤ 0 in frenata):

1. **Frenata in corso** (`_v122_7`): `M < 0 || R < 0` (per Prius 2 il segno di R è invertito). Per Hyundai/Kia si usa invece `_brake_pressure > 0`.
2. **Attrito ("Bad brakes")** (`_v122_0`), ramo `default` usato da tutte le Yaris:
   - `attrito = (M − R) < 0`, cioè la coppia richiesta supera in modulo quella recuperata;
   - `quota_attrito% = (M − R) · 100 / M`.
   - Tolleranze per altri modelli: Prius 2 `R+M < −8`; IS300h/GS300h/RC300h `< −28`; RX400h `M + 1.5·R < −2`; Prius 4/Prime/Camry SE18 `< −10`; C-HR (tutte) e Corolla 2.0 `< −30`; RX450h MY16 `< −5`. Yaris: tolleranza 0 Nm.
3. Stati derivati: `Good brakes` = frenata senza attrito, `Bad brakes` = con attrito, `Braking while moving` = frenata e velocità ≥ soglia "moving". I campioni contano per lo storico solo se velocità OBD > 15 km/h.
4. **Storico frenate** (icone in basso): a fine frenata l'evento è `good` se tutti i campioni erano good o i campioni bad ≤ 2; `bad` se i bad superano metà dei good; altrimenti `notsobad`.
5. Il recupero energetico in frenata si accumula in `REGENKWH` (kWh) dalla potenza HV `V·I/1000` quando lo stato è Braking.

## 6. HyBall: colore e posizione (`dash._v277_3` → `powerball._v154_1(valore, colore)`)

Valore in [−100, +100] → posizione verticale `y0 − valore/100 · maxbias` (se il salto supera 15 punti la posizione è mediata con la precedente). Durante `Braking while moving`:

- **Con attrito** (`Bad brakes`): `valore = quota_attrito%` se velocità > 15 km/h, altrimenti 0. Colore `_v47_4(valore)`: 0 < v < 75 → `ColorWarning` (giallo), 75..100 → sfumatura giallo → `ColorBad` (rosso).
- **Senza attrito** (`Good brakes`): `valore = min(1, |P_hv / CCL|) · 100 − 100`, con `P_hv = V·I/1000` (kW, negativa in carica) e `CCL` = limite di carica letto dal DID 1807. Colore: sfumatura verde scuro → `ColorGood` (verde) tra −100 e 0.

Quindi la pallina **sopra il centro** indica la quota di frenata ad attrito (0–100 %), **sotto il centro** l'intensità della rigenerazione rispetto al limite di carica della batteria; la posizione non dipende dalla pressione del pedale ma dal rapporto tra le due coppie (o tra potenza e limite). L'icona freno (`_di_brake`) è rossa con attrito, verde in regen pura, grigia a riposo.

## 7. Risposta alle domande

- **Ipotesi "flag diretto vs deduzione"**: per la Yaris (sia MY12/15 sia MY20) l'app **non deduce** la frenata ad attrito dalla corrente HV o dalla coppia MG2: legge due coppie dalla ECU ibrida (richiesta dal cilindro maestro e rigenerativa) e dichiara attrito quando la richiesta eccede la rigenerativa. La corrente HV serve solo per il posizionamento della pallina in regen e per i kWh. La deduzione da MG2 esiste solo per i modelli pre-2009.
- **Copertura Yaris 2026**: nessuna tabella dedicata. Se il VIN inizia con un codice `KBAC`/`KBAB`/`KCAC` in posizione 4-7 la vettura viene trattata come "Yaris MY20" (ramo `g4n`); altrimenti come generica senza dati freno. L'app è ferma al ~2021, quindi la riutilizzabilità dei PID sulla 2026 (stesso sistema ibrido 1.5 M15A-FXE, famiglia TNGA-B) è plausibile ma **non verificata** dalle tabelle.

## 8. Come procedere sulla vettura

1. Leggere il VIN (`0902`) e confrontare i caratteri 4-7 con `KBAC`/`KBAB`/`KCAC`.
2. Provare direttamente, senza DDDI: `ATSP6`, `ATH1`, `ATSH 7D2`, poi `22 1067` e `22 1069` (risposte `62 10 67 …`: usare i byte 1-2 di 1067 e 3-4 di 1069 con la formula `(b1·256+b2 − 32768)/8` Nm) e `22 1807` per CCL/DCL, `22 1F9A` per V/I, `22 1F5B` per SOC.
3. Se l'ECU risponde `7F 22 31` (DID non supportato) su 7D2, provare su `7E2` i comandi mode 21 del ramo `p3` (`2146`) come secondo tentativo.
4. Per riprodurre l'indicatore: `attrito = (M − R) < 0`, `quota = (M−R)/M·100`, `regen = min(1, |V·I/1000 / CCL|)`.

## 9. Punti rimasti opachi

- Alcune variabili sono identificabili solo dall'uso (`_v4_5` da 1060, campi F302 `103A`/`103B`/`10A8`/`10A5`: temperature MG/inverter e stato ventola, per contesto UI).
- Il significato esatto dei DID `1067`/`1069` è inferito dai nomi `brake_mastcyl_trq`/`brake_regen_trq` dell'autore, non da documentazione Toyota.
- Sorgenti decompilati completi (jadx, apktool, copie con nomi accorciati e stringhe annotate) nello scratchpad della sessione: `/private/tmp/claude-501/-Users-smarello-repo-hybrid-buddy/9df8de11-e8dc-41a5-82d3-cd4aa0706eea/scratchpad/{jadx,apktool,clean}`.
