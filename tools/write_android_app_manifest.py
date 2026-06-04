#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Write KEPS32v1 Android app update manifest.")
    parser.add_argument("--apk", required=True, help="Path to compiled APK")
    parser.add_argument("--version-name", required=True, help="Android versionName")
    parser.add_argument("--version-code", required=True, type=int, help="Android versionCode")
    parser.add_argument("--output", default="android-app/latest.json", help="Manifest output path")
    parser.add_argument("--package-name", default="pl.kejmil.oledsender")
    parser.add_argument("--name", default="KEPS32v1")
    parser.add_argument("--apk-url", default="", help="HTTPS URL where the APK will be available")
    parser.add_argument("--changelog", default="Android app build from GitHub Actions")
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()

    apk_path = Path(args.apk)
    data = apk_path.read_bytes()
    sha256 = hashlib.sha256(data).hexdigest()
    apk_url = args.apk_url.strip()

    if not apk_url:
        repo = os.environ.get("GITHUB_REPOSITORY", "krawc/kejmil-oled")
        branch = os.environ.get("GITHUB_REF_NAME", "main")
        apk_url = (
            f"https://raw.githubusercontent.com/{repo}/{branch}/"
            f"android-app/releases/{apk_path.name}"
        )

    manifest = {
        "packageName": args.package_name,
        "name": args.name,
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "apkUrl": apk_url,
        "changelog": args.changelog,
        "sizeBytes": len(data),
        "sha256": sha256,
        "required": bool(args.required),
        "protocol": "android-apk-v1",
        "builtAt": datetime.now(timezone.utc).isoformat(),
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output} for {apk_path.name}, size={len(data)}, sha256={sha256}")


if __name__ == "__main__":
    main()
