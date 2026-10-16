# Contributing to BetterLauncher

Bug reports, translations and pull requests are welcome. For substantial UI or behavior changes, open an issue first so we can agree on the scope.

## Report a problem

Include the app version, Android version/device, steps to reproduce, expected behavior and actual behavior. Screenshots or a short recording help with UI problems. Remove personal data from logs and exported settings before sharing them.

## Build locally

Use JDK 17 and install Android SDK platform 36. Configure `sdk.dir` in your untracked `local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew assembleDebug assembleDebugAndroidTest lintDebug
```

The debug package is `com.pierspad.betterlauncher.debug`, separate from the release app. Use an emulator or disposable debug installation when testing launcher defaults, permissions, backups and accessibility.

Run the Android regression checks on a connected emulator:

```sh
adb install -r app/build/outputs/apk/debug/betterlauncher-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/betterlauncher-debug-androidTest.apk
adb shell am instrument -w com.pierspad.betterlauncher.debug.test/app.olauncher.FeedbackRegressionInstrumentation
```

You can also run `bash scripts/run-android-regressions.sh`, which checks the runner output and returns a failing exit code when a regression fails.

The runner isolates its backup/font test data and restores the temporary app-lock selection. Look for every `PASS` line and a successful final instrumentation result; an instrumentation command can exit without reporting an ordinary shell error when a test fails.

Lint currently has inherited resource/translation findings. CI rejects new findings against a committed baseline; warnings are treated as errors. Keep changes focused and do not suppress a new correctness problem merely to make CI pass.

## Change guidelines

- Keep user preferences and old backups compatible. Validate imported data before replacing settings.
- Preserve profile identity and apply the same launch checks to apps and shortcuts.
- Keep provider/disk I/O off the UI thread and tie UI work to the view lifecycle.
- Use immutable snapshots with ListAdapter; do not mutate submitted lists.
- Add regression checks for behavior changes. For UI changes, check light/dark themes, larger text and small screens.
- Add new user-visible strings to the default resources; update translations you can verify.
- Keep investigation reports and local audit notes out of commits. Public documentation belongs in `docs/`; the README and this guide belong in the repository root.

## Pull requests and releases

Explain the problem, resulting behavior and checks performed. Use a conventional commit prefix such as `fix:`, `feat:` or `docs:`. Do not mix unrelated refactors into a bug fix.

A release commit on `main` triggers semantic-release. It updates `gradle.properties` and `CHANGELOG.md`, creates a tag, and publishes the signed APK/AAB. Signing credentials belong in GitHub secrets, never in the repository. Run local checks before pushing; CI verifies the same source before publishing.

F-Droid builds the tagged source and checks it against the published signed APK. Keep the build reproducible and update the pending inclusion recipe when a new release needs review; details are in [docs/f-droid.md](docs/f-droid.md).
