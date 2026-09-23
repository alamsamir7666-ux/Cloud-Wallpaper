#!/usr/bin/env python3
"""Builds the extension repository index.

Scans a directory of built provider packages (<id>.zip files containing an
extension.json manifest plus classes.dex), and writes an index.json that
the app's repo manager can parse:

    {
      "name": "Cloudimage official repository",
      "packages": [
        {
          "id": "cloudimage.wallhaven",
          "fileName": "cloudimage.wallhaven.zip",
          "sha256": "…",
          "sizeBytes": 22304,
          "versionName": "1.0.0",
          "versionCode": 1,
          "apiVersion": 1,
          "author": "Cloudimage",
          "description": "…"
        }
      ]
    }

Usage: python3 tools/build_repo_index.py <packages-dir> [--name "Repo name"]
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path


def read_manifest(zip_path: Path) -> dict:
    with zipfile.ZipFile(zip_path) as archive:
        try:
            manifest = json.loads(archive.read("extension.json"))
        except KeyError:
            raise SystemExit(f"{zip_path.name}: missing extension.json — not an extension package")
    for key in ("id", "name", "versionName", "versionCode", "apiVersion"):
        if key not in manifest:
            raise SystemExit(f"{zip_path.name}: manifest is missing '{key}'")
    return manifest


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build_index(packages_dir: Path, repo_name: str) -> dict:
    packages = []
    for zip_path in sorted(packages_dir.glob("*.zip")):
        manifest = read_manifest(zip_path)
        packages.append(
            {
                "id": manifest["id"],
                "fileName": zip_path.name,
                "sha256": sha256_of(zip_path),
                "sizeBytes": zip_path.stat().st_size,
                "versionName": str(manifest["versionName"]),
                "versionCode": int(manifest["versionCode"]),
                "apiVersion": int(manifest["apiVersion"]),
                "author": str(manifest.get("author", "")),
                "description": str(manifest.get("description", "")),
            }
        )
    return {"name": repo_name, "packages": packages}


def main() -> int:
    parser = argparse.ArgumentParser(description="Builds the extension repository index.")
    parser.add_argument("packages_dir", type=Path, help="directory containing built *.zip packages")
    parser.add_argument("--name", default="Cloudimage official repository", help="repository display name")
    parser.add_argument("--output", type=Path, default=None, help="index.json location (default: <dir>/index.json)")
    args = parser.parse_args()

    if not args.packages_dir.is_dir():
        raise SystemExit(f"{args.packages_dir}: not a directory")

    index = build_index(args.packages_dir, args.name)
    output = args.output or args.packages_dir / "index.json"
    output.write_text(json.dumps(index, indent=2) + "\n")

    for package in index["packages"]:
        print(
            f"{package['id']:<28} {package['versionName']:<8} "
            f"{package['sizeBytes']:>8} bytes  {package['sha256'][:12]}..."
        )
    print(f"\n{len(index['packages'])} package(s) -> {output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
