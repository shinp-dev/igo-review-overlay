#!/usr/bin/env bash
set -euo pipefail

# Official g170 network (CC0 exception documented by katagotraining.org).
# This small model is the offline baseline; 9x9-specialized model can be added
# as a user-imported optional network after on-device speed/accuracy testing.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/katago/model.bin.gz"
URL="https://katagoarchive.org/g170/neuralnets/g170-b6c96-s175395328-d26788732.bin.gz"
SHA256="f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1"
mkdir -p "$(dirname "$OUT")"
if [[ ! -s "$OUT" ]] || ! echo "$SHA256  $OUT" | sha256sum -c --status; then
  curl --fail --location --retry 3 --connect-timeout 20 "$URL" --output "$OUT.partial"
  echo "$SHA256  $OUT.partial" | sha256sum -c
  mv "$OUT.partial" "$OUT"
fi
echo "Validated original g170 network: $SHA256"
