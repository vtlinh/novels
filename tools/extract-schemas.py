"""Pull the CREATE statements a historical build's onCreate would run.

The statements are Kotlin string-literal concatenations, optionally via a
`private const val X_TABLE = "..."` constant, so resolving them is a matter of
gluing adjacent literals — no Kotlin evaluation needed.

Up to v19 they lived in DownloadStore.onCreate; from v20 they live in
Schema.create, which the store delegates to. Both are read, whichever the
commit happens to have — the point of this script is that the baseline comes
from git history rather than from the current code's idea of it, and that
holds either side of the move.
"""
import re, subprocess, sys

BASE = "android/app/src/main/java/dev/vtlinh/noveldownloader"

# (file, the function whose body holds the CREATEs, the call it makes), most
# recent arrangement first. Schema.kt is absent from the older commits, where
# `git show` returns nothing and this falls through to the store; at HEAD the
# store's onCreate is a one-line delegation with no body to read, so asking it
# first would walk into the next braces it found and pull out whatever
# statements happened to be there.
WHERE = [
    (f"{BASE}/Schema.kt", "fun create", "exec"),
    (f"{BASE}/DownloadStore.kt", "override fun onCreate", "execSQL"),
]

def src(commit, path):
    return subprocess.run(
        ["git", "show", f"{commit}:{path}"],
        capture_output=True, cwd="/home/user/novels").stdout.decode("utf-8", "replace")

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def decomment(text):
    """Blank out Kotlin comments, keeping every offset where it was.

    Comments sit between the pieces of these constants — the reasoning for a
    column is written next to the column — and both the literal-gluing and the
    brace balancing below would otherwise read them as source. A comment
    between two halves of a concatenation truncated the `novels` table at
    exactly the column the comment explained.

    Replaced with spaces rather than removed so the index arithmetic that
    follows keeps working, and string literals are respected so a "/*" inside
    one is left alone.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                i += 2 if text[i] == "\\" else 1
            i += 1
        elif text.startswith("//", i):
            while i < n and text[i] != "\n":
                out[i] = " "; i += 1
        elif text.startswith("/*", i):
            while i < n and not text.startswith("*/", i):
                if text[i] != "\n": out[i] = " "
                i += 1
            for k in range(i, min(i + 2, n)):
                out[k] = " "
            i += 2
        else:
            i += 1
    return "".join(out)

def glue(chunk):
    return "".join(m.group(1) for m in LIT.finditer(chunk)).replace('\\"', '"')

def consts(text):
    out = {}
    for m in re.finditer(r'const val (\w+) =\s*((?:\s*"(?:[^"\\]|\\.)*"\s*\+?)+)', text):
        out[m.group(1)] = glue(m.group(2))
    return out

def creates(text, fn, call):
    text = decomment(text)
    i = text.find(fn)
    if i < 0:
        return []
    # body: balance braces from the first {
    j = text.index("{", i); depth = 0
    for k in range(j, len(text)):
        if text[k] == "{": depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0: break
    body = text[j:k]
    c = consts(text)
    stmts = []
    for m in re.finditer(re.escape(call) + r'\(', body):
        s = m.end(); depth = 1
        for k2 in range(s, len(body)):
            if body[k2] == "(": depth += 1
            elif body[k2] == ")":
                depth -= 1
                if depth == 0: break
        arg = body[s:k2].strip().rstrip(",").strip()
        if arg in c:
            stmts.append(c[arg])
        else:
            g = glue(arg)
            if g: stmts.append(g)
    return stmts

def at(commit):
    for path, fn, call in WHERE:
        stmts = creates(src(commit, path), fn, call)
        if stmts:
            return stmts
    return []

# Version -> the commit that declared it. Found with:
#   git log --format=%H -- .../DownloadStore.kt | while read c; do
#     git show $c:.../DownloadStore.kt | grep -o '"downloads.db", null, [0-9]*'; done
# (from v19 the number is Schema.VERSION, so grep Schema.kt for `const val VERSION`)
VERSIONS = {
    4: "8249426", 5: "20aaad8", 6: "df8faf5", 7: "d8ab597", 8: "35a4279",
    9: "b887619", 10: "2910bf9", 11: "972c442", 12: "9564980", 13: "54517af",
    14: "09e90c3", 15: "7afbafe", 16: "dfd35ce", 17: "8226f29", 18: "749fbfe",
    19: "6ff6dab", 20: "HEAD",
}

OUT = "android/app/src/test/resources/schema"

if __name__ == "__main__":
    import os
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    only = set(int(a) for a in sys.argv[1:])
    for v, c in sorted(VERSIONS.items()):
        if only and v not in only:
            continue
        stmts = at(c)
        assert stmts, f"v{v}: no CREATE statements found at {c}"
        with open(os.path.join(root, OUT, f"v{v}.sql"), "w") as f:
            f.write(f"-- schema of version {v}, as onCreate built it at commit {c}\n")
            f.write("-- extracted by tools/extract-schemas.py; do not hand-edit\n")
            for stmt in stmts:
                f.write(stmt.rstrip().rstrip(";") + ";\n")
        print(f"v{v}: {len(stmts)} statements")
