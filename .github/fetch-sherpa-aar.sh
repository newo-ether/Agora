#!/usr/bin/env bash
# app/libs/*.aar is git-ignored; CI downloads the pinned AAR that app/build.gradle.kts links.
set -euo pipefail
version=2.0.3
sha256=7744a3a8f08f017d59c2d8ed8d9b700345561f5abe9f4e06dc6eaef3a0b977b4
target="app/libs/sherpa-onnx-static-link-onnxruntime-$version.aar"
mkdir -p app/libs
curl -fL --retry 5 --retry-all-errors -o "$target" \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-static-link-onnxruntime-$version.aar"
echo "$sha256  $target" | sha256sum -c -
