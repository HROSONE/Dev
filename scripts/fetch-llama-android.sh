#!/usr/bin/env bash
# Baixa o binário oficial Android arm64 do ggml-org/llama.cpp e coloca em
# app/src/main/jniLibs/arm64-v8a/libllama_server.so (+ libs .so)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNI_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNI_DIR"

if [[ -f "$JNI_DIR/libllama_server.so" ]] && [[ $(stat -c%s "$JNI_DIR/libllama_server.so" 2>/dev/null || echo 0) -gt 50000 ]]; then
  echo "Já existe libllama_server.so ($(stat -c%s "$JNI_DIR/libllama_server.so") bytes)"
  ls -la "$JNI_DIR"
  exit 0
fi

# Pin estável com asset android-arm64 (releases recentes nem sempre publicam o asset)
TAG="${LLAMA_TAG:-b9782}"
URL="https://github.com/ggml-org/llama.cpp/releases/download/${TAG}/llama-${TAG}-bin-android-arm64.tar.gz"

TMP="/tmp/llama-android-$$"
mkdir -p "$TMP"
ARCHIVE="$TMP/llama-android-arm64.tar.gz"

echo "Baixando $URL"
HEADERS=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then
  HEADERS+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
fi
HEADERS+=(-H "User-Agent: DevAgent-CI" -L --fail --retry 3 --retry-delay 2)

curl "${HEADERS[@]}" -o "$ARCHIVE" "$URL"

echo "Extraindo..."
tar -xzf "$ARCHIVE" -C "$TMP"

# llama-server -> libllama_server.so
SERVER=$(find "$TMP" -type f -name 'llama-server' | head -n 1 || true)
if [[ -z "$SERVER" ]]; then
  echo "ERRO: llama-server não encontrado no tarball"
  find "$TMP" -type f | head -50
  exit 1
fi
cp "$SERVER" "$JNI_DIR/libllama_server.so"
chmod 755 "$JNI_DIR/libllama_server.so"
echo "server: $(wc -c < "$JNI_DIR/libllama_server.so") bytes"

# libs compartilhadas
while IFS= read -r so; do
  base=$(basename "$so")
  # normaliza libfoo.so.1 -> libfoo.so
  if [[ "$base" == *.so.* ]]; then
    base="${base%%.so*}.so"
  fi
  dest="$JNI_DIR/$base"
  if [[ ! -f "$dest" ]]; then
    cp "$so" "$dest"
    chmod 755 "$dest"
    echo "lib: $base"
  fi
done < <(find "$TMP" -type f -name '*.so' -o -name 'lib*.so*')

ls -la "$JNI_DIR"
echo "OK llama.cpp empacotado em $JNI_DIR"
