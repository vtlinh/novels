/*
 * Cloudflare Worker for the Novel Downloader.
 *
 * Two jobs, both CORS-enabled so the browser page can call them:
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
 * Deploy: paste into your Worker at dash.cloudflare.com (Quick edit) and Save.
 * The API key travels browser -> your Worker -> Anthropic; it only ever
 * touches infrastructure you control.
 */

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, POST, OPTIONS",
  "access-control-allow-headers": "x-api-key, anthropic-version, anthropic-beta, content-type",
  "access-control-max-age": "600",
};

const withCors = resp => {
  const out = new Response(resp.body, resp);
  for (const [k, v] of Object.entries(CORS)) out.headers.set(k, v);
  return out;
};

export default {
  async fetch(request) {
    // CORS preflight for any route
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const url = new URL(request.url);

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
      const resp = await fetch(targetUrl, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
          "Accept-Language": "vi,en;q=0.8",
        },
      });
      return withCors(resp);
    }

    return new Response("Novel Downloader proxy: /?url=... or /anthropic/v1/...", { headers: CORS });
  },
};
