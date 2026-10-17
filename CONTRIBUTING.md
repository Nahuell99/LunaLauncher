# Contributing to LunaLauncher

Bug reports, translations and pull requests are welcome. For substantial UI or behaviour
changes, open an issue first so we can agree on the scope.

## Report a problem

Include the app version, Android version/device, steps to reproduce, expected behaviour and
actual behaviour. Screenshots or a short recording help with UI problems. Remove personal data
from logs and exported settings before sharing them.

## Build locally

Use JDK 17 and install Android SDK platform 36. Configure `sdk.dir` in your untracked
`local.properties`, or set `ANDROID_HOME`.

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug package is `app.nahuell.launcher.debug`, separate from a release install. Use an
emulator or a disposable debug installation when testing launcher defaults, permissions,
backups and accessibility.

Run the on-device regression checks on a connected emulator:

```sh
bash scripts/run-android-regressions.sh
```

The runner isolates its backup/font test data and restores the temporary app-lock selection.
An instrumentation command can exit without an ordinary shell error when a check fails; the
script checks the output for you.

Lint runs with warnings as errors against a committed baseline of inherited findings. Do not
add new findings, and do not suppress a real problem just to make lint pass.

## Change guidelines

- Keep user preferences and old backups compatible. Validate imported data before replacing settings.
- New features are opt-in: off by default and configurable from Settings.
- No Internet permission.
- Preserve profile identity and apply the same launch checks to apps and shortcuts.
- Keep provider/disk I/O off the UI thread and tie UI work to the view lifecycle.
- Add unit tests for pure logic (`app/src/test`). For UI changes, check light/dark themes, larger text and small screens.
- New user-visible strings go to `res/values/strings_nahuel.xml` (English) and the Spanish
  variants; other languages fall back to English.

## Releases

There is no automated release pipeline. To publish a version: bump `versionCode` and
`versionName` in `gradle.properties`, build a signed release APK with your own keystore
(never committed), tag the commit and attach the APK to a GitHub release. Add the changes to
[CHANGELOG.md](CHANGELOG.md).
