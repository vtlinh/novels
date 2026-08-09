# TinyTube

Kid-safe YouTube viewer — a child only ever sees videos from parent-approved
channels. No search, no suggestions, no external navigation, no backend.

Copied from [pathikrit/TinyTube](https://github.com/pathikrit/TinyTube)
(upstream commit `cbab6b8`, 2026-08-08) and kept here as a self-contained
subdirectory. The upstream repository carries no licence file; the copy is
kept verbatim apart from the deploy wiring below.

```sh
npm install && npm run dev
```

Published at **https://vtlinh.github.io/novels/tinytube/** by
`.github/workflows/pages.yml` at the repository root, which stages this app's
`dist/` alongside the Novel Downloader landing page into the single Pages
deployment a repository gets. `.github/workflows/tinytube.yml` runs the tests
and a build on every pull request that touches this directory.

Two things differ from upstream:

- `vite.config.js`'s `BASE_PATH` default is `/novels/tinytube/`, not
  `/TinyTube/` (CI passes the value explicitly either way).
- Upstream's `.github/workflows/deploy.yml` is not copied — `pages.yml` at the
  repository root does that job for both sites at once.

The curated gallery needs a `YOUTUBE_API_KEY` repository secret (Settings →
Secrets and variables → Actions): the weekly `pages` run uses it to rebuild
`videos.json` from `channels.json`. Without it the workflow republishes the
last deployed `videos.json`, or ships an empty channel list on a first run —
the app still loads, and a parent can add channels with their own key under
Parents → Settings. No key is ever shipped to the browser.

See [AGENTS.md](AGENTS.md) for architecture and development details.
