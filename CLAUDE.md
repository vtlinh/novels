# Repo notes for Claude

- Single-file app: `index.html` is a browser-based truyenfull novel downloader
  (no build step, no CI). Bump the `VERSION` constant in `index.html` on every
  user-facing change so a stale cached page is detectable.

## Workflow

- After completing a fix, always merge it: push the branch, open the PR
  (ready, not draft), and squash-merge immediately — do not wait for the user
  to ask.
