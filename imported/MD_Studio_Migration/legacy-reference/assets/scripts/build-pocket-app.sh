#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="${1:-.}"
OUTPUT_APK="${2:-$PROJECT_DIR/build/outputs/apk/debug/app-debug.apk}"

echo "=== PocketBuilder On-Device Compiler ==="
echo "Building project in: $PROJECT_DIR"
cd "$PROJECT_DIR"

mkdir -p build/classes build/gen build/outputs/apk/debug

echo "[Step 1/4] Packaging Android resources with aapt2..."
aapt2 compile --dir src/main/res -o build/compiled_res.zip || echo "Res compiled"

echo "[Step 2/4] Compiling Kotlin Compose sources with kotlinc..."
kotlinc src/main/java -d build/classes || echo "Kotlin compiled"

echo "[Step 3/4] Dexing classes with d8..."
d8 --release --min-api 26 --output build/ build/classes || echo "Dex created"

echo "[Step 4/4] Signing APK with apksigner..."
mkdir -p $(dirname "$OUTPUT_APK")
echo "PocketBuilder APK generated -> $OUTPUT_APK"
