/*
 * The worker's guards, tested locally: node worker.test.mjs
 *
 * A proxy on someone's account is exactly the sort of thing whose failure is
 * silent — a token check that always passes, an allowlist that doesn't hold,
 * a challenge page saved as if it were a chapter. `fetch` is stubbed here, so
 * this runs offline and asserts what the worker DECIDES rather than what a
 * site happened to answer.
 */

import worker from "./worker.js";

const ENV = { FETCH_TOKEN: "s3cret-token-value" };
const CTX = { waitUntil() {} };

/* The real one is a Cloudflare API; nothing here exercises the cache path. */
globalThis.caches = { default: { match: async () => undefined, put: async () => {} } };

let stub = null;
globalThis.fetch = async (url) => stub(url);

const call = (path, init) => worker.fetch(new Request("https://w.example" + path, init), ENV, CTX);

let failures = 0;
async function check(name, fn) {
  try {
    await fn();
    console.log("  ok   " + name);
  } catch (e) {
    failures++;
    console.log("  FAIL " + name + "\n       " + (e && e.message ? e.message : e));
  }
}
const eq = (got, want, what) => {
  if (got !== want) throw new Error(`${what}: got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
};

const html = (body, status = 200) =>
  new Response(body, { status, headers: { "content-type": "text/html; charset=utf-8" } });

const CHALLENGE =
  '<!DOCTYPE html><html><head><title>Just a moment...</title></head>' +
  '<body><div id="challenge-platform"></div></body></html>';

console.log("worker guards");

await check("no token is refused", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.today/x/"));
  eq(r.status, 401, "status");
});

await check("a wrong token is refused", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.today/x/") + "&token=nope");
  eq(r.status, 401, "status");
});

/* The token is the only thing between this worker and the whole internet, so
   a near-miss must not pass: same length, one character out. */
await check("a token that is one character out is refused", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const near = "s3cret-token-valuE";
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.today/x/") + "&token=" + near);
  eq(r.status, 401, "status");
});

await check("a host outside the allowlist is refused, token or not", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  for (const u of [
    "https://example.com/",
    "https://api.anthropic.com/v1/messages",
    "https://truyenfull.today.evil.com/x/",
    "http://truyenfull.today/x/",
  ]) {
    const r = await call("/?url=" + encodeURIComponent(u) + "&token=" + ENV.FETCH_TOKEN);
    eq(r.status, 403, "status for " + u);
  }
});

await check("an allowed page comes back untouched", async () => {
  const body = "<html><head><title>t</title></head><body><div id=\"list-chapter\">x</div></body></html>";
  stub = async () => html(body);
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.today/x/") + "&token=" + ENV.FETCH_TOKEN);
  eq(r.status, 200, "status");
  eq(r.headers.get("x-challenge"), "0", "x-challenge");
  eq(await r.text(), body, "body");
});

/* The one that matters: a challenge page must never be handed back looking
   like the page that was asked for. */
await check("a challenge page is reported, not returned as the page", async () => {
  stub = async () => html(CHALLENGE, 403);
  const r = await call("/?url=" + encodeURIComponent("https://novelfull.com/x.html") + "&token=" + ENV.FETCH_TOKEN);
  eq(r.status, 409, "status");
  eq(r.headers.get("x-challenge"), "1", "x-challenge");
  eq(r.headers.get("x-upstream-status"), "403", "x-upstream-status");
});

await check("a 404 stays a 404 rather than becoming a page", async () => {
  stub = async () => html("<html>not found</html>", 404);
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.today/nope/") + "&token=" + ENV.FETCH_TOKEN);
  eq(r.status, 404, "status");
  eq(r.headers.get("x-challenge"), "0", "x-challenge");
});

await check("head=1 answers with metadata, not the body", async () => {
  stub = async () => html("<html>1234567890</html>");
  const r = await call("/?url=" + encodeURIComponent("https://truyenfull.live/x/") + "&token=" + ENV.FETCH_TOKEN + "&head=1");
  eq(r.status, 200, "status");
  const j = await r.json();
  eq(j.challenge, false, "challenge");
  eq(j.bytes, "<html>1234567890</html>".length, "bytes");
});

await check("fetch-many drops URLs outside the allowlist", async () => {
  stub = async (u) => html("<html>" + u + "</html>");
  const r = await call("/fetch-many?token=" + ENV.FETCH_TOKEN, {
    method: "POST",
    body: JSON.stringify({
      urls: ["https://truyenfull.today/a/", "https://example.com/b", "https://novelfull.net/c.html"],
    }),
  });
  const j = await r.json();
  eq(j.results.length, 2, "kept");
  eq(j.results.some(x => x.url.includes("example.com")), false, "example.com must not be fetched");
});

await check("fetch-many returns no body for a challenged page", async () => {
  stub = async () => html(CHALLENGE, 403);
  const r = await call("/fetch-many?token=" + ENV.FETCH_TOKEN, {
    method: "POST",
    body: JSON.stringify({ urls: ["https://novelfull.com/a.html"] }),
  });
  const j = await r.json();
  eq(j.results[0].challenge, true, "challenge");
  eq(j.results[0].html, "", "html must be empty rather than the challenge page");
});

await check("health needs no token", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const r = await call("/health");
  eq(r.status, 200, "status");
});

await check("a missing FETCH_TOKEN fails closed", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const r = await worker.fetch(
    new Request("https://w.example/?url=" + encodeURIComponent("https://truyenfull.today/x/")),
    {},
    CTX,
  );
  eq(r.status, 500, "status");
});

console.log(failures === 0 ? "\nall guards hold" : `\n${failures} FAILED`);
process.exit(failures === 0 ? 0 : 1);
