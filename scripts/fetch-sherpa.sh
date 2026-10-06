#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p phone/libs
target=phone/libs/sherpa-onnx-1.13.8.aar
checksum=633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96
if [ -f "$target" ] && printf '%s  %s\n' "$checksum" "$target" | sha256sum -c -; then exit 0; fi
curl --fail --location --retry 3 --connect-timeout 30 --max-time 600 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar -o "$target.part"
printf '%s  %s\n' "$checksum" "$target.part" | sha256sum -c -
mv "$target.part" "$target"
