#!/usr/bin/env python3
"""Release-dex plugin ABI audit.

The app's extension plugins are dexed separately (d8) and bind to host
classes BY ORIGINAL NAME at runtime through DexClassLoader. R8 shrinking
the host APK does not see plugin code — an unkept class the plugins need
(kotlin.*, kotlinx.serialization.*, com.cloudimage.provider.api.*) gets
renamed or dropped and every plugin dies with NoClassDefFoundError on the
release build only. That is exactly the v1.0.0 regression: debug builds,
unit tests and CI were all green because none of them run R8.

This script fails the release build if it happens again:

  1. every *.zip under assets/ must be a valid plugin package
     (extension.json + classes.dex),
  2. every external type reference of every bundled plugin dex must be
     resolvable — either from the Android/Java bootclasspath, or from the
     host APK's dex files under its ORIGINAL name.

Usage:
    python3 tools/audit_release_dex.py app/build/outputs/apk/release/app-release.apk

Exit code 0 = ABI intact; 1 = missing bindings (do not ship).
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# Types the runtime itself provides; never required inside the host dex.
# NOTE: this is about class RESOLUTION, not API-level availability.
BOOTCLASSPATH_PREFIXES = (
    "Ljava/",
    "Landroid/",
    "Ljavax/",
    "Lorg/w3c/dom/",
    "Lorg/xml/",
    "Ldalvik/",
)

CLASS_DESCRIPTOR = re.compile(r"^\s*Class descriptor\s*:\s*'(.+?)'\s*$")

# Any 'Lfoo/bar/Baz;' token anywhere in a dexdump: standalone field types,
# inside quoted method signatures '(Lkotlin/Pair;)V', method-owner lines
# '(in Lcom/...;)' and annotations. Must contain a package slash — that
# filters synthetic-name fragments like 'Lambda0;' out of raw output.
ALL_DESCRIPTORS = re.compile(r"L[\w/$-]+;")
MIN_DESCRIPTOR_LEN = 4


def find_dexdump() -> str:
    """Locates the dexdump binary from the Android SDK build-tools."""
    candidates: list[str] = []
    homes = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.expanduser("~/Android/Sdk"),
        "/usr/local/lib/android/sdk",  # GitHub Actions runners
    ]
    for home in homes:
        if home:
            candidates.extend(glob.glob(os.path.join(home, "build-tools", "*", "dexdump")))
    which = shutil.which("dexdump")
    if which:
        candidates.append(which)
    for candidate in candidates:
        if os.access(candidate, os.X_OK):
            return candidate
    raise SystemExit(
        "dexdump not found — set ANDROID_HOME or install Android build-tools"
    )


def dexdump_to_text(dex_bytes: bytes, workdir: Path, name: str) -> str:
    """Writes a dex to a temp file and returns its dexdump text output."""
    dex_path = workdir / name
    dex_path.write_bytes(dex_bytes)
    result = subprocess.run(
        [find_dexdump(), str(dex_path)],
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout


def defined_classes(dump: str) -> set[str]:
    return {m.group(1) for m in (CLASS_DESCRIPTOR.match(line) for line in dump.splitlines()) if m}


def referenced_types(dump: str) -> set[str]:
    return {
        descriptor
        for descriptor in ALL_DESCRIPTORS.findall(dump)
        if len(descriptor) >= MIN_DESCRIPTOR_LEN and "/" in descriptor
    }


def read_zip_entry(archive: zipfile.ZipFile, entry_name: str) -> bytes:
    entry = archive.getinfo(entry_name)
    with archive.open(entry) as stream:
        return stream.read()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", help="Path to the release APK to audit")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    if not apk_path.is_file():
        print(f"APK not found: {apk_path}", file=sys.stderr)
        return 1

    failures: list[str] = []
    with tempfile.TemporaryDirectory(prefix="dexaudit-") as tmp:
        workdir = Path(tmp)

        with zipfile.ZipFile(apk_path) as apk:
            host_dex_names = sorted(n for n in apk.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            plugin_zip_names = sorted(n for n in apk.namelist() if n.startswith("assets/") and n.endswith(".zip"))

            if not host_dex_names:
                print("FAIL: APK contains no classes.dex", file=sys.stderr)
                return 1
            if not plugin_zip_names:
                failures.append("APK ships no bundled plugin packages under assets/")

            # Host side: every class the app itself ships, by original name.
            host_defined: set[str] = set()
            for dex_name in host_dex_names:
                dump = dexdump_to_text(read_zip_entry(apk, dex_name), workdir, dex_name.replace("/", "_"))
                host_defined |= defined_classes(dump)
            print(f"host dex files: {len(host_dex_names)} ({len(host_defined)} classes)")

            # Plugin side: every external reference must resolve.
            for plugin_name in plugin_zip_names:
                plugin_zip_bytes = read_zip_entry(apk, plugin_name)
                plugin_path = workdir / Path(plugin_name).name
                plugin_path.write_bytes(plugin_zip_bytes)

                with zipfile.ZipFile(plugin_path) as plugin:
                    names = set(plugin.namelist())
                    display = Path(plugin_name).name
                    if "extension.json" not in names:
                        failures.append(f"{display}: no extension.json")
                        continue
                    if "classes.dex" not in names:
                        failures.append(f"{display}: no classes.dex")
                        continue
                    dump = dexdump_to_text(read_zip_entry(plugin, "classes.dex"), workdir, f"plugin-{display}.dex")

                plugin_defined = defined_classes(dump)
                external = referenced_types(dump) - plugin_defined
                missing = sorted(
                    ref
                    for ref in external
                    if not ref.startswith(BOOTCLASSPATH_PREFIXES) and ref not in host_defined
                )
                print(
                    f"{display}: {len(plugin_defined)} classes, "
                    f"{len(external)} external refs, {len(missing)} unresolved"
                )
                for ref in missing:
                    failures.append(f"{display}: needs {ref} but the host dex does not provide it")

    if failures:
        print("\nPLUGIN ABI AUDIT FAILED — do not ship this APK:", file=sys.stderr)
        for failure in failures:
            print(f"  !! {failure}", file=sys.stderr)
        print(
            "\nFix: pin every package plugins compile against in "
            "app/proguard-rules.pro (see the 'Extension plugin runtime ABI' block).",
            file=sys.stderr,
        )
        return 1

    print("\nPlugin ABI audit passed: every bundled plugin binding resolves in the host dex.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
