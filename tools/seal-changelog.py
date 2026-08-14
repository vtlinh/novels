#!/usr/bin/env python3
"""Stamp unreleased changelog rows with the published versionName.

Blank-version rows in android/app/src/main/assets/changelog.tsv concatenate
under whatever versionName the next APK is stamped with. After a version
ships, those rows have to be sealed to that version or the following build
will swallow them. This fills every blank version column with the
versionName currently on the android-latest release.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "android/changelog/notes.tsv"


def published_version() -> str:
    raw = subprocess.check_output(
        [
            "gh", "release", "download", "android-latest",
            "--pattern", "version.json",
            "--output", "-",
        ],
        text=True,
    )
    m = re.search(r'"versionName"\s*:\s*"([^"]+)"', raw)
    if not m:
        sys.exit("android-latest version.json had no versionName")
    return m.group(1)


def seal(text: str, version: str) -> tuple[str, int]:
    n = 0
    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\n")
        ended = line[len(body):]
        if body.startswith("#") or not body.strip() or "\t" not in body:
            out.append(line)
            continue
        ver, summary = body.split("\t", 1)
        if ver.strip():
            out.append(line)
            continue
        if not summary.strip():
            out.append(line)
            continue
        n += 1
        out.append(f"{version}\t{summary}{ended}")
    return "".join(out), n


def main() -> None:
    version = sys.argv[1] if len(sys.argv) > 1 else published_version()
    text = CHANGELOG.read_text()
    sealed, n = seal(text, version)
    CHANGELOG.write_text(sealed)
    print(f"sealed {n} row(s) as {version} in {CHANGELOG.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
