# STATUS.md

**Aggiornato:** 2026-11-05 · versione app 0.2.1

## Ultimo risultato verificato
- **Prova da fermo sul Galaxy A34 (0.1.1)**: connessione all'LX riuscita al primo tentativo, 138 s senza errori;
  STN1155 v5.6.19, CAN 11/500, 4 centraline rispondono al `0100` (7E8, 7EA, 7EB, 7EE); valori plausibili al minimo
  (dettagli in `SIGNALS.md`). Registrazione: `core/src/test/resources/recordings/2026-11-05-a34-prova-da-fermo-0.1.1.jsonl`.
  Stacco/riattacco dell'LX e schermo spento **non provati** (rinviati dal proprietario, D-013).
- **0.2.0 — Esplora freni** (fase 3, primo passo): build, lint e test JVM superati, inclusi lista dei comandi consentiti,
  ricomposizione multi-frame, mappa con centralina muta saltata, fasi del pedale registrate, conteggio dei byte che
  cambiano col pedale, mascheramento VIN, interruzione e mancata connessione; la registrazione 0.1.1 è un test permanente.
- **Prova 0.2.0 sull'auto (2026-11-05, dal racconto del proprietario)**: la mappa è arrivata in fondo (~7 minuti), poi
  le fasi del pedale sono partite da sole mentre il proprietario era distratto; «Ripeti» ha rifatto la mappa. Nessun
  file esportato finora. **0.2.1** corregge (D-018): conferma «Inizia il pedale» e «Ripeti solo il pedale» che riusa la
  mappa salvata (anche quelle della 0.2.0). Test JVM: attesa della conferma, riuso della mappa senza richieste di mappa,
  mappa incompleta non riutilizzabile.
- **Prova 0.2.1 (2026-11-05)**: «Ripeti solo il pedale» ha riusato la mappa delle 18:35 (66 identificativi, tutti 7B0)
  e completato le quattro fasi; l'app indica 12 identificativi che cambiano col pedale. Candidati forti: `221022`
  (pressione alle 4 ruote), `221070` (corsa del pedale, 2 sensori), `22104C`, `22102D` (pedale premuto). Dettagli in
  `SIGNALS.md`; la registrazione è un test permanente.
- **Non verificato**: rendering della schermata durante mappa e pedale sul telefono, risposte reali di 7E2/7B0 alle richieste mode 21/22,
  durata reale della mappa. Nessun segnale dei freni è ancora noto.

## Problema aperto
La 7B0 (freni) dà candidati forti per pedale e pressione d'attrito, ma nessun dato distingue ancora la rigenerazione: la
7E2 non risponde nell'intervallo provato e da fermo tutta la frenata è ad attrito.

## Prossimo passo
Da decidere con il proprietario (prossimo incremento): prova in marcia che legge solo i candidati 7B0 (`221022`,
`221070`, `22104C`, `22102D`) più velocità, molte volte al secondo, per vedere rigenerazione e attrito in frenata; oppure
estendere la ricerca della rigenerazione (mode 22 fuori da `1000–1FFF` sulla 7E2, centraline 7E0/7E3/7E6).
