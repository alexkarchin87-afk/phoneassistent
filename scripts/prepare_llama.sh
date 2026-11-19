#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
revision=9a080f44b503c64f66c84a6274b9d9ee5d5b09d5
if [ -d "$root/.llama.cpp/.git" ] && [ "$(git -C "$root/.llama.cpp" rev-parse HEAD)" = "$revision" ]; then exit 0; fi
if [ -e "$root/.llama.cpp" ]; then echo 'Unexpected .llama.cpp directory; remove it yourself.' >&2; exit 1; fi
git init -q "$root/.llama.cpp"
git -C "$root/.llama.cpp" remote add origin https://github.com/ggml-org/llama.cpp.git
git -C "$root/.llama.cpp" fetch --depth 1 origin "$revision"
git -C "$root/.llama.cpp" checkout --detach FETCH_HEAD
test "$(git -C "$root/.llama.cpp" rev-parse HEAD)" = "$revision"
