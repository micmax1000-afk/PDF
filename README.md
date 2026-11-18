# PDF e fogli

App Android per lavorare sui PDF senza internet e senza AI: tutto resta sul telefono.

- **Apri e leggi** un PDF (zoom con due dita o doppio tocco), cerca una parola dentro il PDF o in tutti i PDF recenti
- **Firma** col dito (la firma viene ricordata), aggiungi testo, data, note e **evidenzia**
- **Compila** i moduli (campi veri del PDF)
- **Pagine**: ruota, sposta, togli, aggiungi un altro PDF o una foto; **Dividi** (tieni solo alcune pagine); **Unisci** più PDF
- **Scansiona** un foglio (bordi, raddrizzamento e filtri con lo scanner di Google), anche più pagine in un PDF
- **Comprimi** (per email/WhatsApp), **proteggi con password**, apri PDF con password, **copia il testo**
- Ogni modifica crea un **nuovo PDF**: l'originale non viene toccato
- **Tema** chiaro, scuro o come il telefono (🎨 in alto nella home)
- «Apri con» e «Condividi» da altre app; «Con l'AI» manda il PDF a Web Launcher

## Costruzione

```
gradle testDebugUnitTest assembleDebug
```

Su GitHub la costruzione parte a ogni modifica; sul ramo `main` crea la release con l'APK.
Per avere aggiornamenti installabili sopra la versione precedente servono i secret
`PDF_KEYSTORE_BASE64` e `PDF_KEYSTORE_PASSWORD` (la stessa chiave di Web Launcher va bene; alias `weblauncher`).

## Limiti

- Il testo già scritto in un PDF non si può cambiare: si aggiunge sopra.
- «Compila» funziona solo sui moduli con campi veri; sui fogli scansionati si scrive sul foglio.
- La firma col dito è un'immagine, non una firma digitale qualificata.
- Cercare nelle foto/scansioni richiede il riconoscimento del testo (non ancora incluso).
