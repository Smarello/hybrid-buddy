# STATUS.md

**Aggiornato:** 2026-11-05 · versione app 0.3.0

## Ultimo risultato verificato
- **0.3.0 — schermata Guida** (fasi 4–5): indicatore di frenata (disco con anello verde → rosso, quota sotto il disco) e
  powermeter (termico e batteria in kW) con la ricetta di Hybrid Assistant per la Yaris MY20, solo letture dirette
  (D-019–D-022). Verificato automaticamente (test JVM): conversioni dei DID (anche multi-frame), classificazione con gli
  esempi della specifica (42 % d'attrito, rigenerativa 50 %, sotto 15 km/h, nessuna frenata), «Non disponibile» per dato
  rifiutato, mancante o scaduto, indirizzamento fisico e flow control, ripiego della batteria da 747 a 7D2, centralina muta
  interrogata sempre più di rado, registrazione e replay di una guida. Build, lint, APK.
- **Non verificato**: aspetto della schermata sul telefono (nessun emulatore né dispositivo nell'ambiente di sviluppo),
  risposte reali di 7D2/747/700 alle letture dirette, frequenze reali di lettura.
- **Prova 0.2.1 (2026-11-05)**: «Ripeti solo il pedale» ha riusato la mappa delle 18:35 (66 identificativi, tutti 7B0) e
  completato le quattro fasi; candidati 7B0 `221022`, `221070`, `22104C`, `22102D` (`SIGNALS.md`).
- **Prova da fermo 0.1.1**: connessione all'LX riuscita, 4 centraline al `0100`; stacco LX e schermo spento rinviati (D-013).

## Problema aperto
Nessuno sa se la Yaris 2026 accetta le letture dirette dei DID di Hybrid Assistant: HA legge `1067`/`1069` solo via DDDI
(`2C01`), che il vincolo di sola lettura esclude.

## Prossimo passo
**Prima guida con la 0.3.0** (`README.md`, sezione "Guida"): già da fermo la riga delle centraline dice se 7D2/747/700
rispondono; poi frenate leggere e decise sopra i 15 km/h ed esportazione del file `guida-….jsonl`, che diventerà un test.
Se la 7D2 rifiuta `221067`/`221069`: decidere con il proprietario tra DDDI (percorso B, nuova decisione) e i candidati 7B0.
