"""Measure each captured novel page's reader rating into its manifest.

Fills the tenth column of every site's manifest.tsv: the rating the page
prints, scaled to out-of-10 with one decimal, or blank when the page prints
none. A rating nobody has voted on is none — read-novel.com prints "nan from
0 ratings", truyenfullmoi "0.0" from an empty count.

Read straight off the raw HTML with regular expressions, never through the
app's parser, so SiteContract compares the adapters against a measurement
rather than against themselves. The three shapes the corpus holds:

  schema.org microdata    itemprop="ratingValue" / "bestRating" / "ratingCount"
  "Rating: X/Y from N"    novelfull's info block
  "X / Y ( N votes )"     freewebnovel's p.vote

Run from the repository root:  python3 tools/measure-ratings.py
"""
import os
import re
import zipfile

SITES = "android/app/src/sites"
NOTE = (
    "# rating (10th column): the reader rating the page prints, out of 10, "
    "blank when it prints none or nobody has voted — measured by "
    "tools/measure-ratings.py from the raw HTML, not with this app's parser.\n"
)

NUM = r"([\d.]+|nan)"
COUNT = r"([\d,]*)"
SHAPES = [
    re.compile(
        r'itemprop="ratingValue"[^>]*>\s*' + NUM + r"\s*<.*?"
        r'itemprop="bestRating"[^>]*>\s*([\d.]+)\s*<.*?'
        r'itemprop="ratingCount"[^>]*>\s*' + COUNT + r"\s*<",
        re.S,
    ),
    re.compile(
        r"Rating:\s*<strong>\s*<span>\s*" + NUM + r"\s*</span>\s*</strong>\s*/\s*"
        r"<span[^>]*>\s*([\d.]+)\s*</span>\s*from\s*<strong>\s*<span>\s*" + COUNT,
        re.S,
    ),
    re.compile(r'class="vote">\s*' + NUM + r"\s*/\s*([\d.]+)\s*\(\s*" + COUNT + r"\s*votes?", re.S),
]


def measure(html):
    found = []
    for shape in SHAPES:
        for value, best, count in shape.findall(html):
            votes = int(count.replace(",", "") or "0")
            if value == "nan" or votes <= 0 or float(value) <= 0:
                found.append("")
            else:
                found.append("%.1f" % (float(value) / float(best) * 10))
    if len(set(found)) > 1:
        raise ValueError(f"the page prints {len(found)} different ratings: {found}")
    return found[0] if found else ""


def page(site, path):
    with zipfile.ZipFile(f"{SITES}/{site}/test/resources/pages/{path}.zip") as z:
        return z.read(z.namelist()[0]).decode("utf-8", "replace")


def main():
    for site in sorted(os.listdir(SITES)):
        manifest = f"{SITES}/{site}/test/resources/pages/{site}/manifest.tsv"
        if not os.path.exists(manifest):
            continue
        lines = open(manifest, encoding="utf-8").read().splitlines(keepends=True)
        out, rated, noted = [], 0, any(l.startswith("# rating (10th column)") for l in lines)
        for line in lines:
            if not noted and not line.startswith("#"):
                out.append(NOTE)
                noted = True
            if line.startswith("#") or not line.strip():
                out.append(line)
                continue
            cols = line.rstrip("\n").split("\t")
            if cols[0] == "site":
                cols = (cols + [""] * 10)[:9] + ["rating"]
            elif cols[4] == "novel":
                r = measure(page(site, cols[1]))
                rated += r != ""
                cols = (cols + [""] * 10)[:9] + [r]
            out.append("\t".join(cols) + "\n")
        open(manifest, "w", encoding="utf-8").write("".join(out))
        print(f"{site}: {rated} rated")


if __name__ == "__main__":
    main()
