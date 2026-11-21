# PDF e fogli

App Android per lavorare sui PDF senza internet e senza AI: tutto resta sul telefono (niente backup su Google Drive).

**Leggere**
- **Apri e leggi** un PDF: zoom con due dita o doppio tocco (le pagine si ridisegnano nitide), **riprende dall'ultima pagina letta**, tocca il numero di pagina per **andare alla pagina** che vuoi
- **Seleziona il testo**: tieni premuto sulla pagina (o «✂ Seleziona testo») e trascina il dito sulle parole; poi copia, condividi o evidenzia proprio quelle righe
- **Cerca** una parola dentro il PDF o in tutti i PDF (accenti e maiuscole non contano: «perche» trova «perché»)
- **📖 Indice** dei capitoli (quando il PDF lo ha) e **🔖 segnalibri** tuoi
- **🔊 Leggi ad alta voce** con la voce del telefono, **🌙 lettura notturna**

**Scrivere sul foglio**
- **Firma** e **sigla** col dito (ricordate, in blu o nero); tocca la sigla per **copiarla su tutte le pagine**
- **Testo**, **data**, **note**, **✓ ✗ ● ◯** per le caselle dei fogli scansionati, **👤 i miei dati** (nome, codice fiscale, indirizzo…) con un tocco
- **Timbri** (Pagato, Ricevuto, Approvato…), **penna**, **frecce**, **riquadri**, **sottolinea**, **barra**, **evidenzia**, in più colori
- **⬛ Oscura**: copre per davvero (la pagina diventa un'immagine, sotto non resta niente)
- **↶ Annulla / ↷ Rifai**; se esci senza salvare l'app chiede prima

**Moduli e pagine**
- **Compila** i moduli (campi veri del PDF), anche con «Riempi con i miei dati»
- **Pagine**: trascina per riordinare, gira, copia, togli, aggiungi un PDF, foto o una **pagina bianca**, **numeri di pagina**, **filigrana**; **Dividi** (tieni solo alcune pagine, anche pari o dispari); **Unisci** più PDF
- **Foto in PDF** (più foto insieme, girate nel verso giusto), **PDF in foto JPG** per WhatsApp
- **Scansiona** un foglio (bordi, raddrizzamento e filtri con lo scanner di Google), anche più pagine in un PDF

**E poi**
- **Comprimi** (per email/WhatsApp), **proteggi con password**, apri PDF con password, **copia il testo**, **🖨 stampa**
- Ogni modifica crea un **nuovo PDF**: l'originale non viene toccato; i PDF dell'elenco non vengono mai cancellati da soli
- **⭐ Preferiti**, ordina per data, nome o dimensione, cerca per nome
- **⚙ Impostazioni**: tema chiaro, scuro o come il telefono; i miei dati; **blocco con impronta o PIN** del telefono
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
- «Compila» funziona solo sui moduli con campi veri; sui fogli scansionati si scrive sul foglio (con ✓ e i miei dati).
- La firma col dito è un'immagine, non una firma digitale qualificata.
- Cercare nelle foto/scansioni richiede il riconoscimento del testo (non incluso: userebbe un modello di apprendimento automatico).
- Le pagine oscurate diventano immagini: lì il testo non si cerca e non si copia più.
