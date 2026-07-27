/*
 * Cloudflare Worker for the Novel Downloader — a fetch proxy for DEVELOPMENT.
 *
 * The app does not use this and must not: it fetches novel sites directly,
 * which is the point of it being a native app (no CORS, no middleman). This
 * exists so work on the app can read a real page — capturing the test
 * fixtures in android/app/src/test/resources/pages.zip, checking what a
 * site's HTML actually contains before changing a selector, confirming a
 * claim about a site instead of guessing at it.
 *
 * This is the Worker that used to serve the retired web front-end (#102),
 * cut down to the one job still worth having. Gone with the web app: the
 * /browse iframe shim, the Anthropic API proxy and its batch long-poll (the
 * app talks to Anthropic itself now, and a proxy that forwards someone's API
 * key is not a thing to leave standing for no reason).
 *
 * What replaced the CORS gate: the old Worker was limited to the front-end's
 * Origin, which its own comment admitted was a browser-enforced convention a
 * scripted client can forge. There is no front-end now and the caller IS a
 * script, so it takes a shared secret instead — a real lock.
 *
 *   GET  /?url=<encoded>          the page, bytes untouched
 *        &strip=1                 drop scripts/styles/comments first
 *        &head=1                  metadata only, as JSON
 *        &fresh=1                 skip the edge cache
 *   POST /fetch-many  {"urls":[…]}   up to 50 in one call
 *   GET  /health
 *
 * Token: header `x-fetch-token`, or `?token=` for convenience from a shell.
 *
 * Every reply says what actually happened upstream:
 *   x-upstream-status, x-upstream-url, x-challenge
 *
 * Deploy: pushes to main auto-deploy via the git-connected Worker
 * (wrangler.jsonc). Set the secret once, from the repo root:
 *   npx wrangler secret put FETCH_TOKEN
 */

/* The only hosts this will ever reach. Without it an authenticated proxy is
 * still a proxy, and a leaked token would make it everyone's. */
const SITES = /^https:\/\/(truyenfull\.(today|live|vn)|novelfull\.(com|net))\//i;

/* A browser's headers. Not a disguise — the request really is on our behalf
 * — but these sites serve a different page, or nothing at all, to something
 * that looks like a script. */
const PAGE_HEADERS = {
  "User-Agent":
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/126.0 Mobile Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
  "Accept-Language": "vi,en;q=0.8",
  "Upgrade-Insecure-Requests": "1",
};

const MAX_BYTES = 8 * 1024 * 1024;
const CACHE_SECONDS = 300;

/* Scripts, styles, iframes and comments are usually well over half the bytes
 * of a chapter page, and nothing that reads these pages looks at them. Off by
 * default all the same: a test fixture has to be the page as served, not a
 * version of it we edited. Ask for it with &strip=1 when size matters. */
const stripHtml = resp => {
  if (!(resp.headers.get("content-type") || "").includes("html")) return resp;
  return new HTMLRewriter()
    .on("script, style, link, noscript, iframe, svg", { element(e) { e.remove(); } })
    .onDocument({ comments(c) { c.remove(); } })
    .transform(resp);
};

/* Cloudflare's interstitial is a 403 (sometimes 503) whose body is a JS
 * challenge rather than the page. It has a length and the shape of a real
 * document, so a caller that doesn't look inside saves it as if it were a
 * novel page — which is how a "downloaded" chapter turns out to be an error
 * page. Name it, and give it a status of its own. */
const looksLikeChallenge = (status, body) =>
  (status === 403 || status === 503) &&
  (body.includes("Just a moment") ||
    body.includes("cf-browser-verification") ||
    body.includes("challenge-platform") ||
    body.includes("__cf_chl"));

const deny = (status, message) =>
  new Response(JSON.stringify({ error: message }) + "\n", {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });

/* Compared in full every time, so a wrong token can't be found one character
 * at a time by timing the reply. */
const tokenOk = (given, expected) => {
  if (typeof given !== "string" || typeof expected !== "string") return false;
  if (given.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < given.length; i++) diff |= given.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
};

/* Redirects are followed BY HAND so the allowlist is applied to every hop.
 * `redirect: "follow"` checked only the URL the caller supplied: one open
 * redirect on either novel site — or a hostile response from one — and the
 * proxy fetched anything at all on the caller's behalf, link-local addresses
 * included, which is the single thing the allowlist exists to prevent. It
 * even reported the foreign final host back in x-upstream-url: it had the
 * value and never looked at it. */
const MAX_HOPS = 5;

async function readPage(target) {
  let at = target;
  let r;
  for (let hop = 0; ; hop++) {
    r = await fetch(at, { headers: PAGE_HEADERS, redirect: "manual" });
    if (r.status < 300 || r.status > 399) break;
    const loc = r.headers.get("location");
    if (!loc) break;
    if (hop >= MAX_HOPS) return { resp: r, buf: new ArrayBuffer(0), challenged: false, tooMany: true };
    at = new URL(loc, at).toString();
    if (!SITES.test(at)) return { resp: r, buf: new ArrayBuffer(0), challenged: false, offSite: at };
  }
  const buf = await r.arrayBuffer();
  let sniff = "";
  try {
    sniff = new TextDecoder("utf-8", { fatal: false }).decode(buf.slice(0, 4096));
  } catch { /* not text, so not a challenge page either */ }
  return { resp: r, buf, challenged: looksLikeChallenge(r.status, sniff) };
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
    }

    const secret = env.FETCH_TOKEN;
    if (!secret) return deny(500, "FETCH_TOKEN is not set — npx wrangler secret put FETCH_TOKEN");
    const given = request.headers.get("x-fetch-token") || url.searchParams.get("token") || "";
    if (!tokenOk(given, secret)) return deny(401, "bad or missing token");

    /* Batch capture: one call for a whole set of fixtures. 50 is the free
     * tier's subrequest ceiling. No throttling of our own — Cloudflare paces
     * these (~6 at a time, the rest queue). */
    if (url.pathname === "/fetch-many" && request.method === "POST") {
      let urls = [];
      try { ({ urls } = await request.json()); } catch { /* falls through as an empty list */ }
      const list = (urls || []).filter(u => SITES.test(u)).slice(0, 50);
      const results = await Promise.all(list.map(async u => {
        try {
          const page = await readPage(u);
          if (page.offSite) {
            return { url: u, status: 403, bytes: 0, challenge: false, html: "", error: `redirected off the allowlist: ${page.offSite}` };
          }
          if (page.tooMany) {
            return { url: u, status: 508, bytes: 0, challenge: false, html: "", error: "too many redirects" };
          }
          const { resp, buf, challenged } = page;
          const text = buf.byteLength > MAX_BYTES
            ? ""
            : new TextDecoder("utf-8", { fatal: false }).decode(buf);
          return {
            url: u, status: resp.status, finalUrl: resp.url || u,
            bytes: buf.byteLength, challenge: challenged,
            html: challenged ? "" : text,
          };
        } catch (e) {
          return { url: u, status: 0, bytes: 0, challenge: false, html: "", error: String(e) };
        }
      }));
      return Response.json({ results });
    }

    const target = url.searchParams.get("url");
    if (!target) {
      return new Response(
        "Novel Downloader dev proxy. GET /?url=<page>[&strip=1][&head=1][&fresh=1], " +
        "POST /fetch-many {urls:[…]}, GET /health\n",
        { headers: { "content-type": "text/plain" } },
      );
    }
    if (!SITES.test(target)) return deny(403, "host not allowed");

    const fresh = url.searchParams.get("fresh") === "1";
    const strip = url.searchParams.get("strip") === "1";
    const cache = caches.default;
    const key = new Request(`${url.origin}/cache?u=${encodeURIComponent(target)}&s=${strip ? 1 : 0}`);
    if (!fresh) {
      const hit = await cache.match(key);
      if (hit) {
        const out = new Response(hit.body, hit);
        out.headers.set("x-cache", "hit");
        return out;
      }
    }

    let page;
    try {
      page = await readPage(target);
    } catch (e) {
      return deny(502, `upstream fetch failed: ${e && e.message ? e.message : e}`);
    }
    if (page.offSite) return deny(403, `redirected off the allowlist: ${page.offSite}`);
    if (page.tooMany) return deny(508, "too many redirects");
    const { resp, buf, challenged } = page;
    if (buf.byteLength > MAX_BYTES) return deny(413, "response too large");

    const headers = new Headers();
    headers.set("content-type", resp.headers.get("content-type") || "application/octet-stream");
    headers.set("x-upstream-status", String(resp.status));
    headers.set("x-upstream-url", resp.url || target);
    headers.set("x-challenge", challenged ? "1" : "0");
    headers.set("x-cache", "miss");
    headers.set("cache-control", `public, max-age=${CACHE_SECONDS}`);
    /* A Worker's own fetch runs no JavaScript and carries no browser
     * fingerprint, so a site behind a JS interstitial answers it with the
     * interstitial. This proxy cannot talk its way past that — passing the
     * challenge takes a real browser (Browser Rendering; see the README).
     * Say so, rather than let the caller read it as the site being down. */
    /* ASCII only: a header value is a ByteString, and setting one with a
       character above 255 in it THROWS — so a fancier dash here would have
       turned every challenged page into a 500 with no diagnostic at all,
       which is precisely the case this header exists to explain. */
    if (challenged) headers.set("x-hint", "JS challenge - a plain proxy cannot pass it; see README");

    if (url.searchParams.get("head") === "1") {
      return new Response(
        JSON.stringify({
          status: resp.status,
          url: resp.url || target,
          bytes: buf.byteLength,
          challenge: challenged,
          contentType: resp.headers.get("content-type"),
        }, null, 1) + "\n",
        {
          status: 200,
          headers: { ...Object.fromEntries(headers), "content-type": "application/json; charset=utf-8" },
        },
      );
    }

    /* The upstream status is passed through rather than flattened to 200, so
     * a caller saving whatever comes back can tell a page from a refusal
     * without parsing it. A challenge gets 409 — neither mistaken for the
     * page nor for a transport error worth retrying. */
    const status = challenged ? 409 : resp.status;
    let out = new Response(buf, { status, headers });
    if (strip && status === 200) {
      out = new Response(stripHtml(new Response(buf, { headers })).body, { status, headers });
    }
    if (!fresh && status === 200) ctx.waitUntil(cache.put(key, out.clone()));
    return out;
  },
};
