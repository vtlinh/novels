/*
 * worker.js, plus a real browser — for the sites that answer a plain fetch
 * with a JS interstitial instead of the page.
 *
 * Use it only if worker.js comes back with x-challenge: 1. A Worker's own
 * fetch() executes no JavaScript and carries no browser fingerprint, so a
 * site behind an interstitial answers it with the interstitial, and no
 * amount of header-setting changes that. Browser Rendering runs actual
 * Chromium on Cloudflare's edge: the page's scripts run, the challenge
 * resolves the way it does in a browser, and what comes back is the
 * rendered DOM.
 *
 * It costs more in every sense — Workers Paid plan, seconds rather than
 * milliseconds, metered per session — so it is opt-in per request with
 * &render=1 and everything else takes the ordinary path.
 *
 * To use this instead of worker.js:
 *   npm i @cloudflare/puppeteer
 *   wrangler.toml:  main = "worker-render.js"
 *                   [browser]
 *                   binding = "BROWSER"
 *
 * &wait=<css selector> returns as soon as that element exists (for these
 * sites, "#list-chapter") rather than after a fixed sleep that is either
 * wasteful or too short.
 */

import puppeteer from "@cloudflare/puppeteer";

const SITES = /^https:\/\/(truyenfull\.(today|live|vn)|novelfull\.(com|net))\//i;

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
const GOTO_TIMEOUT_MS = 45000;
const WAIT_TIMEOUT_MS = 25000;

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

const tokenOk = (given, expected) => {
  if (typeof given !== "string" || typeof expected !== "string") return false;
  if (given.length !== expected.length) return false;
  let diff = 0;
  for (let i = 0; i < given.length; i++) diff |= given.charCodeAt(i) ^ expected.charCodeAt(i);
  return diff === 0;
};

async function renderPage(env, target, waitFor) {
  const browser = await puppeteer.launch(env.BROWSER);
  try {
    const page = await browser.newPage();
    await page.setUserAgent(PAGE_HEADERS["User-Agent"]);
    const res = await page.goto(target, { waitUntil: "domcontentloaded", timeout: GOTO_TIMEOUT_MS });
    if (waitFor) {
      try {
        await page.waitForSelector(waitFor, { timeout: WAIT_TIMEOUT_MS });
      } catch {
        /* never appeared — hand back what there is, so the caller can see
           WHY rather than getting a failure with nothing to look at */
      }
    }
    return { html: await page.content(), status: res ? res.status() : 0, url: page.url() };
  } finally {
    await browser.close();
  }
}

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    if (url.pathname === "/health") {
      return new Response(`ok browser=${env.BROWSER ? "yes" : "no"}\n`, {
        headers: { "content-type": "text/plain" },
      });
    }

    const secret = env.FETCH_TOKEN;
    if (!secret) return deny(500, "FETCH_TOKEN is not set");
    const given = request.headers.get("x-fetch-token") || url.searchParams.get("token") || "";
    if (!tokenOk(given, secret)) return deny(401, "bad or missing token");

    const target = url.searchParams.get("url");
    if (!target) return deny(400, "pass ?url=<encoded url>");
    if (!SITES.test(target)) return deny(403, "host not allowed");

    const render = url.searchParams.get("render") === "1";
    const fresh = url.searchParams.get("fresh") === "1";
    const waitFor = url.searchParams.get("wait") || "";

    const cache = caches.default;
    const key = new Request(
      `${url.origin}/cache?u=${encodeURIComponent(target)}&r=${render ? 1 : 0}`,
    );
    if (!fresh) {
      const hit = await cache.match(key);
      if (hit) {
        const out = new Response(hit.body, hit);
        out.headers.set("x-cache", "hit");
        return out;
      }
    }

    const headers = new Headers();
    headers.set("x-cache", "miss");
    headers.set("cache-control", `public, max-age=${CACHE_SECONDS}`);

    if (render) {
      if (!env.BROWSER) return deny(501, "no BROWSER binding - see the header comment");
      let out;
      try {
        out = await renderPage(env, target, waitFor);
      } catch (e) {
        return deny(502, `render failed: ${e && e.message ? e.message : e}`);
      }
      /* After rendering, "Just a moment" in the DOM means the challenge did
         not clear — reported rather than returned as the page. */
      const stuck = out.html.includes("Just a moment") || looksLikeChallenge(out.status, out.html);
      headers.set("content-type", "text/html; charset=utf-8");
      headers.set("x-upstream-status", String(out.status));
      headers.set("x-upstream-url", out.url);
      headers.set("x-challenge", stuck ? "1" : "0");
      headers.set("x-rendered", "1");
      const response = new Response(out.html, { status: stuck ? 409 : 200, headers });
      if (!fresh && !stuck) ctx.waitUntil(cache.put(key, response.clone()));
      return response;
    }

    let resp;
    let buf;
    try {
      /* By hand, so the allowlist applies to every hop and not only to the
         URL the caller sent — see worker.js. `redirect: "follow"` let one
         open redirect on either site turn this into a proxy for anything. */
      let at = target;
      for (let hop = 0; ; hop++) {
        resp = await fetch(at, { headers: PAGE_HEADERS, redirect: "manual" });
        if (resp.status < 300 || resp.status > 399) break;
        const loc = resp.headers.get("location");
        if (!loc) break;
        if (hop >= 5) return deny(508, "too many redirects");
        at = new URL(loc, at).toString();
        if (!SITES.test(at)) return deny(403, `redirected off the allowlist: ${at}`);
      }
      buf = await resp.arrayBuffer();
    } catch (e) {
      return deny(502, `upstream fetch failed: ${e && e.message ? e.message : e}`);
    }
    if (buf.byteLength > MAX_BYTES) return deny(413, "response too large");
    let sniff = "";
    try {
      sniff = new TextDecoder("utf-8", { fatal: false }).decode(buf.slice(0, 4096));
    } catch { /* not text, so not a challenge page either */ }
    const challenged = looksLikeChallenge(resp.status, sniff);

    headers.set("content-type", resp.headers.get("content-type") || "application/octet-stream");
    headers.set("x-upstream-status", String(resp.status));
    headers.set("x-upstream-url", resp.url || target);
    headers.set("x-challenge", challenged ? "1" : "0");
    headers.set("x-rendered", "0");
    /* ASCII only: a header value is a ByteString and a character above 255
       throws, which would turn the challenge case into a bare 500. */
    if (challenged) headers.set("x-hint", "retry with &render=1");

    const status = challenged ? 409 : resp.status;
    const response = new Response(buf, { status, headers });
    if (!fresh && status === 200) ctx.waitUntil(cache.put(key, response.clone()));
    return response;
  },
};
