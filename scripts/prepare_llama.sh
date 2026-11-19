#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
revision=ec7f3ac9ab33e46b136eb5ab6a76c4d81f57c7f1
if [ -d "$root/.llama.cpp/.git" ] && [ "$(git -C "$root/.llama.cpp" rev-parse HEAD)" = "$revision" ]; then exit 0; fi
if [ -e "$root/.llama.cpp" ]; then echo 'Unexpected .llama.cpp directory; remove it yourself.' >&2; exit 1; fi
git clone --depth 1 --branch b4514 https://github.com/ggml-org/llama.cpp.git "$root/.llama.cpp"
test "$(git -C "$root/.llama.cpp" rev-parse HEAD)" = "$revision"
