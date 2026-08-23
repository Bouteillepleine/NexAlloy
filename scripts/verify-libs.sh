#!/usr/bin/env bash
# Verify the binaries committed to this repository still hash to what was reviewed.
#
# 2.9 MB of prebuilts are tracked here -- dexkit's AAR and native .so, the sources
# jar, and the Gradle wrapper -- with no upstream version recorded and no dependency
# verification. The .so is loaded into every hooked app's process
# (System.loadLibrary("dexkit")), so "it was in the repo already" is not a provenance
# story. This does not establish where they came from; it establishes that they have
# not changed since, which is the part CI can actually enforce.
#
# After a deliberate update: ./scripts/verify-libs.sh --update
set -euo pipefail

cd "$(dirname "$0")/.."
SUMS=scripts/vendored-libs.sha256

if [ "${1:-}" = "--update" ]; then
    sha256sum \
        libs/dexkit-android.aar \
        libs/dexkit-android-sources.jar \
        app/src/test/jniLibs/libdexkit.so \
        gradle/wrapper/gradle-wrapper.jar > "$SUMS"
    echo "updated $SUMS:"
    cat "$SUMS"
    exit 0
fi

if [ ! -f "$SUMS" ]; then
    echo "error: $SUMS is missing" >&2
    exit 1
fi

if sha256sum -c "$SUMS" --quiet; then
    echo "vendored binaries: $(wc -l < "$SUMS") file(s) verified"
else
    echo "error: a vendored binary does not match $SUMS." >&2
    echo "       If the change was intended, re-run with --update and commit the result." >&2
    exit 1
fi
