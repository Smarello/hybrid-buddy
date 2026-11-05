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
- **Non verificato**: rendering della schermata durante mappa e pedale sul telefono, risposte reali di 7E2/7B0 alle richieste mode 21/22,
  durata reale della mappa. Nessun segnale dei freni è ancora noto.

## Problema aperto
Non sappiamo se la Yaris 2026 risponde a mode 21/22 su 7E2 e 7B0, né quali dati descrivano pedale, pressione o rigenerazione.

## Prossimo passo
1. Il proprietario installa la 0.2.1 ed esegue **Esplora freni** da fermo (`README.md`): con «Ripeti solo il pedale»
   riusa la mappa già fatta; poi esporta il file (meglio anche quello della mappa).
2. L'agente analizza la registrazione (identificativi che rispondono, byte che cambiano col pedale), la aggiunge ai
   test e aggiorna `SIGNALS.md`.
3. Si decide con il proprietario: mappa più ampia del mode 22, altre centraline (7E0, 7E3, 7E6) o prova in marcia per la
   rigenerazione.
