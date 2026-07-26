# Repo notes for Claude

- This repo is the **native Android app** (`android/`, Kotlin) plus a static
  GitHub Pages landing page (`index.html`) whose only job is the app-download
  button. The old browser-based downloader and its Cloudflare Worker proxy
  were removed — all features live in the app now.
- The app fetches novel sites directly (no proxy/CORS). The
  `.github/workflows/android.yml` action builds a signed release APK on every
  push touching `android/` and, on `main`, publishes it (plus `version.json`)
  to the fixed `android-latest` GitHub release — the landing page's download
  button and the in-app self-updater both point there. Check that workflow's
  result after pushing android changes.
- **You can type-check the Kotlin locally** — worth doing before merging a
  large change, since CI is otherwise the first compiler to see it. (An older
  note here claimed the sandbox couldn't compile because Google's SDK host was
  blocked; that is no longer true.) A full Gradle build still won't work, but
  `K2JVMCompiler` on the sources does:
  - Compiler: `/opt/gradle-8.14.3/lib/kotlin-compiler-embeddable-*.jar`, run as
    `java -cp <that + kotlin-stdlib/reflect/daemon/script-runtime + coroutines
    + trove4j + annotations from the same dir> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`.
    `kotlinx-coroutines-core-jvm` must be on the COMPILER's `-cp`, not only the
    target `-classpath`, or it dies with `NoClassDefFoundError: CoroutineScope`.
  - Dependencies: `dl.google.com/dl/android/maven2` (androidx/material `.aar` —
    unzip each and use its `classes.jar`) and `repo1.maven.org/maven2` (okhttp,
    okio, jsoup, coroutines) are both reachable.
  - Android framework: `org.robolectric:android-all:14-robolectric-10818077`
    from Maven Central is a usable API 34 `android.jar`. Caveat: it also exposes
    `@hide` APIs the real one strips, so it won't catch a hidden-API use.
  - Resources aren't compiled, so generate a stub `dev.vtlinh.noveldownloader.R`
    with the fields the sources reference. That means this check can't catch a
    reference to a resource missing from `res/` — that fails at link time in CI.
  - Flags: `-jvm-target 17 -no-stdlib`, pass the whole source directory.
- `versionCode` is stamped from `GITHUB_RUN_NUMBER`; `android/signing.p12`
  (committed, personal-app trade-off) signs every build so updates install
  over existing installs.

## Workflow

- After completing a fix, always merge it RIGHT AWAY: push the branch, open
  the PR (ready, not draft), and squash-merge immediately — do not wait for
  CI or for the user to ask. Fix forward: check the android workflow on main
  after merging and, if red, fix on a fresh branch and merge that the same
  way. A red main build is safe — it just doesn't publish a new APK, the
  previous release stays live.
- The human-facing versionName is computed by the android.yml "Compute
  version name" step as `<year>.<week>.<patch>` and passed to Gradle via the
  `APP_VERSION_NAME` env var (so it's baked into both the APK and
  `version.json`). `year` = years since 2025, 1-indexed (2026 → 1); `week` =
  ISO week of the year (1-indexed); `patch` = running build within that week,
  restarting at 1 the first build of a new week (read back from the previous
  `version.json`). It's fully automatic — nothing to hand-edit per release.
  `versionCode` is still `GITHUB_RUN_NUMBER` (that's what the in-app update
  check compares; versionName may reset freely).
- Always tell the user which app version is being deployed after a merge:
  read the published `version.json`'s `versionName` (or the android.yml run's
  "Compute version name" log) and report `v<versionName>`.
