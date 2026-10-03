#!/usr/bin/env bash
# Emulator smoke test: installs the debug APK, walks the main flow in demo mode,
# saves screenshots and fails if the app crashes.
set -u
PKG=com.onefera.app.debug
FAILED_STEPS=0
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

# Emulators sometimes show "<System app> isn't responding" dialogs; tap "Wait" to dismiss them.
dismiss_system_dialogs() {
  if grep -q "isn&apos;t responding\|isn't responding" /tmp/ui.xml 2>/dev/null; then
    echo "dismissing a system 'not responding' dialog"
    python3 - <<'PY' | { read -r x y && timeout 10 adb shell input tap "$x" "$y"; }
import re
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
for m in re.finditer(r'<node [^>]*text="Wait"[^>]*>', xml):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
    if b:
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
    sleep 2
    timeout 20 adb exec-out uiautomator dump /dev/tty 2>/dev/null > /tmp/ui.xml || true
  fi
}

# Taps the centre of the first UI node whose text (or content-desc) matches $1.
tap_text() {
  for _ in 1 2 3 4; do
    device_ok || return 1
    timeout 20 adb exec-out uiautomator dump /dev/tty 2>/dev/null > /tmp/ui.xml || true
    dismiss_system_dialogs
    bounds=$(python3 - "$1" <<'PY'
import re, sys
xml = open('/tmp/ui.xml', encoding='utf-8', errors='ignore').read()
want = sys.argv[1]
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); d = re.search(r'content-desc="([^"]*)"', n)
    def match(v):
        return v.startswith(want[:-1]) if want.endswith('*') else v == want
    if (t and match(t.group(1))) or (d and match(d.group(1))):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
    if [ -n "$bounds" ]; then timeout 10 adb shell input tap $bounds; echo "tapped '$1'"; return 0; fi
    timeout 10 adb shell input swipe 540 1600 540 900 300; sleep 1
  done
  echo "::error::could not find '$1'"; FAILED_STEPS=$((FAILED_STEPS + 1)); return 1
}

# Let the freshly booted system settle so launcher/system ANR dialogs don't cover the app.
sleep 30
timeout 120 adb install -r app/build/outputs/apk/debug/app-debug.apk || finish 1
timeout 60 adb shell am start -W -n "$PKG/com.onefera.app.MainActivity"
shot 01-onboarding 6
tap_text "Next";                      shot 02-onboarding-shop
tap_text "Next";                      shot 03-onboarding-sell
tap_text "I already have an account"; shot 04-sign-in
tap_text "Fill demo login";           sleep 1
tap_text "Log in";                    shot 05-feed 8
timeout 10 adb shell input swipe 540 1700 540 700 400; shot 06-feed-scrolled 4
tap_text "Comments*";                 shot 07-comments 4
timeout 10 adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "Reels";                     shot 08-reels 8
tap_text "Search";                    shot 09-search 4
tap_text "Home";                      sleep 2
tap_text "Notifications*";            shot 10-notifications 4
timeout 10 adb shell input keyevent KEYCODE_BACK; sleep 1
tap_text "Chats";                     shot 10b-chats 4
tap_text "Aanya Rao";                 shot 10c-chat 4
tap_text "lowkey yes";                shot 10d-chat-reply 7
timeout 10 adb shell input keyevent KEYCODE_BACK; sleep 2
tap_text "You";                       shot 11-profile
tap_text "Profile menu";              shot 12-profile-menu
tap_text "Settings";                  shot 13-settings
tap_text "Sunset Pop";                shot 14-settings-sunset-pop
timeout 10 adb shell input keyevent KEYCODE_BACK; shot 15-profile-sunset-pop
tap_text "Shop";                      shot 16-shop-upcoming

if ! timeout 10 adb shell pidof "$PKG" >/dev/null; then
  echo "::error::App process is not running at the end of the smoke test"
  tail -100 "$OUT/logcat.txt"
  finish 1
fi
if [ "$FAILED_STEPS" -gt 0 ]; then
  echo "::error::$FAILED_STEPS smoke-test step(s) could not be completed"
  finish 1
fi
echo "Smoke test passed"
finish 0
