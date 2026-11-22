# NEURALE — Edicola

App web (PWA) che raccoglie tutte le edizioni della rivista quotidiana NEURALE.
Si apre da Safari, si aggiunge alla schermata Home e funziona anche offline.

- `editions.json` — elenco delle edizioni (la più recente viene aggiunta ogni mattina)
- `edizioni/AAAA-MM-GG/` — le 13 pagine di ogni edizione in WebP + `thumb.webp`
- `sw.js` — service worker per la lettura offline

## App Android

Cartella `android/`: app Kotlin che archivia i PDF della rivista (Condividi / Apri con → NEURALE) e li sfoglia offline.
L'APK viene compilato da GitHub Actions: https://github.com/Fambokki/Neurale/releases/latest/download/NEURALE.apk
