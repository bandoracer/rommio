#!/usr/bin/env python3
"""Build the pinned FCEUmm source as APK assets; never fetch source or toolchains."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

REPO = Path(__file__).resolve().parents[2]
SOURCE = REPO / "third_party/fceumm"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
LIBRARY = "libfceumm_libretro_android.so"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--rebuild", action="store_true", help="Recompile all objects for repeatability checks")
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(REPO / "app/build") or output == REPO / "app/build":
        raise ValueError("Generated core assets must be under app/build")
    pin_bytes = (SOURCE / "sources.json").read_bytes()
    pin = json.loads(pin_bytes)
    for item in pin["files"]:
        path = SOURCE / item["path"]
        if not path.resolve().is_relative_to(SOURCE) or digest(path.read_bytes()) != item["vendoredSha256"]:
            raise ValueError(f"Pinned core source changed: {item['path']}")
    expected = {item["path"] for item in pin["files"]} | {"sources.json", "UPSTREAM.md", "rommio.patch"}
    unexpected = {str(p.relative_to(SOURCE)) for p in SOURCE.rglob("*") if p.is_file()} - expected
    if unexpected:
        raise ValueError(f"Unrecorded core source files: {sorted(unexpected)}")
    properties = (args.ndk / "source.properties").read_text()
    revision = next(line.split("=", 1)[1].strip() for line in properties.splitlines() if line.startswith("Pkg.Revision"))
    if revision != pin["ndkVersion"]:
        raise ValueError(f"Expected NDK {pin['ndkVersion']}, got {revision}")
    work = REPO / "app/build/pinned-fceumm"
    libs = work / "libraries"
    work.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, SOURCE_DATE_EPOCH=str(pin["sourceDateEpoch"]))
    subprocess.run([
        str(args.ndk / "ndk-build"), *(["-B"] if args.rebuild else []), "-j8", "NDK_PROJECT_PATH=null",
        f"APP_BUILD_SCRIPT={SOURCE / 'jni/Android.mk'}", f"NDK_APPLICATION_MK={SOURCE / 'jni/Application.mk'}",
        f"APP_ABI={' '.join(ABIS)}", f"APP_PLATFORM=android-{pin['api']}", "APP_OPTIM=release", "NDK_DEBUG=0",
        "APP_CFLAGS=-Werror=date-time", f"NDK_OUT={work / 'objects'}", f"NDK_LIBS_OUT={libs}",
    ], check=True, cwd=SOURCE, env=environment)
    staging = output.with_name(output.name + ".staging")
    if staging.exists():
        shutil.rmtree(staging)
    artifacts = []
    for abi in ABIS:
        source = libs / abi / LIBRARY
        relative = f"cores/fceumm/{abi}/{LIBRARY}"
        destination = staging / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        artifacts.append({"abi": abi, "assetPath": relative, "sha256": digest(source.read_bytes()), "sizeBytes": source.stat().st_size})
    manifest = {
        "schemaVersion": 1, "runtimeId": pin["runtimeId"], "sourceRevision": pin["revision"],
        "sourceManifestSha256": digest(pin_bytes), "recipeSha256": digest(Path(__file__).read_bytes()),
        "ndkVersion": revision, "androidApi": pin["api"], "persistenceExtension": "rommio-fceumm-segments-v2",
        "artifacts": artifacts,
    }
    (staging / "cores/fceumm/manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if output.exists():
        shutil.rmtree(output)
    staging.rename(output)
    print("Built pinned FCEUmm assets: " + ", ".join(ABIS))


if __name__ == "__main__":
    main()
