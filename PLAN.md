# PLAN.md — Piano MVP

Legenda: ✅ fatto e verificato · 🟡 fatto, prova sull'auto da eseguire · ⬜ da fare

| Fase | Contenuto | Criterio di completamento | Stato |
|---|---|---|---|
| 1. Progetto manutenibile | Kotlin/Compose, 4 componenti, replay, documenti | APK installabile, build e test verificati, documenti per riprendere | ✅ automatico (build + 23 test JVM) |
| 2. Connessione e registrazioni | Scelta LX, permessi, comunicazione ordinata, timeout/errori, riconnessione, log, export, foreground service | APK che legge parametri base, esporta e recupera da una disconnessione; **prova da fermo sull'auto** | 🟡 codice e test automatici fatti; prova sull'auto da fare |
| 3. Segnali essenziali | HV tensione/corrente, potenza termica, segnali freno, contesto | Matrice in `SIGNALS.md` con evidenze reali e registrazioni riutilizzabili | ⬜ |
| 4. Powermeter | Termico kW, batteria kW (negativo = carica, positivo = scarica) | Dati reali, test delle conversioni, confronto con riferimento indipendente | ⬜ |
| 5. Indicatore frenata | Grigio / Verde / Rosso / Non disponibile | Classificazione riproducibile sulle registrazioni | ⬜ |
| 6. MVP utilizzabile | Schermata principale, dati scaduti, frequenze (freno ≥ 5/s, potenze 2–5/s) | APK, sorgenti, istruzioni, rapporto verifiche | ⬜ |

**Punto di decisione (fase 3):** se mancano dati attendibili per potenza termica o frenata, la fase resta aperta e si
documenta cosa manca. Solo batteria = prototipo parziale, non MVP.

## Criteri finali di accettazione dell'MVP
- Installazione e funzionamento su Galaxy A34 / Android 16.
- Collegamento all'OBDLink LX e gestione di una disconnessione senza blocchi.
- Potenza termica e batteria con significato, unità e segno verificati.
- Tre stati di frenata distinti con evidenze sufficienti.
- "Zero" e "nessuna frenata" separati da "dato non disponibile".
- Sessione continua di 30 minuti e prove di arresto/riavvio della connessione.
- Registrazioni esportate riproducibili nei test.
- Sorgenti, istruzioni di build e limiti conosciuti.

## Prove sull'auto (a cura del proprietario)
1. **Connessione iniziale** (fase 2) — vedi `README.md`, sezione "Prova da fermo". ⬜
2. Acquisizione dei segnali essenziali (fase 3). ⬜
3. Verifica dei due indicatori (fasi 4–5). ⬜
4. Collaudo finale (fase 6). ⬜

Fuori dall'MVP: Android Auto, ventola, salute batteria, consumi avanzati, altre vetture, Play Store.
