"""Pull the CREATE statements a historical DownloadStore.onCreate would run.

The statements are Kotlin string-literal concatenations, optionally via a
`private const val X_TABLE = "..."` constant, so resolving them is a matter of
gluing adjacent literals — no Kotlin evaluation needed.
"""
import re, subprocess, sys

def src(commit):
    return subprocess.run(
        ["git", "show", f"{commit}:android/app/src/main/java/dev/vtlinh/noveldownloader/DownloadStore.kt"],
        capture_output=True, cwd="/home/user/novels").stdout.decode("utf-8", "replace")

LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')

def glue(chunk):
    return "".join(m.group(1) for m in LIT.finditer(chunk)).replace('\\"', '"')

def consts(text):
    out = {}
    for m in re.finditer(r'const val (\w+) =\s*((?:\s*"(?:[^"\\]|\\.)*"\s*\+?)+)', text):
        out[m.group(1)] = glue(m.group(2))
    return out

def on_create(text):
    i = text.find("override fun onCreate")
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
    for m in re.finditer(r'execSQL\(', body):
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

# Version -> the commit whose DownloadStore declared it. Found with:
#   git log --format=%H -- .../DownloadStore.kt | while read c; do
#     git show $c:.../DownloadStore.kt | grep -o '"downloads.db", null, [0-9]*'; done
VERSIONS = {
    4: "8249426", 5: "20aaad8", 6: "df8faf5", 7: "d8ab597", 8: "35a4279",
    9: "b887619", 10: "2910bf9", 11: "972c442", 12: "9564980", 13: "54517af",
    14: "09e90c3", 15: "7afbafe", 16: "dfd35ce", 17: "8226f29", 18: "749fbfe",
    19: "6ff6dab",
}

OUT = "android/app/src/test/resources/schema"

if __name__ == "__main__":
    import os
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    for v, c in sorted(VERSIONS.items()):
        stmts = on_create(src(c))
        assert stmts, f"v{v}: no CREATE statements found at {c}"
        with open(os.path.join(root, OUT, f"v{v}.sql"), "w") as f:
            f.write(f"-- schema of version {v}, as onCreate built it at commit {c}\n")
            f.write("-- extracted by tools/extract-schemas.py; do not hand-edit\n")
            for stmt in stmts:
                f.write(stmt.rstrip().rstrip(";") + ";\n")
        print(f"v{v}: {len(stmts)} statements")
