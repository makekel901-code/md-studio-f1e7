#!/data/data/com.termux/files/usr/bin/bash
set -e

echo "=== PocketBuilder Termux Environment Setup ==="
echo "[1/4] Updating package repositories..."
pkg update -y

echo "[2/4] Installing OpenJDK 17 & Kotlin compiler..."
pkg install -y openjdk-17 kotlin

echo "[3/4] Installing Android build tools (aapt2, d8, apksigner, ecj)..."
pkg install -y android-tools

echo "[4/4] Verifying installations..."
java -version
kotlinc -version
echo "=== PocketBuilder Environment Ready! ==="
