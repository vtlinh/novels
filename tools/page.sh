#!/usr/bin/env bash
# Read a novel-site page through the dev Worker.
#
#   export NOVELS_WORKER=https://truyenfull.vtlinh87.workers.dev
#   export NOVELS_TOKEN=<the FETCH_TOKEN you set on the worker>
#
#   tools/page.sh https://novelfull.com/the-mech-touch.html          # to stdout
#   tools/page.sh https://truyenfull.today/tu-tien/ out.html         # to a file
#   tools/page.sh -m urls.txt outdir/                                # many at once
#
# Exits non-zero and says so when the site answered with a JS challenge
# rather than the page, so a challenge is never saved as if it were a novel
# page — the failure this whole thing exists to make visible.
set -euo pipefail

: "${NOVELS_WORKER:?set NOVELS_WORKER to the worker URL}"
: "${NOVELS_TOKEN:?set NOVELS_TOKEN to the worker FETCH_TOKEN}"

hdr=$(mktemp); trap 'rm -f "$hdr"' EXIT

one() {
  local url="$1" out="${2:-}"
  local body
  body=$(mktemp)
  local code
  code=$(curl -sS -D "$hdr" -o "$body" -w "%{http_code}" \
    -H "x-fetch-token: $NOVELS_TOKEN" \
    --get --data-urlencode "url=$url" "$NOVELS_WORKER/")
  local challenge
  challenge=$(grep -i '^x-challenge:' "$hdr" | tr -d '\r' | awk '{print $2}')
  if [ "$challenge" = "1" ]; then
    echo "challenge: $url (the site served an interstitial, not the page)" >&2
    echo "  a plain proxy cannot pass it — see worker-render.js" >&2
    rm -f "$body"; return 2
  fi
  if [ "$code" != "200" ]; then
    echo "http $code: $url" >&2
    head -c 300 "$body" >&2; echo >&2
    rm -f "$body"; return 1
  fi
  if [ -n "$out" ]; then mv "$body" "$out"; echo "$out  $(wc -c < "$out") bytes"
  else cat "$body"; rm -f "$body"; fi
}

if [ "${1:-}" = "-m" ]; then
  list="${2:?usage: -m urls.txt outdir/}"; dir="${3:?usage: -m urls.txt outdir/}"
  mkdir -p "$dir"; rc=0
  while read -r u; do
    [ -z "$u" ] && continue
    name=$(printf '%s' "$u" | sed -E 's#https?://##; s#[^A-Za-z0-9._-]#_#g').html
    one "$u" "$dir/$name" || rc=$?
  done < "$list"
  exit $rc
fi

one "${1:?usage: page.sh <url> [outfile]}" "${2:-}"
