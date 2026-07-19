# Repo notes for Claude

- `index.html` is a browser-based truyenfull/novelfull novel downloader
  (no build step, no CI). Bump the `VERSION` constant in `index.html` on every
  user-facing change so a stale cached page is detectable.
- PWA shell: `manifest.webmanifest`, `sw.js` (network-first; only an offline
  fallback — never let it cache-first index.html; also stashes Background
  Fetch results into `bgjob-*` caches, which must survive SW updates), and
  `icon-*.png` make the page installable and an Android share target.
  `worker.js` is the Cloudflare Worker proxy (auto-deploys from main).

## Workflow

- After completing a fix, always merge it: push the branch, open the PR
  (ready, not draft), and squash-merge immediately — do not wait for the user
  to ask.
