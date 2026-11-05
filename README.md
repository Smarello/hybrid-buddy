# Hybrid Buddy

Monitor ibrido per Toyota Yaris Hybrid 115 (2026) via OBDLink LX. Stato attuale: fasi 1–2 (diagnostica di connessione,
parametri OBD di base, registrazione ed esportazione). Per sviluppatori e agenti: `AGENTS.md`.

## Installazione
1. Build: `scripts/archetipo/toolchain.sh run ./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
2. Copia l'APK sul Galaxy A34 e installalo (consenti l'installazione da origine sconosciuta), oppure
   `scripts/archetipo/toolchain.sh run adb install -r app/build/outputs/apk/debug/app-debug.apk`.
3. Associa l'OBDLink LX dalle impostazioni Bluetooth di Android (se non lo è già).

## Prova da fermo (fase 2)
1. Auto in READY o quadro acceso, LX inserito. Apri Hybrid Buddy → **Dispositivo** → scegli l'LX → **Avvia acquisizione**
   (concedi Bluetooth e notifiche).
2. Attendi che la catena sia verde: `Telefono → LX` collegato, `LX → Auto` auto raggiunta, `Segnali 5/5`.
3. Spegni lo schermo per 1 minuto, riaccendi e riapri l'app: i valori devono essere aggiornati (età < 1 s).
4. Stacca l'LX per ~10 s e reinseriscilo: la catena passa a "riconnessione…", i valori a "Non disponibile", poi
   l'acquisizione riprende da sola. La notifica resta presente.
5. Spegni il quadro: `LX → Auto` deve indicare "auto non risponde".
6. **Ferma** (o "Termina" dalla notifica) → **Esporta sessione** → condividi il file `.jsonl` con lo sviluppatore.

Annota cosa hai visto ai punti 2–5: insieme alla registrazione è l'evidenza della prova.

## Replay
**Apri registrazione** → "Esempio SIMULATO" (dati inventati, non dell'auto) o "Da file…" per una sessione esportata.
In replay il bollino in alto e la riga "Sorgente: … (non è l'auto)" lo segnalano sempre.

## Limiti conosciuti
- Mostra solo PID OBD-II standard: potenze e frenata arrivano nelle fasi 3–5.
- Parsing limitato a risposte CAN 11-bit a frame singolo (sufficiente per i PID attuali).
- Timeout, scadenza (2 s) e attese di riconnessione sono valori iniziali da tarare sull'auto.
- Se si preme Ferma mentre il telefono sta ancora cercando di collegarsi all'LX, l'arresto può richiedere ~12 s.
