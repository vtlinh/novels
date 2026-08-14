#!/usr/bin/env python3
"""Fill unreleased changelog rows with this build's versionName.

android/changelog/notes.tsv is the source: one row per PR, version column
blank until the row ships. Packaging concatenates those blank rows under
APP_VERSION_NAME — they become this version's notes — and copies the result
into the APK as changelog.tsv.
"""
from __future__ import annotations

import argparse
import pathlib
import sys


def fill(text: str, version: str) -> str:
    out = []
    for line in text.splitlines(keepends=True):
        body = line.rstrip("\n")
        ended = line[len(body) :]
        if body.startswith("#") or not body.strip() or "\t" not in body:
            out.append(line)
            continue
        ver, summary = body.split("\t", 1)
        if ver.strip() or not summary.strip():
            out.append(line)
            continue
        out.append(f"{version}\t{summary}{ended}")
    return "".join(out)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--version", required=True)
    args = p.parse_args()
    src = pathlib.Path(args.input)
    dst = pathlib.Path(args.output)
    if not src.is_file():
        sys.exit(f"missing changelog {src}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(fill(src.read_text(), args.version))


if __name__ == "__main__":
    main()
