# F-Droid

BetterLauncher inclusion is tracked in [fdroiddata !44511](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/44511). The authoritative recipe lives in fdroiddata; this repository does not keep an outdated duplicate.

The recipe uses `AutoUpdateMode: Version`, stable version tags and the version fields in `gradle.properties`. Once inclusion is merged, F-Droid can detect new stable tags automatically. Detection, building, reproducibility checks and repository publication happen on F-Droid's schedule, not immediately after a GitHub release.

While the inclusion merge request is pending, select the release under review explicitly: update the build version/name/code/commit and CurrentVersion fields, preserve the signing certificate, then run metadata formatting, lint and build checks before pushing.

Use the fdroidserver revision **and dependency versions** from GitLab CI when checking metadata formatting. Different ruamel.yaml versions can wrap the same long URL differently. A local formatter pass with different dependencies is not sufficient.

The `Binaries` URL points to `betterlauncher-release.apk` in the matching GitHub release. The locally built unsigned APK must reproduce that signed APK after signature copying. Do not publish a new signing key or include build timestamps, changing VCS metadata or machine paths in release artifacts.
