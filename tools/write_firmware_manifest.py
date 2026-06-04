#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
from datetime import datetime, timezone


def main() -> None:
    parser = argparse.ArgumentParser(description="Write Kejmil OLED firmware manifest.")
    parser.add_argument("--bin", required=True, help="Path to compiled ESP32 .bin")
    parser.add_argument("--version", required=True, help="Firmware version")
    parser.add_argument("--output", default="firmware/latest.json", help="Manifest output path")
    parser.add_argument("--device", default="kejmil-oled-esp32")
    parser.add_argument("--name", default="Kejmil OLED")
    parser.add_argument("--firmware-url", default="", help="HTTPS URL where the .bin will be available")
    parser.add_argument("--changelog", default="Firmware build from GitHub Actions")
    parser.add_argument("--required", action="store_true")
    args = parser.parse_args()

    bin_path = Path(args.bin)
    data = bin_path.read_bytes()
    sha256 = hashlib.sha256(data).hexdigest()
    firmware_url = args.firmware_url.strip()

    if not firmware_url:
        repo = os.environ.get("GITHUB_REPOSITORY", "krawc/kejmil-oled")
        branch = os.environ.get("GITHUB_REF_NAME", "main")
        firmware_url = (
            f"https://raw.githubusercontent.com/{repo}/{branch}/"
            f"firmware/releases/{bin_path.name}"
        )

    manifest = {
        "device": args.device,
        "name": args.name,
        "version": args.version,
        "firmwareUrl": firmware_url,
        "changelog": args.changelog,
        "sizeBytes": len(data),
        "sha256": sha256,
        "required": bool(args.required),
        "protocol": "ble-ota-v1",
        "builtAt": datetime.now(timezone.utc).isoformat(),
    }

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {output} for {bin_path.name}, size={len(data)}, sha256={sha256}")


if __name__ == "__main__":
    main()

