#!/usr/bin/env sh
# Populate app/binaries with the APKs the fingerprint tests run against.
#
# The APKs are pulled off an attached device over adb — whatever the Play Store has installed
# there, which is the newest build Play offers for that device. That keeps the check honest
# (it is the binary users actually run) without automating Play downloads or storing Google
# account credentials anywhere.
#
# Usage:
#   tools/fetch-target-apks.sh [package ...]
#
# With no arguments it fetches the apps the fingerprint tests know how to check.
# Set ADB_SERIAL to choose between multiple attached devices.

set -eu

# Git Bash rewrites /data/... into a Windows path before adb ever sees it.
MSYS_NO_PATHCONV=1
MSYS2_ARG_CONV_EXCL='*'
export MSYS_NO_PATHCONV MSYS2_ARG_CONV_EXCL

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BINARIES_DIR="$SCRIPT_DIR/../app/binaries"

# Only these three are wired into FingerprintsKtTest's app detection.
DEFAULT_PACKAGES="com.google.android.youtube com.google.android.apps.youtube.music com.reddit.frontpage"
PACKAGES=${*:-$DEFAULT_PACKAGES}

adb_() {
    if [ -n "${ADB_SERIAL:-}" ]; then
        adb -s "$ADB_SERIAL" "$@"
    else
        adb "$@"
    fi
}

if ! command -v adb >/dev/null 2>&1; then
    echo "adb not found on PATH." >&2
    exit 1
fi

if [ -z "$(adb_ get-state 2>/dev/null || true)" ]; then
    echo "No device reachable over adb. Attach the phone and enable USB debugging." >&2
    exit 1
fi

mkdir -p "$BINARIES_DIR"

fetched=0
for pkg in $PACKAGES; do
    # A Play-installed app is usually a split APK; the dex the fingerprints match lives in
    # base.apk, the other splits are resources and native libs.
    base=$(adb_ shell pm path "$pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep 'base\.apk$' | head -1 || true)
    if [ -z "$base" ]; then
        echo "skip $pkg: not installed on the device"
        continue
    fi

    version=$(adb_ shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' | sed -n 's/^ *versionName=//p' | head -1 || true)
    out="$BINARIES_DIR/$pkg.apk"
    adb_ pull "$base" "$out" >/dev/null
    echo "pulled $pkg ${version:-?} -> app/binaries/$pkg.apk"
    fetched=$((fetched + 1))
done

if [ "$fetched" -eq 0 ]; then
    echo "No target apps are installed on the device; nothing to test against." >&2
    exit 1
fi

echo
echo "Now run:  ./gradlew :app:testDebugUnitTest"
