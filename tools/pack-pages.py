#!/usr/bin/env python3
"""Pack captured pages into the fixture layout the tests read.

    tools/pack-pages.py <captured-dir> <site-name> [subdir]

<captured-dir> holds raw .html files as fetched. Each becomes its own zip
under the site's own directory —

    android/app/src/sites/<site>/test/resources/pages/<site>/[subdir]/

one page per archive — `subdir` is how a site's chapter pages land in
`chapters/` beside its novel pages rather than mixed in with them.

compressed so whole third-party pages are neither indexed by repository
search nor carried uncompressed on every clone, and one-per-archive so the
page you are debugging can be extracted alone.

The manifest is NOT written here. Its columns must be measured by whatever
script captured the pages, independently of the app's own parser — that is
the entire point of them: the tests cross-check the app against a separate
measurement rather than echoing it back.
"""
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def site_pages(site):
    """Everything a site owns lives under its own root, tests included."""
    return os.path.join(REPO, "android/app/src/sites", site, "test/resources/pages", site)


def main():
    if len(sys.argv) not in (3, 4):
        print(__doc__)
        return 1
    src, site = sys.argv[1], sys.argv[2]
    out = site_pages(site)
    if len(sys.argv) == 4:
        out = os.path.join(out, sys.argv[3])
    os.makedirs(out, exist_ok=True)
    n = 0
    for name in sorted(os.listdir(src)):
        if not name.endswith(".html"):
            continue
        with open(os.path.join(src, name), "rb") as f:
            data = f.read()
        with zipfile.ZipFile(
            os.path.join(out, name + ".zip"), "w", zipfile.ZIP_DEFLATED, compresslevel=9
        ) as z:
            z.writestr(name, data)
        n += 1
    print(f"packed {n} pages into {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
