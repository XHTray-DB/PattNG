#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Fetches the three geo files for the build into the app's assets, where the app copies them into its
# asset folder at first start. Without them the core refuses any routing that names a geosite: or geoip:
# list: the Iran routing names category-ads-all, category-ir and geoip:ir, and every routing's
# geoip:private and geoip:cn become the lists of geoip-only-cn-private.dat. The sources are the ones the
# app lists (V2GetGeoFiles.kt, AppConfig.GEOIP_ONLY_CN_PRIVATE_URL). *.dat is git-ignored, so the files
# exist only in the build.

IRAN_RULES="https://github.com/Chocolate4U/Iran-v2ray-rules/releases/latest/download"
TARGET="$__dir/V2rayNG/app/src/main/assets"
mkdir -p "$TARGET"

fetch() { # name url min_bytes
  echo "[geo] fetching $1"
  curl -fsSL --retry 3 --retry-delay 5 --max-time 180 -o "$TARGET/$1.part" "$2"
  local size
  size="$(wc -c < "$TARGET/$1.part")"
  if [[ "$size" -lt "$3" ]]; then
    echo "$1 is too small ($size bytes)"
    exit 1
  fi
  mv "$TARGET/$1.part" "$TARGET/$1"
}

fetch geosite.dat "$IRAN_RULES/geosite.dat" 100000
fetch geoip.dat "$IRAN_RULES/geoip.dat" 100000
fetch geoip-only-cn-private.dat "https://raw.githubusercontent.com/Loyalsoldier/geoip/release/geoip-only-cn-private.dat" 1000

# The names the shipped routing uses must be in the lists.
for tag in CATEGORY-ADS-ALL CATEGORY-IR PRIVATE; do
  grep -aq "$tag" "$TARGET/geosite.dat" || { echo "geosite.dat has no $tag"; exit 1; }
done
grep -aq "PRIVATE" "$TARGET/geoip.dat" || { echo "geoip.dat has no PRIVATE"; exit 1; }
grep -aq "IR" "$TARGET/geoip.dat" || { echo "geoip.dat has no IR"; exit 1; }
for tag in PRIVATE CN; do
  grep -aq "$tag" "$TARGET/geoip-only-cn-private.dat" || { echo "geoip-only-cn-private.dat has no $tag"; exit 1; }
done
echo "[geo] done"
