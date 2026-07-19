/*
 * Cloudflare Worker for the Novel Downloader.
 *
 * Two jobs, both callable only from our own front-end origin (see
 * ALLOWED_ORIGINS) via CORS:
 *
 *   1. Chapter fetching:  GET  <worker>/?url=<encoded truyenfull URL>
 *      Fetches the page server-side and returns its HTML.
 *
 *   2. Claude API proxy:  ANY  <worker>/anthropic/v1/...   ->   https://api.anthropic.com/v1/...
 *      Forwards the request (method, body, x-api-key, anthropic-version,
 *      anthropic-beta) to Anthropic and returns the response. This exists
 *      because Anthropic's Message Batches endpoint does NOT send CORS
 *      headers, so the browser can't call it directly — but a Worker can,
 *      since server-to-server requests aren't subject to CORS.
 *
 * Deploy: pushes to main auto-deploy via the git-connected Cloudflare Worker
 * (see wrangler.toml); pasting into Quick edit at dash.cloudflare.com still
 * works as a manual fallback.
 * The API key travels browser -> your Worker -> Anthropic; it only ever
 * touches infrastructure you control.
 */

// Requests are limited to our own front-end origin(s). Note this is a CORS
// (browser-enforced) gate only: it stops other websites from using the Worker
// inside a visitor's browser, but a scripted client can forge the Origin
// header, so it is not a hard lock against a determined caller.
const ALLOWED_ORIGINS = new Set([
  "https://vtlinh.github.io",
]);

const corsHeaders = origin => ({
  "access-control-allow-origin": origin,
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "x-api-key, anthropic-version, anthropic-beta, content-type",
  "access-control-max-age": "600",
  "vary": "Origin",
});

// The only sites /browse will proxy — keeps it from being an open proxy.
const BROWSABLE_SITES = /^https:\/\/(truyenfull\.(today|live)|novelfull\.com)\//i;

const PAGE_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
  "Accept-Language": "vi,en;q=0.8",
};

// how long a status long-poll holds the connection, and how often it checks
const POLL_WINDOW_MS = 90000;
const POLL_INTERVAL_MS = 10000;
const sleep = ms => new Promise(res => setTimeout(res, ms));

// The front-end only ever reads text content, meta/title, and links out of
// the fetched pages — scripts, stylesheets, iframes, svg, and HTML comments
// (usually well over half the bytes of a chapter page) are dead weight, and
// the client's extractContent() removes script/style itself anyway. Strip
// them here so every page crosses the Worker->browser wire much smaller.
// Transport compression (gzip/brotli) is already automatic on both legs;
// this shrinks the actual content before it's compressed. Only applied to
// HTML responses — anything else passes through untouched.
const stripHtml = resp => {
  if (!(resp.headers.get("content-type") || "").includes("html")) return resp;
  return new HTMLRewriter()
    .on("script, style, link, noscript, iframe, svg", { element(e) { e.remove(); } })
    .onDocument({ comments(c) { c.remove(); } })
    .transform(resp);
};

export default {
  async fetch(request) {
    const origin = request.headers.get("Origin") || "";
    const allowed = ALLOWED_ORIGINS.has(origin);
    const url = new URL(request.url);

    // CORS preflight for any route — only answered for allowed origins.
    if (request.method === "OPTIONS")
      return new Response(null, { headers: allowed ? corsHeaders(origin) : {} });

    // In-app site browser: proxy a supported novel site's page so the
    // front-end can show it in an iframe (the sites forbid direct framing).
    // This route sits BEFORE the origin gate because iframe navigations
    // carry no Origin header; it is NOT an open proxy — only the supported
    // novel sites may be targeted, and frame-ancestors restricts embedding
    // to our own front-end. Scripts/ads are stripped (the pages are
    // server-rendered) and a small shim is injected that (a) reports the
    // page's original URL to the parent app and (b) keeps link navigation
    // inside the proxy.
    //   GET <worker>/browse?url=<encoded novel-site URL>
    if (url.pathname === "/browse" && request.method === "GET") {
      const target = url.searchParams.get("url") || "";
      if (!BROWSABLE_SITES.test(target))
        return new Response("unsupported url", { status: 400 });
      const r = await fetch(target, { headers: PAGE_HEADERS });
      const ct = r.headers.get("content-type") || "";
      if (!ct.includes("html"))
        return new Response(r.body, { status: r.status, headers: { "content-type": ct || "application/octet-stream" } });
      const shim =
        `<base href="${target.replace(/"/g, "&quot;")}">` +
        `<script>(function(){var ORIG=${JSON.stringify(target)};` +
        `try{parent.postMessage({type:"browse-url",url:ORIG},"*");}catch(e){}` +
        `document.addEventListener("click",function(e){` +
        `var t=e.target;while(t&&!(t.tagName==="A"&&t.getAttribute("href")))t=t.parentElement;` +
        `if(!t)return;var href;try{href=new URL(t.getAttribute("href"),ORIG).href;}catch(err){return;}` +
        `e.preventDefault();` +
        `if(${BROWSABLE_SITES.toString()}.test(href))location.href="/browse?url="+encodeURIComponent(href);` +
        `},true);})();</scr` + `ipt>`;
      const cleaned = new HTMLRewriter()
        .on("script, noscript, iframe, ins", { element(e) { e.remove(); } })
        .on("head", { element(e) { e.prepend(shim, { html: true }); } })
        .transform(r);
      return new Response(cleaned.body, {
        status: r.status,
        headers: {
          "content-type": "text/html; charset=utf-8",
          "content-security-policy": "frame-ancestors " + [...ALLOWED_ORIGINS].join(" "),
        },
      });
    }

    // Only our own front-end may use the Worker.
    if (!allowed) return new Response("origin not allowed", { status: 403 });

    const CORS = corsHeaders(origin);
    const withCors = resp => {
      const out = new Response(resp.body, resp);
      for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
      return out;
    };

    // Batch status long-poll: check every ~10s for up to ~90s, return the
    // moment the batch ends — so the browser makes far fewer status calls.
    //   GET <worker>/anthropic-poll/<batchId>
    if (url.pathname.startsWith("/anthropic-poll/")) {
      const batchId = url.pathname.slice("/anthropic-poll/".length);
      const headers = {
        "x-api-key": request.headers.get("x-api-key") || "",
        "anthropic-version": request.headers.get("anthropic-version") || "2023-06-01",
      };
      const deadline = Date.now() + POLL_WINDOW_MS;
      let last = "{}";
      for (;;) {
        const r = await fetch(`https://api.anthropic.com/v1/messages/batches/${batchId}`, { headers });
        if (!r.ok) {
          return withCors(new Response(await r.text(), {
            status: r.status, headers: { "content-type": "application/json" },
          }));
        }
        last = await r.text();
        let status;
        try { status = JSON.parse(last).processing_status; } catch {}
        if (status === "ended" || Date.now() + POLL_INTERVAL_MS >= deadline) break;
        await sleep(POLL_INTERVAL_MS);
      }
      return withCors(new Response(last, { headers: { "content-type": "application/json" } }));
    }

    // Batched chapter fetch: fetch up to 50 URLs in one call (free-tier
    // subrequest limit). No throttling of our own — all fetches are fired at
    // once and Cloudflare's per-invocation connection handling paces them
    // (~6 simultaneous, the rest queue automatically). The front-end runs
    // several of these calls in parallel to go wider.
    //   POST <worker>/fetch-many  { "urls": [...] }
    if (url.pathname === "/fetch-many" && request.method === "POST") {
      let urls = [];
      try { ({ urls } = await request.json()); } catch {}
      const list = (urls || []).slice(0, 50);
      const results = await Promise.all(list.map(async u => {
        try {
          const r = await fetch(u, { headers: PAGE_HEADERS });
          return { url: u, ok: r.ok, status: r.status, html: r.ok ? await stripHtml(r).text() : "" };
        } catch (e) {
          return { url: u, ok: false, status: 0, html: "", error: String(e) };
        }
      }));
      return withCors(Response.json({ results }));
    }

    // 2. Claude API proxy
    if (url.pathname.startsWith("/anthropic/")) {
      const target = "https://api.anthropic.com/" + url.pathname.slice("/anthropic/".length) + url.search;
      const headers = new Headers();
      for (const h of ["x-api-key", "anthropic-version", "anthropic-beta", "content-type"]) {
        const v = request.headers.get(h);
        if (v) headers.set(h, v);
      }
      const hasBody = request.method !== "GET" && request.method !== "HEAD";
      const resp = await fetch(target, {
        method: request.method,
        headers,
        body: hasBody ? await request.arrayBuffer() : undefined,
      });
      return withCors(resp);
    }

    // 1. Chapter fetching
    const targetUrl = url.searchParams.get("url");
    if (targetUrl) {
      return withCors(stripHtml(await fetch(targetUrl, { headers: PAGE_HEADERS })));
    }

    return new Response(
      "Novel Downloader proxy. Routes: GET /?url=<novel page>, GET /browse?url=<novel page>, " +
      "POST /fetch-many, ANY /anthropic/v1/..., GET /anthropic-poll/<batchId>",
      { headers: CORS },
    );
  },
};
