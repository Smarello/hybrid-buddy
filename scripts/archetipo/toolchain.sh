#!/bin/bash
# Pinned, checksum-verified Android toolchain (JDK, Gradle, Android SDK) shared by
# every checkout of this project. Installs under ~/.cache/archetipo/toolchains,
# never touches system paths or the shell profile.
#
#   toolchain.sh install      idempotent download + verify + install (accepts SDK licenses)
#   toolchain.sh probe        verify the installed toolchain is usable
#   toolchain.sh run CMD...   run CMD with JAVA_HOME/ANDROID_HOME/PATH pointing at it
set -euo pipefail

TOOLCHAIN_ROOT="$HOME/.cache/archetipo/toolchains"

JDK_RELEASE="jdk-21.0.13+9"
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_aarch64_mac_hotspot_21.0.13_9.tar.gz"
JDK_SHA256="bf036b35b071bfb2f54f2c0af8ee8d88afa95ee6eb3dce02c0f94cfc2524d04a"

GRADLE_VERSION="9.8.2"
GRADLE_URL="https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"
GRADLE_SHA256="21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607"

# Google publishes only SHA-1 for SDK archives (repository2-3.xml).
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac_arm64-16111833_latest.zip"
CMDLINE_TOOLS_SHA1="ad03dc49bfacfd52c110b14104ea548b8a07e830"
# Android CLI package specs (sdkmanager is a deprecated shim over it in cmdline-tools 23).
SDK_PACKAGES=("platform-tools@37.0.1" "platforms/android-36@2.0.0" "build-tools/36.0.0@36.0.0")

JAVA_HOME_DIR="$TOOLCHAIN_ROOT/$JDK_RELEASE/Contents/Home"
GRADLE_HOME_DIR="$TOOLCHAIN_ROOT/gradle-$GRADLE_VERSION"
ANDROID_SDK_DIR="$TOOLCHAIN_ROOT/android-sdk"
ANDROID_CLI="$ANDROID_SDK_DIR/cmdline-tools/latest/bin/android"
INSTALL_LOCK="$TOOLCHAIN_ROOT/.install.lock"

fail() { echo "toolchain: $*" >&2; exit 1; }

export_toolchain_env() {
  export JAVA_HOME="$JAVA_HOME_DIR"
  export ANDROID_HOME="$ANDROID_SDK_DIR"
  export PATH="$JAVA_HOME_DIR/bin:$GRADLE_HOME_DIR/bin:$ANDROID_SDK_DIR/platform-tools:$PATH"
}

# download URL CHECKSUM ALGO(256|1) DEST
download_verified() {
  local url="$1" expected="$2" algo="$3" dest="$4" actual
  curl -fsSL --retry 3 -o "$dest" "$url"
  actual="$(shasum -a "$algo" "$dest" | cut -d' ' -f1)"
  [ "$actual" = "$expected" ] || fail "checksum mismatch for $url (sha$algo $actual, expected $expected)"
}

# install_archive NAME URL CHECKSUM ALGO FINAL_DIR: extract into a temp dir, then move atomically.
install_archive() {
  local name="$1" url="$2" checksum="$3" algo="$4" final_dir="$5" work
  [ -f "$final_dir/.archetipo-verified" ] && return 0
  echo "toolchain: installing $name"
  work="$(mktemp -d "$TOOLCHAIN_ROOT/.tmp-$name.XXXXXX")"
  download_verified "$url" "$checksum" "$algo" "$work/archive"
  mkdir "$work/extracted"
  case "$url" in
    *.tar.gz) tar -xzf "$work/archive" -C "$work/extracted" ;;
    *.zip) unzip -q "$work/archive" -d "$work/extracted" ;;
  esac
  # Every archive has a single top-level directory.
  local top
  top="$(find "$work/extracted" -mindepth 1 -maxdepth 1)"
  [ "$(printf '%s\n' "$top" | wc -l)" -eq 1 ] || fail "unexpected layout in $url"
  touch "$top/.archetipo-verified"
  rm -rf "$final_dir"
  mkdir -p "$(dirname "$final_dir")"
  mv "$top" "$final_dir"
  rm -rf "$work"
}

# has_revision PACKAGE_DIR REVISION
has_revision() {
  grep -qx "Pkg.Revision=$2" "$ANDROID_SDK_DIR/$1/source.properties" 2>/dev/null
}

sdk_packages_installed() {
  [ -x "$ANDROID_SDK_DIR/platform-tools/adb" ] &&
    has_revision platform-tools 37.0.1 &&
    has_revision platforms/android-36 2 &&
    has_revision build-tools/36.0.0 36.0.0 &&
    [ -f "$ANDROID_SDK_DIR/licenses/android-sdk-license" ]
}

install_toolchain() {
  [ "$(uname -s)/$(uname -m)" = "Darwin/arm64" ] || fail "pinned artifacts exist only for Darwin/arm64"
  mkdir -p "$TOOLCHAIN_ROOT"
  # Serialize concurrent installs from different checkouts.
  local waited=0
  until mkdir "$INSTALL_LOCK" 2>/dev/null; do
    waited=$((waited + 1))
    [ "$waited" -le 900 ] || fail "lock $INSTALL_LOCK held for 15 minutes; remove it only if no install is running"
    sleep 1
  done
  trap 'rmdir "$INSTALL_LOCK"' EXIT

  install_archive jdk "$JDK_URL" "$JDK_SHA256" 256 "$TOOLCHAIN_ROOT/$JDK_RELEASE"
  install_archive gradle "$GRADLE_URL" "$GRADLE_SHA256" 256 "$GRADLE_HOME_DIR"
  install_archive cmdline-tools "$CMDLINE_TOOLS_URL" "$CMDLINE_TOOLS_SHA1" 1 "$ANDROID_SDK_DIR/cmdline-tools/latest"

  export_toolchain_env
  if ! sdk_packages_installed; then
    # The Android CLI records SDK license acceptance itself; acceptance authorized by
    # the project owner (2026-11-05). Usage metrics are opted out.
    "$ANDROID_CLI" --no-metrics --sdk="$ANDROID_SDK_DIR" sdk install "${SDK_PACKAGES[@]}"
  fi
  sdk_packages_installed || fail "Android SDK packages missing after install"
  echo "toolchain: ready in $TOOLCHAIN_ROOT"
}

probe_toolchain() {
  export_toolchain_env
  local java_version
  java_version="$("$JAVA_HOME_DIR/bin/java" -version 2>&1 | head -1)"
  echo "$java_version"
  case "$java_version" in *'"21.0.13"'*) ;; *) fail "unexpected JDK: $java_version" ;; esac
  gradle --version --offline | grep -q "^Gradle $GRADLE_VERSION\$" || fail "Gradle $GRADLE_VERSION not usable"
  echo "Gradle $GRADLE_VERSION"
  # Offline check: the Android CLI's list command contacts Google's repository.
  sdk_packages_installed || fail "pinned SDK packages or license missing in $ANDROID_SDK_DIR"
  echo "Android SDK: ${SDK_PACKAGES[*]}"
  "$ANDROID_SDK_DIR/build-tools/36.0.0/aapt2" version
  adb version | head -1
}

case "${1:-}" in
  install) install_toolchain ;;
  probe) probe_toolchain ;;
  run)
    shift
    [ "$#" -gt 0 ] || fail "usage: toolchain.sh run CMD..."
    export_toolchain_env
    exec "$@"
    ;;
  *) fail "usage: toolchain.sh install|probe|run CMD..." ;;
esac
