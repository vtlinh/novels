# Repo notes for Claude

- This repo is the **native Android app** (`android/`, Kotlin) plus a static
  GitHub Pages landing page (`index.html`) whose only job is the app-download
  button. The old browser-based downloader and its Cloudflare Worker proxy
  were removed — all features live in the app now.
- `worker.js` + `wrangler.toml` are that Worker brought back for DEVELOPMENT
  ONLY — an authenticated, host-restricted fetch proxy so a session with no
  browser can read a real page (capturing the pages under each site's
  `android/app/src/sites/<site>/test/resources/`, checking a selector against
  what a site actually serves). The app must never call it. `tools/README.md` covers
  deploy and use; `node worker.test.mjs` checks its guards offline. It DOES
  reach novelfull.com, which answers a direct curl with a JS interstitial —
  measured, after an earlier note here predicted the opposite. What is
  challenge-protected is individual novels (truyenfull's `-free` titles), and
  those are unavailable to the app too. A challenge is reported as a 409 rather
  than returned as if it were the page; `worker-render.js` (Browser Rendering,
  paid plan) is the fallback if a whole site ever starts refusing.
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

## Adding a supported site

- **Capture the pages BEFORE writing the adapter.** Always, and in that order.
  An adapter written first is a guess at markup nobody has looked at, and the
  tests written alongside it only prove the parser handles the shape whoever
  wrote it imagined. Every selector, every URL rule, every "this site marks a
  finished novel like so" is a claim about real HTML, and the only way to make
  it a measurement is to have the HTML in hand first.
  Ordering it the other way has cost real defects here: a chapter container
  two selectors both matched, a title wrapped in site furniture that would
  have become a folder name for good, and a heading printed twice inside a
  single line — each found the moment real pages arrived, none of them
  reachable by reasoning.

- **Everything about a site lives in that site's own directory** — its
  adapter, its tests, and the real pages it is judged against:

  ```
  android/app/src/sites/<site>/
      main/<Site>.kt                          the Site implementation
      test/java/<Site>Test.kt                 its SiteContract subclass
      test/resources/pages/<site>/            its captured pages
          manifest.tsv  chapters.tsv          measured independently
          *.html.zip  chapters/*.html.zip
  ```

  `app/build.gradle.kts` discovers these roots from the filesystem, so adding
  a site is a new directory and removing one is a deleted directory — no list
  to keep in step. Nothing outside a site's directory may hold a selector for
  it, and nothing inside it belongs to any other site: that knowledge used to
  be spread over four files with the fixtures in a fifth and one shared
  manifest every site had to be edited into, and a selector added for one
  site silently changed what the others extracted.

- The adapter implements the `Site` interface; its test subclasses
  `SiteContract`, which asks every question the engine asks, so a site cannot
  be added without answering all of them.

- **The site is not done until its pages are captured.** Per site:
  - **100 novel pages**, deliberately mixed: completed AND in progress, short
    AND long — a handful of chapters up to thousands. A corpus of only
    finished, only popular novels tests one shape and misses the rest; the
    first attempt here captured 88 completed novels in a row before anyone
    noticed the candidate ordering.
  - **30 chapter pages**, spread ACROSS those novels rather than taken from
    one — first chapters, middle chapters, chapters from long books and from
    short ones.
  - **Every page compressed when saved**: one HTML per zip under the site's
    own `test/resources/pages/<site>/`
    (`tools/pack-pages.py <dir> <site>` packs them). Compressed so whole
    third-party pages stay out of repository search and off every clone's
    disk; one per archive so the page being debugged can be extracted alone.

- **Every one of a site's tests must pass against every page captured for it.**
  Not a sample of them, and not with exceptions carved out for pages that
  fail — a page the adapter cannot read is either a bug to fix or a shape to
  handle, and carving it out hides which.

- Capture per HOST, not per site. Two hosts serving "the same application" can
  still differ in ways that matter: novelfull.com serves no `og:title` at all
  while novelfull.net wraps one in site furniture, and a corpus holding only
  `.com` pages could not see it.

- Add a row per page to that site's own `manifest.tsv` (and chapter pages to
  its `chapters.tsv`), and measure its columns with a script that does NOT use
  this app's parser. The tests compare the app against that independent
  measurement; filled in from `Listing.collect`, they would only prove the
  code agrees with itself.

- Fetch through the dev Worker (`tools/README.md`), adding the host to its
  `SITES` allowlist first, and pace the crawl — these sites challenge a burst,
  and some individual novels are challenge-protected for every client
  (truyenfull's `-free` titles), which is worth recording rather than
  retrying. Four of the sites on the wish list answer a plain fetch with a JS
  challenge and need the Worker; five do not.

- Never test against hand-written HTML. It only proves the parser handles the
  shape someone imagined. Real pages have found, in one run each: a chapter
  container that two selectors both matched, a title wrapped in site
  furniture that would have become a folder name for good, and a heading
  printed twice inside a single line.

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
