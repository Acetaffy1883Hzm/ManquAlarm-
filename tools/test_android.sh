#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/native-screenshots
test_status=0
gradle --no-daemon --console=plain :app:connectedDebugAndroidTest || test_status=$?
adb logcat -d -s AndroidRuntime:E > build/native-screenshots/crashes.log || true
capture_status=0
adb pull /sdcard/Download/ManquAlarm-native-screenshots build/native-screenshots || capture_status=$?
if [ "$test_status" -ne 0 ]; then
  exit "$test_status"
fi
if [ "$capture_status" -ne 0 ]; then
  exit "$capture_status"
fi
for screenshot in 01-home-pink 02-appearance-blue 03-custom-background 04-native-schedule-dark 05-appearance-dark 06-native-alarm; do
  test -s "build/native-screenshots/ManquAlarm-native-screenshots/$screenshot.png"
done
