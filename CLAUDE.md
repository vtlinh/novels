# Repo notes for Claude

- This repo is the **native Android app** (`android/`, Kotlin) plus a static
  GitHub Pages landing page (`index.html`) whose only job is the app-download
  button. The old browser-based downloader and its Cloudflare Worker proxy
  were removed — all features live in the app now.
- The app fetches novel sites directly (no proxy/CORS). It can't be compiled
  in this sandbox (Google's SDK host is blocked); the
  `.github/workflows/android.yml` action builds a signed release APK on every
  push touching `android/` and, on `main`, publishes it (plus `version.json`)
  to the fixed `android-latest` GitHub release — the landing page's download
  button and the in-app self-updater both point there. Check that workflow's
  result after pushing android changes.
- `versionCode` is stamped from `GITHUB_RUN_NUMBER`; `android/signing.p12`
  (committed, personal-app trade-off) signs every build so updates install
  over existing installs.

## Workflow

- After completing a fix, always merge it: push the branch, open the PR
  (ready, not draft), and squash-merge as soon as the android workflow is
  green — do not wait for the user to ask.
