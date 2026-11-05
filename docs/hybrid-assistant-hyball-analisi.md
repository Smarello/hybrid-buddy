# Hybrid Assistant 3.322.0 — come decide regen vs attrito (HyBall)

Analisi statica, in sola lettura, dell'APK `priusfan.info.bthsd10` (XAPK APKPure, versionCode 322,
SHA-256 `6f4c4379a6c4b536f44ce5d2df97db1764ec05bf5c36149af8cfcd7dc0d5e079`), a scopo di studio e interoperabilità.

- **Prima passata**: `unzip`, `apktool 2.x`, `jadx 1.5`, più uno script Python che replica la cifratura stringhe di B4A.
- **Seconda passata (2026-11-05)**: ricontrollo con **rea 6.8.0** (provider JADX headless 0.7.1, decompilazione metodo per
  metodo di `scanner`, `carstate`, `cartype` e del metodo HyBall di `dash`). Le stringhe sono state ridecifrate dai sorgenti
  prodotti da rea: 209 valori su 210 coincidono con [hybrid-assistant-stringhe-obd-decifrate.json](hybrid-assistant-stringhe-obd-decifrate.json);
  fa eccezione solo `_vv1` (`"init"`), che non è in `scanner` e non è un comando OBD.

Legenda usata sotto: **[rea]** = letto nel codice decompilato da rea (evidence id in §10); **[corretto]** = la prima
passata diceva diversamente; **[ipotesi]** = dedotto da nomi, contesto o UI, non dal codice; **[non verificato]** = nessuna
prova sull'auto. Nessuna parte di questo documento è stata provata sulla Yaris: è tutta analisi statica.

Nomi offuscati: B4A rinomina i membri privati in `_` + N volte `v` + una cifra; qui sono scritti `_vN_d` (per esempio
`_v243_3`). Le classi e i nomi pubblici (`_brake_regen_trq`, `_car_yarismy20`, …) sono originali.

## 1. Quadro generale

- App scritta in **B4A (Basic4Android)**: layout `.bal` in `assets/`, classi `priusfan.info.bthsd10.*`.
- Le stringhe dei comandi OBD sono cifrate con `main.vvv13(byte[], seed)`: XOR sequenziale con package name,
  `versionName` ("3.322.0"), `versionCode` (322 → byte 0x42) e `(seed/7 + 737958)` big-endian.
- Classi chiave (ruoli confermati **[rea]**):
  - `scanner`: init dell'adattatore, sequenze di comandi per ECU ("sequencer"), decodifica delle risposte in variabili
    globali (`_brake_regen_trq`, `_brake_mastcyl_trq`, `_hv_i`, `_hv_v`, `_mg2_trq`, `_b_soc`, …).
  - `cartype`: VIN/codice modello → modello, capacità per modello, sequencer per modello.
  - `carstate`: macchina a stati (Braking, Good/Bad brake, …) e formula regen/attrito.
  - `dash` + `powerball`: disegno della pallina (`_pb_pwr_ball`).
  - `sharedvar`: id dei sequencer (`p3`, `g4n`, `g5`, `unknown`, `china`, …).

## 2. Comunicazione con l'adattatore

**Sequenza di init [rea]** (`scanner._process_globals`, array `_str_init`): `ATWS`, `ATWS`, `ATE0`, `ATI`, `STI`,
`AT@1`, `ATSP6`, `ATAT1`, `ATH1`, `ATL0`, `ATS0`, `ATCAF1`, `ATRV`, `03`, `STCSEGT 1`, **`0902 3` (due volte, VIN)**,
`ATSH 7E0`/`21C1 3`, `ATSH 7E2`/`2192 3`, comandi `STSL*` e `STCAFCP 761,762` (configurazione sleep/filtri dell'OBDLink),
`ATSH 747`/`3E00 1`, `ATSH 7E4`/`220101 9`, `ATSH 700`/`3E00 1`, poi sul motore (700): `1001`, `1003`, `31031002`,
`2C03F301`, `2C03F302`, `2C01F301…`, `2C01F302…`.

Nella stessa init **[rea]** (`scanner._v242_0`): il VIN è letto dai byte `[2..18]` della risposta a `0902`; da `21C1` su
7E0 si leggono un codice modello (`[1..7]`) e un secondo campo (`[8..15]`); la risposta di 747 a `3E00` (`74F…` ma non
`74F037F`) accende il flag "batteria 747 presente"; se 708 risponde `037F` alla definizione `2C01F302` scatta la
"Jurassic Park protection" (letture alternative sul motore).

**Adattatore [rea, nuovo]** (`scanner._v243_5`): un flag `_v7_6` diventa vero se `STI` inizia con `STN` oppure se `AT@1`
contiene `SCANTOOL` o `OBD SOLUTIONS LLC` (cioè un OBDLink/STN vero). Serve sotto (§3).

**Coda dei comandi [rea]**: ogni voce è (header TX, header RX atteso, comando, priorità) — `scanner._v241_7`. Il comando
porta in coda il numero di frame attesi (es. `22F301 3`).

**Indicizzazione dei byte di risposta [rea, confermata]** (`scanner._v244_3`): la risposta esadecimale è spezzata in
frame da 20 caratteri; per una risposta mode 22 `_v3_3[1]` è il **byte basso del DID** e i dati partono da `_v3_3[2]`
(in `62 F3 01 d0 d1 …`: `[1]=01`, `[2]=d0`). Per una risposta mode 01 i dati partono da `[1]`. Eccezione: per RAV4 Prime e
NX450h+ (e per i comandi che iniziano con `2201`) il primo frame salta un byte in più e i dati partono da `[1]`.

## 3. Sequencer e copertura Yaris

| Modello in tabella | Nome UI | Riconoscimento | Sequencer | Batteria | Fonte |
|---|---|---|---|---|---|
| `_car_yarismy12` (id 4) | "Yaris MY12" | codice modello JDM `NHP13` | `p3` (header 7E2, mode 21) | NiMH | [rea] |
| `_car_yarismy15` (id 13) | "Yaris MY15+" | VIN car. 4-7 = `KD3D` | alias di MY12 (`"same"`) | NiMH | [rea] |
| `_car_yarismy20` (id 77) | "Yaris MY20" / "Yaris (exp)" | VIN car. 4-7 = `KBAC`, `KBAB`, `KCAC` | `g4n` (UDS 22 + DDDI) | **Li-ion** **[corretto]** | [rea] |
| `_car_generic` (id 49) | — | nessuna corrispondenza ("ModelCode not found") | `unknown`: solo PID standard, `m_brakeavailable = false` | — | [rea] |

**Come si arriva al sequencer [rea]** (`scanner._v243_4` → `cartype._m_scannersequencer[modello]`):

- `g4n` esegue il sequencer DDDI `_v245_3` **solo se `_v7_6` è vero** (adattatore OBDLink/STN); altrimenti ripiega su
  `_v245_2`, che legge solo PID mode 01 standard (`01040C`, `010E10`, `010D4A`, `01050607`, `015B`, `019A`): niente freni.
  **[nuovo]** Con l'OBDLink LX (STN1155) il flag sarebbe vero.
- Se l'adattatore non è OBDLink, Prius 4, C-HR e Prius Prime vengono anche "declassati" a varianti `_degraded`
  ("Bad OBD"); la Yaris MY20 no (`cartype._v101_5`).

**Parametri Yaris MY20 [rea]** (`cartype._initialize`, `_v107_0`, `_v107_4`): potenza ICE 55 kW, batteria 19 kW,
cilindrata 1500, `m_brakespeed` = **15** km/h, `m_batterychemistry` = **Li-ion** (prima passata: implicitamente NiMH),
`m_brakeavailable = true` (vale per tutti i modelli con sequencer `g4n`), tipo PSD `hsd4`.

Non esiste alcuna voce più recente della Yaris MY20 **[rea]**: nessun ramo esplicito per la Yaris 2026.

## 4. PID coinvolti nella frenata

### 4a. Ramo `g4n` per la Yaris MY20 — sequenza effettiva [rea, corretto]

Sequencer `scanner._v245_3`. Per ogni centralina usata, `scanner._v243_6(header, rx)` accoda prima:
`3E00` (tester present), `1001` (sessione default), `1003` (sessione estesa), `31031002` (RoutineControl, sotto-funzione
03 = risultati della routine 0x1002), `2C03F301`, `2C03F302` (cancella i DID dinamici). Poi definisce i DID dinamici con
`2C01` (triplette `DID, byteIniziale, lunghezza`) e li legge ciclicamente con `22F301`/`22F302`.

Per la Yaris MY20, essendo Li-ion **[corretto]**:

| Centralina | Definizione (stringa) | Contenuto | Lettura |
|---|---|---|---|
| 700 → 708 (motore) | `_v30_4`/`_v30_5` `2C01F301 1F0C 01 02 · 1F0D 01 01 · 1F04 01 01 · 1F06 01 01 · 1F0E 01 01 · 10C2 01 02 · 1017 01 02 · 1F10 01 02 · 10C8 01 02` | rpm, velocità, carico, coolant, anticipo, coppia ICE, … | `22F301 3` |
| 700 → 708 | `_v28_7` `2C01F302 1F05 01 01 · 1F07 01 01 · 1F3C 01 02 · 1F3E 01 02 · 0103 02 03` | temperature, catalizzatore, odometro | `22F302 2` |
| **7D2 → 7DA (ibrido)** | **`_v30_6` `2C01F301 1060 01 01 · 10A0 01 02 · 10A0 07 02 · 10A1 01 02 · 10A1 07 02 · 1067 01 02 · 1069 03 02`** | pedale, MG1, MG2, **coppie freno** | **`22F301 3`** |
| 7D2 → 7DA | `_v31_1` `2C01F302 103A 03 01 · 103B 03 01 · 10A8 01 01 · 10A5 01 01` (solo Yaris MY20) | temperature MG/inverter | `22F302 2` |
| **747 → 74F (batteria Li-ion)** | `_v31_4` `2C01F301 1F9A 03 04 · 1807 01 04 · 1F5B 01 01` | HV V/A, limiti di carica/scarica, SOC | `22F301 2` |
| 747 → 74F | `_v31_5` `2C01F302 2802 01 01 · 180B 01 02 · 1814 01 0C` | temperature batteria | `22F302 3` |
| 7C0 → 7C8 | — | HSI (`221641` o `212B`), carburante (`221022` o `2129`) | lettura diretta |
| 7C4 → 7CC | — | **solo Yaris MY20**: `2210011`, `2210021` (temperature) invece di `2121` | lettura diretta |
| 7D2 → 7DA | — | `221809 4` (tensioni dei blocchi) o, se 747 presente, `22182E` su 747 | lettura diretta |
| 7D2 → 7DA | — | **`2F2802030080`** (IO control: ventola), accodato "a richiesta" | **attuatore** |

La variante con batteria in F301 (`_v30_7`, aggiunge `1F9A 03 04 · 1807 01 04 · 1F5B 01 01` e si legge con `22F301 4`)
vale solo per i modelli NiMH: la prima passata l'aveva attribuita anche alla Yaris MY20, **[corretto]**.

**Ripristino della sessione [rea, nuovo]** (`scanner._v243_5`): se durante il ramo `g4n` una risposta contiene
`037F2231` (requestOutOfRange) o `NO DATA`, l'app rimette in coda la sessione e le definizioni della centralina
interessata ("Reinitialize UDS session dead on …") ed esce dopo 4 tentativi. Indica che i DID dinamici si perdono quando
la sessione cade: sono stato volatile nella centralina.

**Decodifica di `22F301 3` su 7DA (Yaris MY20)** — `scanner._v243_3`, header 7DA, caso `22F301 3` **[rea]**:

| `_v3_3` | sorgente | formula | variabile |
|---|---|---|---|
| [2] | 1060 b1 | /2 | `_v4_5` → stato "acceleratore premuto" se > 0 in `carstate` (**[ipotesi]**: pedale acceleratore %) |
| [3..4] | 10A0 b1-2 | −32768 | `_mg1_rpm` |
| [5..6] | 10A0 b7-8 | (−32768)/8 | `_mg1_trq` (Nm) |
| [7..8] | 10A1 b1-2 | −32768 | `_mg2_rpm` |
| [9..10] | 10A1 b7-8 | (−32768)/8 | `_mg2_trq` (Nm) |
| **[11..12]** | **1067 b1-2** | **(−32768)/8, troncato a intero** | **`_brake_mastcyl_trq`** |
| **[13..14]** | **1069 b3-4** | **(−32768)/8, troncato a intero** | **`_brake_regen_trq`** |

**Decodifica di `22F301 2` su 74F (batteria Li-ion)** — `scanner._v243_3`, header 74F **[rea]**, applicata solo se
`[10] > 0` (SOC diverso da zero):

| `_v3_3` | sorgente | formula | variabile |
|---|---|---|---|
| [2..3] | 1F9A b3-4 | /64 | `_hv_v` (V) |
| [4..5] | 1F9A b5-6 | `[4] & 0x0F`, 12 bit con segno, /10 | `_hv_i` (A) |
| [6..7] | 1807 b1-2 | (−32768)/100 | `_v4_3` (kW): limite usato come denominatore della HyBall in regen; il nome "CCL" viene dall'etichetta UI vista nella prima passata **[ipotesi]** |
| [8..9] | 1807 b3-4 | (−32768)/100 | `_v4_4` (kW, **[ipotesi]** DCL) |
| [10] | 1F5B b1 | /2.55 | `_b_soc` (%) |

**Letture dirette su 7DA già decodificate dall'app [rea, nuovo]** (stesso metodo, header 7DA): `221F5B` (SOC `[2]/2.55`),
`221F9A` (V `[4..5]/64`, I `([6..7] − 32768)/10` — forma diversa dalla DDDI, a 16 bit), `2210A0`/`2210A1` (rpm `[2..3]`,
coppia `[8..9]`), `221807` (`[2..3]`, `[4..5]`), `221814`, `221809`. Le usa il sequencer `china` (`scanner._v245_0`), che
**non** apre sessioni né definisce DID dinamici: indizio che quelle letture `22` funzionano in sessione di default, almeno
sui modelli che lo usano. **Per `1067` e `1069` non esiste alcuna lettura diretta nell'app**: le coppie del freno arrivano
solo attraverso la DDDI F301.

**Altri modelli [rea]**: per RAV4 Prime e NX450h+ la decodifica è in `scanner._v243_2` (F301 con motore posteriore MGR:
`[14..15]` mastcyl, `[16..17]` regen, dati da `[1]`); per Prius 4/Corolla/C-HR NiMH vale la variante `22F301 4` con
`[11..12]`/`[13..14]` come sopra più batteria in `[15..23]`, decodificata solo se `[23] > 0`.

### 4b. Ramo `p3` (Yaris MY12 / MY15+) [rea]

`ATSH 7E2` (risposta `7EA`), comando **`2146 5`**: `_brake_regen_trq = ([16]·256+[17])/8 − 4096`,
`_brake_mastcyl_trq = ([18]·256+[19])/8 − 4096` (altri modelli della famiglia usano offset diversi, es. −8192).

### 4c. Modelli vecchi [rea]

Per Prius 2 e affini la coppia regen è **dedotta**: `_brake_regen_trq = (mastcyl < 0) ? mg2_trq · 5 : 0`. Su Yaris e
generazione 4 l'app legge invece una coppia rigenerativa riportata dalla centralina ibrida.

## 5. Logica di decisione (`carstate`) [rea]

Con `R = _brake_regen_trq`, `M = _brake_mastcyl_trq` (Nm, ≤ 0 in frenata), copiati da `scanner` in `carstate._v126_4`:

1. **Frenata in corso** (`_v124_6` → `_v122_7`): per motori Toyota (`_ice_toyota`, `_ice_2ld4s`) `M < 0 || R < 0`
   (Prius 2: segno di R invertito). Hyundai/Kia: `_brake_pressure > 0`.
2. **Attrito** (`_v124_7` → `_v122_0(R, M)`), ramo `default` usato dalla Yaris MY20:
   - `attrito = (M − R) < 0`, cioè la coppia richiesta supera in modulo quella recuperata, tolleranza 0 Nm;
   - `quota_attrito% = (M − R) · 100 / M` (`_v124_0`, troncata a intero; 0 se `M = 0`).
   - Tolleranze degli altri modelli: Prius 2 `R+M < −8`; IS300h/GS300h/RC300h `M−R < −28`; RX400h `M + 1.5·R < −2`;
     Prius 4/Prime/Camry SE18 `M−R < −10`; C-HR (tre varianti) `M−R < −30`; RX450h MY16 `M−R < −5`; Corolla 2.0
     `M−R < −30` **[corretto: è un caso a sé, non nel gruppo C-HR]**. Per RX450h MY16 e Corolla 2.0 la quota è calcolata
     con `R+M+5` / `R+M+30` invece che con `M−R`: incoerenza dell'autore, irrilevante per la Yaris.
3. **Stati** (`carstate._v49_5`, a ogni ciclo; non gira affatto se il modello è `_car_unknown`):
   - `Braking` = punto 1; `Moving` = velocità ≥ 2 (`_v130_5`; la velocità è moltiplicata per un fattore delle preferenze);
   - `Braking while moving` = Braking e Moving;
   - con Braking: `Bad brake` se il punto 2 dà attrito, altrimenti `Good brake`; senza Braking entrambi spenti;
   - `EV` = giri termico < 800 (`_v130_4`); `ICE spinning` = coppia termica < 0 con giri > 0 (termico trascinato o in
     avviamento); `ICE off` = EV oppure ICE spinning; `Moving` = velocità ≥ 2 km/h (`_v130_5`) **[rea, `ev_5d121490540e`]**;
   - `Coasting` = (Moving ∧ acceleratore non premuto ∧ non Braking ∧ ICE off) oppure ICE spinning.
4. **Storico frenate** (icone): un evento inizia sul fronte di salita di `Braking while moving`; i campioni contano solo
   se **velocità OBD > `m_brakespeed`** (15 km/h per Yaris MY20, letto da `cartype` in `carstate._v101_5`). A fine
   frenata: `good` se tutti i campioni erano good o i bad sono ≤ 2; `bad` se i bad superano metà dei good; altrimenti
   `notsobad`.
5. **Energia recuperata**: con Braking e potenza HV negativa, `_v137_6 += −P · dt / 3 600 000` (kWh se `dt` è in ms, unità **[ipotesi]**).
   Potenza `P = I · V / 1000` kW (`_v85_3`, negativa in carica).

## 6. HyBall: colore e posizione (`dash._v277_3` → `powerball._v154_1(valore, colore)`) [rea]

Durante `Braking while moving`:

- **Con attrito** (`Bad brake`): `valore = quota_attrito%` se velocità OBD > `m_brakespeed`, altrimenti 0 (nel
  decompilato la variabile resta non assegnata: lo 0 è **[ipotesi]**). Colore `colortools._v47_4(valore)`.
- **Senza attrito** (`Good brake`): `valore = _v137_1 − 100`, con `_v137_1 = min(1, |P / _v4_3|) · 100` calcolato in
  `carstate._v49_5` (se `_v4_3 = 0` il rapporto vale 1).

Quindi la pallina **sopra il centro** indica la quota di frenata ad attrito (0–100 %), **sotto il centro** l'intensità
della rigenerazione rispetto al limite di carica della batteria. Le soglie di colore (giallo sotto 75, rosso verso 100,
verde in regen) vengono dalla prima passata e non sono state ricontrollate con rea **[non verificato con rea]**.

**Fuori da `Braking while moving`** (stesso metodo, ricontrollato il 2026-11-05 con `carstate._v49_5`, `ev_5d121490540e`) [rea]:

- Stato `EV` con velocità ≥ 20 km/h (`_v136_7`): la pallina mostra la **potenza HV** `P` (`_v110_6` = I·V/1000; con una
  preferenza, la potenza termica `_v146_2`): valore = 100 − indice di glide, con indice di glide = (1 − |P| / 2 kW)·100
  (`_v136_0`, `_v136_6` = 2.0); il segno segue P: **P < 0 (batteria in carica) → pallina sotto il centro**, P > 0 → sopra,
  P = 0 → centro. Colore `colortools._v47_5`. Stato `ICE spinning`: stesso valore ±|P| / 2 kW · 100.
- Termico acceso (Toyota): valore dai giri MG1 (`_v141_2` / 10, 100 oltre 1000 rpm, colore a parte se negativi).
- Velocità < 20 km/h in EV: valore −(20 − velocità)·100/20, colore neutro.

Conseguenza: **la rigenerazione al rilascio dell'acceleratore è mostrata dalla HyBall tramite la potenza della batteria**,
su una scala di ±2 kW pensata per il "glide" (pallina a fondo scala già a −2 kW), non tramite `1067`/`1069`, che valgono
0 senza pedale (confermato sulla Yaris 2026 nella guida del 2026-11-05: −2…−8 kW in carica con M = R = 0). Le statistiche
seguono la stessa divisione: `_avg_kwforregen` è alimentata da `P` nello stato `Coasting`, `_avg_kwforbrake` da `P` in
`Braking while moving` + `Good brake`. Per Hybrid Buddy: l'anello deve coprire due casi distinti, pedale (`1067`/`1069`,
attrito o rigenerativa) e rilascio (potenza HV negativa con pedale non premuto e termico spento o trascinato).

## 7. Yaris 2026 senza VIN (`cartype._v101_5`) [rea]

- Il VIN (dalla risposta a `0902`, oppure dal file `vin.txt` nella cartella dati) viene usato solo se più lungo di 8
  caratteri: i caratteri 4-7 (`substring(3,7)`) vengono confrontati con la lista; `KBAC`/`KBAB`/`KCAC` → Yaris MY20.
- Senza VIN valido la parte VIN diventa `"dummy"` e l'app prova il **codice modello** letto da `21C1` su 7E0 (primi 5
  caratteri) contro una lista JDM (`NHP13` → Yaris MY12, …). Se nemmeno quello corrisponde → `_car_generic`
  ("ModelCode not found"), sequencer `unknown`, **nessun dato freno**.
- C'è anche un override manuale del nome (`mycar.txt`) e una preferenza "Car detection" usata solo per alcuni
  Hyundai/Kia e RAV4 AUS (non per la Yaris).

**Conseguenza per Hybrid Buddy**: la scelta del ramo in Hybrid Assistant è solo un modo per sapere *quale* centralina e
*quali* DID interrogare. Hybrid Buddy non ha bisogno del VIN: sa già di essere su una Yaris Hybrid e può usare
direttamente i DID del ramo Yaris MY20. Se serve sapere se la 2026 "sarebbe" una MY20 per Hybrid Assistant, i caratteri
4-7 del VIN si leggono dal libretto, senza alcuna richiesta `0902`. Che la Yaris 2026 risponda come una MY20 resta
**[non verificato]**.

## 8. Cosa serve per replicarlo in Hybrid Buddy

**Dati minimi**: `M` (DID `1067`, byte 1-2) e `R` (DID `1069`, byte 3-4) dalla centralina ibrida **7D2 → 7DA**, entrambi
`(raw − 32768) / 8` Nm; per la pallina in regen anche `P = V·I/1000` e il limite `1807` b1-2 (dalla batteria 747 → 74F
nel ramo Li-ion). Velocità OBD (`010D`) per la soglia di 15 km/h.

**Osservazione importante per il progetto**: l'esplorazione 0.2.x di Hybrid Buddy ha interrogato **7E2** (e 7B0), ma
nel ramo `g4n` la centralina ibrida è **7D2** (risposte 7DA), che non è mai stata provata. I DID `1067`/`1069` cadono
nell'intervallo `1000–1FFF` già usato per la mappa.

**Percorso A — solo letture pure (consentito oggi)**: `ATSH 7D2`, `ATCRA 7DA`, flow control `ATFCSH 7D2` /
`ATFCSD 30 00 00` / `ATFCSM 1`, poi `221067`, `221069` (e se servono `2210A0`, `2210A1`, `221F9A`, `221807`, `221F5B`,
anche su 747 → 74F). Tutti rientrano già in `ReadOnlyCommands` (`ATSH [0-9A-F]{3}`, `22(?!F1)[0-9A-F]{4}`).
Limiti: l'app non legge mai `1067`/`1069` direttamente, quindi **non sappiamo** se la centralina li espone con `22` in
sessione di default; un rifiuto (`7F 22 31` o `7F 22 7F`) direbbe che servono DDDI o sessione estesa. Le letture
dirette di `10A0`/`10A1`/`1F9A`/`1807`/`1F5B` usate dal sequencer `china` sono un indizio favorevole, non una prova.

**Percorso B — replicare la DDDI (decisione aperta, NON consentito)**: Hybrid Assistant usa, prima delle letture,
comandi che **non sono letture pure** e che `ReadOnlyCommands` oggi rifiuta:

| Comando | Servizio UDS | Effetto | Valutazione |
|---|---|---|---|
| `3E00` | TesterPresent | tiene viva la sessione | innocuo ma non è una lettura; da decidere |
| `1001`, `1003` | DiagnosticSessionControl | cambia sessione (default / estesa) | cambia lo stato della centralina; da decidere |
| `31031002` | RoutineControl, sotto-funzione 03 (requestRoutineResults) | legge i risultati della routine 0x1002 | servizio 31 in generale avvia/ferma routine: escluso salvo decisione specifica |
| `2C03F301`, `2C03F302` | DynamicallyDefineDataIdentifier, clear | cancella definizioni dinamiche | scrive (RAM volatile) nella centralina |
| `2C01F301…`, `2C01F302…` | DynamicallyDefineDataIdentifier, define | definisce DID dinamici | scrive (RAM volatile) nella centralina |
| `2F2802030080` | InputOutputControlByIdentifier | comanda la ventola | **attuatore: mai** |

Il percorso B richiederebbe una nuova decisione in `DECISIONS.md` (con test) che allarghi `ReadOnlyCommands`; questo
documento non la propone come già consentita. Va tentato solo se il percorso A fallisce.

**Formula da riprodurre** (identica al ramo `default` di `carstate`): in frenata (`M < 0 || R < 0`),
`attrito = M − R < 0`, `quota_attrito% = (M − R)·100 / M`; in regen pura `intensità = min(1, |P / limite|)`. Un dato
mancante o scaduto va mostrato come "Non disponibile" (vincolo di `AGENTS.md`), non come 0 % di attrito.

## 9. Punti rimasti opachi

- Significato dei DID `1067`/`1069`: inferito dai nomi `brake_mastcyl_trq`/`brake_regen_trq` scelti dall'autore, non da
  documentazione Toyota **[ipotesi]**. Non è detto che `1067` sia la coppia del solo attrito o la richiesta totale:
  il codice la tratta come richiesta totale (si sottrae la regen).
- `1060` come pedale acceleratore, `1807` come CCL/DCL e i campi F302 (`103A`, `103B`, `10A8`, `10A5`) come temperature:
  dedotti dall'uso **[ipotesi]**.
- Corrente HV: la forma DDDI (12 bit con segno) e la forma diretta di `221F9A` (16 bit, −32768) sono diverse nel codice;
  quale valga sulla Yaris 2026 è da verificare.
- Nessuna prova che la Yaris 2026 abbia 7D2/747 con gli stessi DID della MY20.

## 10. Evidenze rea

APK SHA-256 `6f4c4379…e079`. Evidence id (troncati a 12 caratteri) dei risultati rea usati:

| Oggetto | Evidence |
|---|---|
| `scanner` (classe, 58 metodi) | `ev_8c2133ba39b5` |
| `scanner._v242_0` (dispatcher risposte, VIN, `_v243_2` solo per RAV4 Prime/NX450h+) | `ev_35c4469293e0` |
| `scanner._v243_3` (decodifica per header; 7DA e 74F) | `ev_56b988b2777f` |
| `scanner._v243_2` (decodifica RAV4 Prime/NX450h+) | `ev_1a56f8d8f6cb` |
| `scanner._v243_4` (scelta del sequencer, flag `_v7_6`) | `ev_2c93bc955e77` |
| `scanner._v243_5` (rilevamento OBDLink, ripristino sessione UDS) | `ev_870b2086b36c` |
| `scanner._v243_6` (sessione + cancellazione DDDI per ECU) | `ev_621454092798` |
| `scanner._v244_3` (parsing ISO-TP in `_v3_3`) | `ev_f7241bce09ac` |
| `scanner._v245_3` (sequencer `g4n`) | `ev_ce7913792fe7` |
| `scanner._v245_0` (sequencer `china`, letture dirette su 7D2) | `ev_56a5514c77e2` |
| `scanner._v245_2` (ripiego senza OBDLink) | `ev_e410a576b911` |
| `scanner._process_globals` (stringhe cifrate, `_str_init`) | `ev_b5fab4b8b4e9` |
| `carstate` (classe) | `ev_69b2e997a923` |
| `carstate._v49_5` (macchina a stati, storico, energia, `_v137_1`) | `ev_c298db1cce84` |
| `carstate._v122_0` (attrito e tolleranze) | `ev_76e89e09f0e0` |
| `carstate._v122_7` (frenata in corso) | `ev_3d20be52d7b5` |
| `carstate._v124_0` (quota d'attrito) | `ev_decc66a0b3ad` |
| `carstate._v124_6` / `_v124_7` | `ev_e5f9cd624058` / `ev_38aa4f2b1fa7` |
| `carstate._v126_4` (copia dei dati da `scanner`) | `ev_b361aa6927dc` |
| `carstate._v85_3` (`P = I·V/1000`) | `ev_fbd8fa2c822e` |
| `cartype` (classe) | `ev_3b33df836c00` |
| `cartype._v101_5` (VIN / codice modello → modello) | `ev_cb241c2610b3` |
| `cartype._initialize` (tabelle: sequencer, `m_brakespeed`, chimica, potenze) | `ev_74fc250301f3` |
| `cartype._v107_4` (capacità per sequencer, `m_brakeavailable`) | `ev_dd20aa7bdc0f` |
| `dash._v277_3` (HyBall) | `ev_e6419abd8986` |

I sorgenti decompilati da rea (con nomi accorciati) sono nello scratchpad della sessione, fuori dal repository:
`/private/tmp/claude-501/-Users-smarello-repo-hybrid-buddy/6de0239e-054a-4f9c-a309-6e70c85dbede/scratchpad/rea/`.
