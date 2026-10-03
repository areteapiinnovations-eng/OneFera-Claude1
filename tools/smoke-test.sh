#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, walks the main flow in demo mode,
# saves screenshots and fails if the app crashes.
set -u
PKG=com.onefera.app.debug
OUT=smoke-screenshots
mkdir -p "$OUT"
adb logcat -c
adb logcat -v time > "$OUT/logcat.txt" 2>/dev/null &

device_ok() { timeout 10 adb get-state >/dev/null 2>&1; }

finish() {
  # Thumbnails are echoed into the job log so they can be reviewed without downloading artifacts.
  python3 tools/log-thumbnails.py "$OUT" || true
  if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
    echo "::error::App crashed during smoke test"
    grep -A 40 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -80
    exit 1
  fi
  exit "$1"
}

shot() {
  sleep "${2:-3}"
  if ! device_ok; then
    echo "::error::Emulator became unreachable before screenshot $1"
    tail -60 "$OUT/logcat.txt"
    finish 1
  fi
  timeout 20 adb exec-out screencap -p > "$OUT/$1.png"
  echo "screenshot $1"
}

# Taps the centre of the first UI node whose text (or content-desc) matches $1.
tap_text() {
  for _ in 1 2 3 4; do
    device_ok || return 1
    timeout 20 adb exec-out uiautomator dump /dev/tty 2>/dev/null > /tmp/ui.xml || true
    bounds=$(python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
    if (t and t.group(1) == want) or (d and d.group(1) == want):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
    if [ -n "$bounds" ]; then timeout 10 adb shell input tap $bounds; echo "tapped '$1'"; return 0; fi
    timeout 10 adb shell input swipe 540 1600 540 900 300; sleep 1
  done
  echo "::warning::could not find '$1'"; return 1
}

timeout 120 adb install -r app/build/outputs/apk/debug/app-debug.apk || finish 1
timeout 60 adb shell am start -W -n "$PKG/com.onefera.app.MainActivity"
shot 01-onboarding 6
tap_text "Next";                      shot 02-onboarding-shop
tap_text "Next";                      shot 03-onboarding-sell
tap_text "I already have an account"; shot 04-sign-in
tap_text "Fill demo login";           sleep 1
tap_text "Log in";                    shot 05-home 6
tap_text "You";                       shot 06-profile
tap_text "Profile menu";              shot 07-profile-menu
tap_text "Settings";                  shot 08-settings
tap_text "Sunset Pop";                shot 09-settings-sunset-pop
timeout 10 adb shell input keyevent KEYCODE_BACK; shot 10-profile-sunset-pop
tap_text "Shop";                      shot 11-shop-upcoming

if ! timeout 10 adb shell pidof "$PKG" >/dev/null; then
  echo "::error::App process is not running at the end of the smoke test"
  tail -100 "$OUT/logcat.txt"
  finish 1
fi
echo "Smoke test passed"
finish 0
