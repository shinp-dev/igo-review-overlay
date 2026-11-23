#!/usr/bin/env bash
set -euo pipefail

# Official g170 network (CC0 exception documented by katagotraining.org).
# This small model is the offline baseline; 9x9-specialized model can be added
# as a user-imported optional network after on-device speed/accuracy testing.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Android's asset merger expands files ending in .gz and removes the suffix.
# Keep the original gzip bytes for the runtime SHA-256 check.
OUT="$ROOT/app/src/main/assets/katago/model.bin.gz.asset"
URL="https://katagoarchive.org/g170/neuralnets/g170-b6c96-s175395328-d26788732.bin.gz"
SHA256="f57fddf4672364d385d6ab177364ab819810d1123e229cb2649c4f337a2160b1"
mkdir -p "$(dirname "$OUT")"
if [[ ! -s "$OUT" ]] || ! echo "$SHA256  $OUT" | sha256sum -c --status; then
  curl --fail --location --retry 3 --connect-timeout 20 "$URL" --output "$OUT.partial"
  echo "$SHA256  $OUT.partial" | sha256sum -c
  mv "$OUT.partial" "$OUT"
fi
echo "Validated original g170 network: $SHA256"

# Optional-at-runtime, built into the APK so switching models needs no network.
# Official KataGo dedicated 9x9 network, ~98MB compressed.
OUT_9X9="$ROOT/app/src/main/assets/katago/kata9x9.bin.gz.asset"
URL_9X9="https://github.com/lightvector/KataGo/releases/download/v1.13.2-kata9x9/kata9x9-b18c384nbt-20231025.bin.gz"
SHA256_9X9="a1298ce1adc1dad7bd868ca962b2384cc8388ed373a00e6bae1114fa6f9e2d61"
if [[ ! -s "$OUT_9X9" ]] || ! echo "$SHA256_9X9  $OUT_9X9" | sha256sum -c --status; then
  curl --fail --location --retry 3 --connect-timeout 20 "$URL_9X9" --output "$OUT_9X9.partial"
  echo "$SHA256_9X9  $OUT_9X9.partial" | sha256sum -c
  mv "$OUT_9X9.partial" "$OUT_9X9"
fi
echo "Validated official dedicated 9x9 network: $SHA256_9X9"
