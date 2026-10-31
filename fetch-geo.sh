#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Fetches geosite.dat and geoip.dat for the build, from the Iran rules release the app's geo file
# sources list first (Chocolate4U/Iran-v2ray-rules). They go into the app's assets, where the app copies
# them into its asset folder at first start; without them the core refuses any routing that names a
# geosite: or geoip: list (the Iran routing names category-ads-all, category-ir and geoip:ir).
# *.dat is git-ignored, so the files exist only in the build.

BASE="https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download"
TARGET="$__dir/V2rayNG/app/src/main/assets"
mkdir -p "$TARGET"

for name in geosite.dat geoip.dat; do
  echo "[geo] fetching $name"
  curl -fsSL --retry 3 --retry-delay 5 --max-time 180 -o "$TARGET/$name.part" "$BASE/$name"
  size="$(wc -c < "$TARGET/$name.part")"
  if [[ "$size" -lt 100000 ]]; then
    echo "$name is too small ($size bytes)"
    exit 1
  fi
  mv "$TARGET/$name.part" "$TARGET/$name"
done

# The names the shipped routing uses must be in the lists.
for tag in CATEGORY-ADS-ALL CATEGORY-IR PRIVATE; do
  grep -aq "$tag" "$TARGET/geosite.dat" || { echo "geosite.dat has no $tag"; exit 1; }
done
grep -aq "PRIVATE" "$TARGET/geoip.dat" || { echo "geoip.dat has no PRIVATE"; exit 1; }
grep -aq "IR" "$TARGET/geoip.dat" || { echo "geoip.dat has no IR"; exit 1; }
echo "[geo] done"
