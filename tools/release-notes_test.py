#!/usr/bin/env python3
"""Offline checks for tools/release-notes.py and tools/seal-changelog.py."""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import importlib.util

def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "tools" / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

rn = load("release-notes")
seal = load("seal-changelog")

src = "# c\n\n\tNew thing\n\tOther\n1.33.21\tOld\n"
filled = rn.fill(src, "1.33.22")
assert "1.33.22\tNew thing" in filled, filled
assert "1.33.22\tOther" in filled, filled
assert "1.33.21\tOld" in filled, filled
assert filled.split("\tNew thing")[0].endswith("1.33.22"), filled

sealed, n = seal.seal(src, "1.33.21")
assert n == 2, n
assert "1.33.21\tNew thing" in sealed
assert "1.33.21\tOther" in sealed
assert sealed.count("1.33.21\tOld") == 1

# already-sealed rows are left alone
again, n2 = seal.seal(sealed, "9.9.9")
assert n2 == 0, n2
assert again == sealed

# fill is what the APK build does: unreleased rows become this version
notes = (ROOT / "android/changelog/notes.tsv").read_text()
filled = rn.fill(notes, "1.33.22")
assert not any(l.startswith("\t") for l in filled.splitlines())
assert "1.33.22\tShow release notes under the Version heading" in filled
assert filled.count("1.33.22\t") == 1

print("ok")
