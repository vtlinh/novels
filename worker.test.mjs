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

const ENV = { FETCH_TOKEN: "s3cret-token-value", GH_TOKEN: "gh-read-only-token" };
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
    /* the allowlist is a list of alternatives now that it covers nine sites,
       so a host that merely CONTAINS an allowed one has to keep failing */
    "https://allnovelupdates.com.evil.com/x",
    "https://evil.com/https://allnovelupdates.com/x",
    "https://notfreewebnovel.com/x",
    "http://xtruyen.vn/x",
    /* the sister host without the 2 is a different site with its own
       catalogue — nothing here has been measured against it */
    "https://vivutruyen.net/x/",
  ]) {
    const r = await call("/?url=" + encodeURIComponent(u) + "&token=" + ENV.FETCH_TOKEN);
    eq(r.status, 403, "status for " + u);
  }
});

/* ...and every host the capture scripts need really is reachable. The
   allowlist went from two sites written out inline to a joined list of nine;
   a mistake in one alternative fails closed and silently, which reads exactly
   like the site challenging us. */
await check("every allowlisted novel host is reachable", async () => {
  stub = async () => html("<html><body>ok</body></html>");
  for (const u of [
    "https://truyenfull.today/x/",
    "https://truyenfull.live/x/",
    "https://truyenfull.vn/x/",
    "https://novelfull.com/x.html",
    "https://novelfull.net/x.html",
    "https://truyenfullmoi.com/x.1/",
    "https://www.truyenfullmoi.com/x.1/",
    "https://allnovelupdates.com/book/x",
    "https://read-novel.com/novel1000-x.html",
    "https://xtruyen.vn/x/",
    /* both spellings: these two redirect the apex to www, and the allowlist
       is applied to the REDIRECT TARGET as well as to the url asked for — so
       a missing www alternative reads as the site refusing us rather than as
       the proxy refusing itself. That is exactly how empirenovel.com looked
       until this line was added. */
    "https://empirenovel.com/x",
    "https://www.empirenovel.com/x",
    "https://novellive.app/x",
    "https://www.novellive.app/x",
    "https://freewebnovel.com/x.html",
    "https://www.freewebnovel.com/x.html",
    "https://vivutruyen2.net/x/",
    "https://www.vivutruyen2.net/x/",
  ]) {
    const r = await call("/?url=" + encodeURIComponent(u) + "&token=" + ENV.FETCH_TOKEN);
    eq(r.status, 200, "status for " + u);
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

/* The allowlist used to be applied to the URL the CALLER sent and to nothing
 * else: the fetch followed redirects itself, so one open redirect on either
 * novel site turned this into a proxy for anything at all — link-local
 * addresses included, which is exactly what the allowlist exists to stop. The
 * worker even reported the foreign final host back in x-upstream-url. */
const redirect = (to, status = 302) => new Response("", { status, headers: { location: to } });

await check("a redirect off the allowlist is refused, not followed", async () => {
  let reached = null;
  stub = async (u) => {
    reached = u;
    if (u.includes("truyenfull")) return redirect("http://169.254.169.254/latest/meta-data/");
    return html("<html>SECRET</html>");
  };
  const r = await call(
    "/?token=" + ENV.FETCH_TOKEN + "&url=" + encodeURIComponent("https://truyenfull.today/x/"),
  );
  eq(r.status, 403, "status");
  eq(reached.includes("169.254"), false, "the off-site host must never be fetched");
  eq((await r.text()).includes("SECRET"), false, "no off-site body may reach the caller");
});

await check("a redirect within the allowlist is followed", async () => {
  stub = async (u) =>
    u.includes("truyenfull.today") ? redirect("https://truyenfull.live/x/") : html("<html>the page</html>");
  const r = await call(
    "/?token=" + ENV.FETCH_TOKEN + "&url=" + encodeURIComponent("https://truyenfull.today/x/"),
  );
  eq(r.status, 200, "status");
  eq((await r.text()).includes("the page"), true, "body");
});

await check("a redirect loop stops rather than spinning", async () => {
  let hops = 0;
  stub = async () => { hops++; return redirect("https://truyenfull.live/loop/"); };
  const r = await call(
    "/?token=" + ENV.FETCH_TOKEN + "&url=" + encodeURIComponent("https://truyenfull.today/loop/"),
  );
  eq(r.status, 508, "status");
  eq(hops <= 8, true, `stopped after ${hops} hops`);
});

await check("fetch-many refuses an off-allowlist redirect too", async () => {
  stub = async (u) =>
    u.includes("novelfull") ? redirect("https://example.com/") : html("<html>ok</html>");
  const r = await call("/fetch-many?token=" + ENV.FETCH_TOKEN, {
    method: "POST",
    body: JSON.stringify({ urls: ["https://novelfull.com/a.html"] }),
  });
  const j = await r.json();
  eq(j.results[0].status, 403, "status");
  eq(j.results[0].html, "", "html must be empty");
});

/* The app's own release, served to anyone. The repository is private, so the
 * GitHub assets the updater used to fetch now 404 on a device with no
 * credential — every installed copy would quietly stop finding updates. These
 * two routes deliberately skip the token gate, which is safe only because
 * they take no url from the caller. */
const RELEASE = {
  assets: [{ name: "version.json", id: 11 }, { name: "app-release.apk", id: 22 }],
};

await check("the release manifest is served without a token", async () => {
  stub = async (u) => {
    if (u.includes("/releases/tags/")) return new Response(JSON.stringify(RELEASE), { status: 200 });
    if (u.includes("/releases/assets/11")) return new Response('{"versionCode":9}', { status: 200 });
    throw new Error("unexpected " + u);
  };
  const r = await call("/app/version.json");
  eq(r.status, 200, "status");
  eq((await r.text()).includes("versionCode"), true, "body");
});

await check("the apk is served without a token", async () => {
  stub = async (u) => {
    if (u.includes("/releases/tags/")) return new Response(JSON.stringify(RELEASE), { status: 200 });
    if (u.includes("/releases/assets/22")) return new Response("APKBYTES", { status: 200 });
    throw new Error("unexpected " + u);
  };
  const r = await call("/app/app-release.apk");
  eq(r.status, 200, "status");
  eq(r.headers.get("content-type"), "application/vnd.android.package-archive", "content-type");
});

/* The credential must not be replayed to whatever host the redirect names,
 * and a redirect somewhere unexpected is refused rather than proxied. */
await check("an asset redirect off GitHub is refused", async () => {
  stub = async (u) => {
    if (u.includes("/releases/tags/")) return new Response(JSON.stringify(RELEASE), { status: 200 });
    return new Response("", { status: 302, headers: { location: "https://example.com/evil" } });
  };
  const r = await call("/app/app-release.apk");
  eq(r.status, 502, "status");
  eq((await r.text()).includes("off GitHub"), true, "reason");
});

await check("the release routes cannot be turned into a proxy", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  for (const p of ["/app/", "/app/../worker.js", "/app/anything.txt"]) {
    const r = await call(p);
    eq(r.status !== 200, true, `${p} must not serve anything`);
  }
});

await check("a missing GH_TOKEN fails closed rather than silently", async () => {
  stub = () => { throw new Error("must not reach upstream"); };
  const r = await worker.fetch(new Request("https://w.example/app/version.json"), { FETCH_TOKEN: "x" }, CTX);
  eq(r.status, 500, "status");
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
