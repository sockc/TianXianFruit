#!/usr/bin/env bash
# Prepare self-contained icon resources before XcodeGen runs.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOURCE="$ROOT/Config/AppIconSource.jpg"
OUT="$ROOT/Resources/Assets.xcassets/AppIcon.appiconset"
test -s "$SOURCE"
test -s "$OUT/Contents.json"
mkdir -p "$OUT"
command -v sips >/dev/null
sips -s format png -z 40 40 "$SOURCE" --out "$OUT/Icon-iphone-20x20-2x.png" >/dev/null
sips -s format png -z 60 60 "$SOURCE" --out "$OUT/Icon-iphone-20x20-3x.png" >/dev/null
sips -s format png -z 58 58 "$SOURCE" --out "$OUT/Icon-iphone-29x29-2x.png" >/dev/null
sips -s format png -z 87 87 "$SOURCE" --out "$OUT/Icon-iphone-29x29-3x.png" >/dev/null
sips -s format png -z 80 80 "$SOURCE" --out "$OUT/Icon-iphone-40x40-2x.png" >/dev/null
sips -s format png -z 120 120 "$SOURCE" --out "$OUT/Icon-iphone-40x40-3x.png" >/dev/null
sips -s format png -z 120 120 "$SOURCE" --out "$OUT/Icon-iphone-60x60-2x.png" >/dev/null
sips -s format png -z 180 180 "$SOURCE" --out "$OUT/Icon-iphone-60x60-3x.png" >/dev/null
sips -s format png -z 20 20 "$SOURCE" --out "$OUT/Icon-ipad-20x20-1x.png" >/dev/null
sips -s format png -z 40 40 "$SOURCE" --out "$OUT/Icon-ipad-20x20-2x.png" >/dev/null
sips -s format png -z 29 29 "$SOURCE" --out "$OUT/Icon-ipad-29x29-1x.png" >/dev/null
sips -s format png -z 58 58 "$SOURCE" --out "$OUT/Icon-ipad-29x29-2x.png" >/dev/null
sips -s format png -z 40 40 "$SOURCE" --out "$OUT/Icon-ipad-40x40-1x.png" >/dev/null
sips -s format png -z 80 80 "$SOURCE" --out "$OUT/Icon-ipad-40x40-2x.png" >/dev/null
sips -s format png -z 76 76 "$SOURCE" --out "$OUT/Icon-ipad-76x76-1x.png" >/dev/null
sips -s format png -z 152 152 "$SOURCE" --out "$OUT/Icon-ipad-76x76-2x.png" >/dev/null
sips -s format png -z 167 167 "$SOURCE" --out "$OUT/Icon-ipad-83_5x83_5-2x.png" >/dev/null
sips -s format png -z 1024 1024 "$SOURCE" --out "$OUT/Icon-ios-marketing-1024x1024-1x.png" >/dev/null
# Every referenced catalog image must exist and be nonempty.
python3 - "$OUT" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
for i in json.loads((p / "Contents.json").read_text())["images"]:
    a=p/i["filename"]
    assert a.stat().st_size > 500, f"Missing AppIcon {a.name}"
print("AppIcon catalog complete, 18 icon PNG variants")
PY
