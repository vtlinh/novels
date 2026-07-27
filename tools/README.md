# The dev fetch Worker

`worker.js` at the repo root is a small, authenticated fetch proxy for the two
novel sites. **The app does not use it and must not** — it fetches sites
directly, which is the point of it being a native app. This exists so work on
the app can read a real page: capturing the test fixtures in
`android/app/src/sites/<site>/test/resources/`, checking what a site's HTML actually
contains before a selector changes, settling a question about a site instead
of guessing at it.

It is the Worker that served the retired web front-end (#102), cut back to
that one job. The `/browse` iframe shim, the Anthropic API proxy and its batch
long-poll went with the web app.

## Deploy

```sh
npx wrangler deploy                  # from the repo root
npx wrangler secret put FETCH_TOKEN  # paste a long random string
```

Generate a token with `openssl rand -hex 24`. Never put it in `wrangler.toml`
— that file is committed. `wrangler secret put` stores it on the Worker, where
the code reads it as `env.FETCH_TOKEN`; without it the Worker refuses every
request.

The name in `wrangler.toml` is kept as `truyenfull`, so the deploy lands on the
existing Worker and its `truyenfull.vtlinh87.workers.dev` URL rather than
minting a new one. If Workers Builds is still connected to this repo, pushes to
`main` deploy it and the `wrangler deploy` above is unnecessary.

## Use

```sh
export NOVELS_WORKER=https://truyenfull.vtlinh87.workers.dev
export NOVELS_TOKEN=…

tools/page.sh https://truyenfull.today/tu-tien/            # page to stdout
tools/page.sh https://novelfull.com/the-mech-touch.html o.html
tools/page.sh -m urls.txt fixtures/                        # a whole set
```

Or directly:

```sh
curl -H "x-fetch-token: $NOVELS_TOKEN" \
  --get --data-urlencode "url=https://truyenfull.today/tu-tien/" \
  "$NOVELS_WORKER/" -o page.html

curl -H "x-fetch-token: $NOVELS_TOKEN" \
  --get --data-urlencode "url=https://novelfull.com/x.html" \
  "$NOVELS_WORKER/?head=1"        # metadata only: status, bytes, challenge
```

`&strip=1` drops scripts, styles and comments (well over half the bytes) —
useful for reading, wrong for capturing a fixture, which has to be the page as
served. `&fresh=1` skips the five-minute edge cache. `POST /fetch-many` takes
up to 50 URLs in one call.

## Challenges

novelfull.com answers a direct `curl` with Cloudflare's JS interstitial, and
the expectation when this was written was that the Worker would get the same —
its `fetch()` runs no JavaScript and carries no browser fingerprint. **It does
not: the deployed Worker reaches novelfull.com normally.** Measured, not
assumed; `?head=1` returns `"challenge": false` and the page parses.

What *is* challenge-protected is individual novels. Several truyenfull titles
(the `-free` suffixed ones) return a 403 interstitial to every client,
including the app — so those novels cannot be downloaded at all, and the
capture scripts record which ones rather than retrying them.

Either way the Worker refuses to pass an interstitial off as a page: it comes
back as **409** with `x-challenge: 1` and an empty body rather than 200 with an
error page. A challenge page has a length and the shape of a real document, and
a caller that doesn't look inside will happily save it as a novel page — which
is exactly how a "downloaded" chapter turns out to be an error page.

If a site does start challenging the Worker, `worker-render.js` is the way
through: same proxy, but `&render=1` runs real Chromium on Cloudflare's edge
(Browser Rendering), so the page's own scripts run and the challenge clears as
it would in a browser. It needs the Workers Paid plan and one package:

```sh
npm i @cloudflare/puppeteer
# wrangler.toml:  main = "worker-render.js"
#                 [browser]
#                 binding = "BROWSER"
```

Then `&render=1&wait=%23list-chapter` returns as soon as the chapter list
exists rather than after a fixed sleep.

## Guards

The Worker is authenticated and host-restricted in code:

- a shared secret on every request, compared in full so a near-miss can't be
  found a character at a time by timing;
- an allowlist regex — only the two novel sites' hosts, https only. A leaked
  token would otherwise make it everyone's proxy;
- an 8 MB cap, a five-minute edge cache so repeated reads don't hammer a site,
  and no cookie or request-body forwarding.

`node worker.test.mjs` checks those decisions offline with `fetch` stubbed:
twelve cases covering a missing/wrong/near-miss token, four kinds of
out-of-allowlist URL, a challenge page, a 404, `head=1`, both `/fetch-many`
paths, and a Worker with no secret set. It caught a real bug when it was
written — a non-ASCII character in a response header, which throws, so every
challenged page would have come back as a bare 500 instead of the diagnostic.

Keep the allowlist tight and the token private; fetch only what you would fetch
in a browser yourself.
