#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

printf '\n=== MD Studio / Termux Android build setup ===\n'

pkg update -y
pkg upgrade -y
pkg install -y openjdk-21 android-sdk aapt2 apksigner d8 zipalign unzip curl git cmake make

export JAVA_HOME="$PREFIX/lib/jvm/java-21-openjdk"
export ANDROID_HOME="$PREFIX/opt/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$PREFIX/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.1.0:$PATH"

mkdir -p "$HOME/.termux" "$HOME/.gradle" "$HOME/tmp" "$HOME/.mdstudio"
TERMUX_PROPERTIES="$HOME/.termux/termux.properties"
if ! grep -q '^allow-external-apps=true$' "$TERMUX_PROPERTIES" 2>/dev/null; then
  printf '\nallow-external-apps=true\n' >> "$TERMUX_PROPERTIES"
fi

if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
elif [ -x "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" ]; then
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/bin/sdkmanager"
else
  echo "ERROR: sdkmanager not found under $ANDROID_HOME/cmdline-tools" >&2
  echo "Install the Android SDK command-line tools package for Termux, then rerun this script." >&2
  exit 2
fi

termux-fix-shebang "$SDKMANAGER" 2>/dev/null || true
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'

# Gradle 8.13 matches this project's Android Gradle Plugin 8.11.x requirement.
if [ ! -x "$HOME/.mdstudio/gradle-8.13/bin/gradle" ]; then
  curl -fL --retry 3 https://services.gradle.org/distributions/gradle-8.13-bin.zip -o "$HOME/.mdstudio/gradle-8.13-bin.zip"
  unzip -oq "$HOME/.mdstudio/gradle-8.13-bin.zip" -d "$HOME/.mdstudio"
  rm -f "$HOME/.mdstudio/gradle-8.13-bin.zip"
  termux-fix-shebang "$HOME/.mdstudio/gradle-8.13/bin/gradle" 2>/dev/null || true
fi

if [ -x "$PREFIX/bin/aapt2" ]; then
  if ! grep -q '^android.aapt2FromMavenOverride=' "$HOME/.gradle/gradle.properties" 2>/dev/null; then
    printf '\nandroid.aapt2FromMavenOverride=%s/bin/aapt2\n' "$PREFIX" >> "$HOME/.gradle/gradle.properties"
  fi
  BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
  mkdir -p "$BUILD_TOOLS"
  for tool in aapt2 apksigner zipalign; do
    if [ -x "$PREFIX/bin/$tool" ]; then ln -sf "$PREFIX/bin/$tool" "$BUILD_TOOLS/$tool"; fi
  done
else
  echo "ERROR: Termux-native aapt2 not found at $PREFIX/bin/aapt2" >&2
  exit 3
fi

termux-setup-storage || true

printf '\n=== Toolchain verification ===\n'
java -version
"$HOME/.mdstudio/gradle-8.13/bin/gradle" --version | head -n 8
printf 'ANDROID_HOME=%s\n' "$ANDROID_HOME"
test -s "$ANDROID_HOME/platforms/android-35/android.jar"
test -x "$PREFIX/bin/aapt2"
test -x "$PREFIX/bin/apksigner"
test -x "$PREFIX/bin/d8"
test -x "$PREFIX/bin/zipalign"

printf '\nMD Studio Termux setup completed. Restart Termux, then grant MD Studio the RUN_COMMAND permission in Android settings.\n'
