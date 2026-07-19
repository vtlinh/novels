# Repo notes for Claude

- `index.html` is a browser-based truyenfull/novelfull novel downloader
  (no build step, no CI). Bump the `VERSION` constant in `index.html` on every
  user-facing change so a stale cached page is detectable.
- PWA shell: `manifest.webmanifest`, `sw.js` (network-first; only an offline
  fallback — never let it cache-first index.html), and `icon-*.png` make the
  page installable and an Android share target. `worker.js` is the Cloudflare
  Worker proxy (auto-deploys from main).
- `android/` is a native Kotlin port (in progress) — kept alongside the web
  app, which stays live. It fetches sites directly (no Worker/CORS). It can't
  be compiled in this sandbox (Google's SDK host is blocked); the
  `.github/workflows/android.yml` action builds the debug APK on every push
  touching `android/` and uploads it as the `novel-downloader-debug-apk`
  artifact. Check that workflow's result after pushing android changes.

## Workflow

- After completing a fix, always merge it: push the branch, open the PR
  (ready, not draft), and squash-merge immediately — do not wait for the user
  to ask.
