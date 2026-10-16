# Feedback 1.4.3: fixes and regression checks

The four reports in `gitlab_feedback/betterlauncher-1.4.3-report.md` were treated
as evidence, not instructions. The accessibility disclosure also uses the app’s
rounded dialog surface, themed typography and scrollable, stacked actions.

## Changes

- Keep the current destination when Android opens a permission dialog or a document
  picker. A Home intent still returns to Home.
- Disable optional contact search after permission denial, preserving app search.
- Report failure when a document provider cannot open a stream.
- Stage preference replacement and reject invalid fields before applying changes;
  reject unknown types, non-finite float values and text scales outside 0.5–1.5.
- Start cooldown from accessibility-observed app departure, not Activity resume.
  Ignore stale window events and additional windows from the same foreground app.

## Runtime checklist

Use the debug APK (separate `.debug` package). Do not clear a personal installation.

1. Enable contact search, deny access, reopen the drawer and restart the app.
   Ordinary app search must remain usable. Re-enable contact search and grant access.
2. Edit settings JSON, save through Files, check that the file contains that text,
   import it, and apply it. Repeat after rotating while the picker is open; cancel
   each picker and verify the editor remains usable.
3. Set text size to 1.5. Import the same backup with `TEXT_SIZE_SCALE.v` changed to
   `"invalid"`. Apply must report failure and preserve all existing settings.
   Restore the valid original and verify success.
4. With accessibility consent and service enabled, limit Clock. Open Clock, then
   return Home: retry count must remain zero. First early open must not extend the
   deadline; the second adds 15 minutes. Wait for natural expiry and verify launch.
   Repeat using Back, notification shade, screen lock and rapid Home/app switching.
5. Check ordinary app launch, Home button, settings navigation and lock/wake for
   regressions from the lifecycle changes.

## Validation performed

Debug and minified release builds succeeded. A fresh API 37 emulator was used to
inspect the consent dialog and run FeedbackRegressionInstrumentation against real
Android SharedPreferences and org.json. Invalid float and unknown-type imports
were rejected, the existing preferences survived, a valid backup restored 1.5,
the first retry preserved the deadline, the second added 15 minutes, and expiry
allowed launch. A UI-selected Clock limit and an actual Clock → Home transition
also left retry count at zero. These checks are automated, not independent human
or physical-device validation. The full checklist above remains useful for wider
coverage, including document providers and Android versions.

Run device checks on a disposable debug installation:

```sh
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/betterlauncher-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/betterlauncher-debug-androidTest.apk
adb shell am instrument -w com.pierspad.betterlauncher.debug.test/app.olauncher.FeedbackRegressionInstrumentation
```

Compilation does not establish reproducibility of the signed release APK.
Additional observations (system font scale, wallpaper contrast, separate icon order
and lock-switch behavior) were not claimed as fixed by these changes.

## F-Droid release procedure

The existing MR is https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44511 .
Its recipe at `pierspad/fdroiddata:add-betterlauncher` currently selects 1.4.3 / 131
and commit `0366ed9615ed39aeb2420672695764ea74ec675b`.

After validation, a `fix:` commit on main triggers the existing semantic-release
workflow. Confirm the actual next version/code in gradle.properties and the
published signed `betterlauncher-release.apk`; do not assume a tag was published.
Update the MR recipe's build versionName, versionCode, commit and CurrentVersion /
CurrentVersionCode to match that actual release. Keep the signing certificate and
Binaries URL configuration. Run the F-Droid pipeline and reproduce the upstream
signed APK from the exact tag before treating the update as ready for review.
AutoUpdateMode Version and UpdateCheckMode Tags are already configured, but the
pending inclusion MR still needs to select the validated release.

References:
- https://gitlab.com/pierspad/fdroiddata/-/raw/add-betterlauncher/metadata/com.pierspad.betterlauncher.yml
- https://f-droid.org/docs/Build_Metadata_Reference/
