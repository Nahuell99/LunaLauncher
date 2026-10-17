#!/usr/bin/env bash
# Builds and runs the on-device regression checks against a connected emulator/device.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/lunalauncher-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/lunalauncher-debug-androidTest.apk
result=$(adb shell am instrument -w app.nahuell.launcher.debug.test/app.olauncher.FeedbackRegressionInstrumentation)
printf '%s\n' "$result"
# am instrument may exit 0 even when the custom runner reports a failed check.
[[ "$result" != *"FAIL:"* && "$result" != *"INSTRUMENTATION_FAILED"* ]]
[[ $(printf '%s\n' "$result" | grep -c '^PASS:') -ge 9 ]]
