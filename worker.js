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

const PAGE_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36",
  "Accept-Language": "vi,en;q=0.8",
};

const ALLOWED_PAGE_HOST_RE = /^(www\.)?truyenfull\.[a-z]{2,10}$/i;
const allowedPage = u => {
  try { const t = new URL(u); return t.protocol === "https:" && ALLOWED_PAGE_HOST_RE.test(t.hostname); }
  catch { return false; }
};

const POLL_WINDOW_MS = 90000;
const POLL_INTERVAL_MS = 10000;
const sleep = ms => new Promise(res => setTimeout(res, ms));

export default {
  async fetch(request) {
    if (request.method === "OPTIONS") return new Response(null, { headers: CORS });

    const url = new URL(request.url);

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

    if (url.pathname === "/fetch-many" && request.method === "POST") {
      let urls = [], concurrency;
      try { ({ urls, concurrency } = await request.json()); } catch {}
      const list = (urls || []).slice(0, 50);
      const conc = Math.max(1, Math.min(10, Number(concurrency) || 5));
      const results = new Array(list.length);
      let next = 0;
      await Promise.all(Array.from({ length: Math.min(conc, list.length) }, async () => {
        for (;;) {
          const i = next++;
          if (i >= list.length) return;
          const u = list[i];
          if (!allowedPage(u)) {
            results[i] = { url: u, ok: false, status: 403, html: "", error: "host not allowed" };
            continue;
          }
          try {
            const r = await fetch(u, { headers: PAGE_HEADERS });
            results[i] = { url: u, ok: r.ok, status: r.status, html: r.ok ? await r.text() : "" };
          } catch (e) {
            results[i] = { url: u, ok: false, status: 0, html: "", error: String(e) };
          }
        }
      }));
      return withCors(Response.json({ results }));
    }

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

    const targetUrl = url.searchParams.get("url");
    if (targetUrl) {
      if (!allowedPage(targetUrl))
        return new Response("host not allowed", { status: 403, headers: CORS });
      return withCors(await fetch(targetUrl, { headers: PAGE_HEADERS }));
    }

    return new Response("Novel Downloader proxy: /?url=... or /anthropic/v1/...", { headers: CORS });
  },
};
