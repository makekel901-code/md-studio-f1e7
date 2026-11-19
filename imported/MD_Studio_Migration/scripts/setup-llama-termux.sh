#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

pkg update -y
pkg install -y git cmake clang make libandroid-spawn

if [ ! -d "$HOME/llama.cpp/.git" ]; then
  git clone --depth 1 https://github.com/ggml-org/llama.cpp "$HOME/llama.cpp"
else
  git -C "$HOME/llama.cpp" pull --ff-only
fi

cmake -S "$HOME/llama.cpp" -B "$HOME/llama.cpp/build" \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_OPENMP=OFF \
  -DGGML_NATIVE=ON

cmake --build "$HOME/llama.cpp/build" --target llama-cli -j2

test -x "$HOME/llama.cpp/build/bin/llama-cli"
printf '\nLlama.cpp CLI built successfully. MD Studio can now send prompts to this local engine through Termux.\n'
