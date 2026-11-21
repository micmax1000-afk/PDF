#!/bin/bash
# Prova dell'app su un telefono virtuale: installa, apre le schermate principali con un PDF fatto sul momento,
# controlla il motore (firma, evidenziatore, pagine, comprimi, password) e fa tocchi casuali.
set -u
APK=${1:-app-debug.apk}
PKG=com.micmax.pdf
OUT=ui-shots
mkdir -p "$OUT"
adb wait-for-device
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r -g "$APK" || exit 1
adb logcat -c
shot() { sleep "${2:-4}"; adb exec-out screencap -p > "$OUT/$1.png"; echo "schermata: $1"; }
alive() { adb shell pidof $PKG >/dev/null; }
why() { adb logcat -d -s AndroidRuntime:E | tail -60; adb logcat -d -s AndroidRuntime:E | tail -60 > "$OUT/crash.txt" || true; }
start() { adb shell am start -W -n $PKG/.MainActivity "$@" >/dev/null; }

start
shot 01-home 6
alive || { echo "::error::L'app si e' chiusa all'avvio"; why; exit 1; }

# motore: firma, evidenzia, pagine, comprimi, password, sblocco, oscura, numeri e filigrana
start --es action selftest
for i in $(seq 1 30); do sleep 2; adb logcat -d -s PDF_SELFTEST:* | grep -q 'PDFEDIT_' && break; done
PE=$(adb logcat -d -s PDF_SELFTEST:* | grep -o 'PDFEDIT_.*' | tail -1)
echo "Motore PDF: $PE"
case "$PE" in
  PDFEDIT_OK*password=true*sblocco=true*compressa=2*oscura=true*decora=true*) ;;
  *) echo "::error::Il motore dei PDF non funziona ($PE)"; adb logcat -d -s AndroidRuntime:E | tail -30; exit 1;;
esac

for a in viewer editor pages split mydata select; do
  start --es action $a
  shot "02-$a" 7
  alive || { echo "::error::L'app si e' chiusa nella schermata $a"; why; exit 1; }
  adb shell input keyevent KEYCODE_BACK
  adb shell input keyevent KEYCODE_BACK
  sleep 1
done

# ricerca nel testo
start --es action search
for i in $(seq 1 20); do sleep 2; adb logcat -d -s PDF_SELFTEST:* | grep -q 'SEARCH=' && break; done
SR=$(adb logcat -d -s PDF_SELFTEST:* | grep -o 'SEARCH=[0-9]*' | tail -1)
echo "Ricerca: $SR"
[ "$SR" = "SEARCH=3" ] || [ "$SR" = "SEARCH=9" ] || { echo "::error::La ricerca nel PDF non trova il testo ($SR)"; exit 1; }
shot 03-ricerca 3

# selezione del testo: le parole della pagina di prova con la loro posizione
WD=$(adb logcat -d -s PDF_SELFTEST:* | grep -o 'WORDS=[0-9]*' | tail -1)
echo "Selezione: $WD"
[ "${WD#WORDS=}" -ge 20 ] 2>/dev/null || { echo "::error::Non trovo le parole da selezionare ($WD)"; exit 1; }

# tocchi casuali
adb shell monkey -p $PKG --throttle 150 --ignore-timeouts --pct-syskeys 0 --pct-appswitch 0 -s 42 300 >"$OUT/monkey.txt" 2>&1
tail -3 "$OUT/monkey.txt"
alive || { echo "::error::L'app si e' chiusa durante i tocchi casuali"; why; exit 1; }
echo "Prova completata"
