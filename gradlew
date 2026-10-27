#!/usr/bin/env sh
# Living Villages bootstrap launcher. The upstream wrapper JAR is not bundled in this source archive.
# If system Gradle is available, use it; otherwise download the pinned official binary distribution.
set -eu

GRADLE_VERSION="9.2.1"
GRADLE_SHA256="72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f"

if command -v gradle >/dev/null 2>&1; then
    SYSTEM_GRADLE_VERSION="$(gradle --version 2>/dev/null | awk '/^Gradle / { print $2; exit }' || true)"
    if [ "$SYSTEM_GRADLE_VERSION" = "$GRADLE_VERSION" ]; then
        exec gradle --no-daemon "$@"
    fi
    echo "Living Villages: system Gradle version '${SYSTEM_GRADLE_VERSION:-unknown}' differs from pinned ${GRADLE_VERSION}; using the checksum-verified distribution." >&2
fi

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
CACHE_ROOT="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin/living-villages"
GRADLE_HOME="$CACHE_ROOT/gradle-${GRADLE_VERSION}"
GRADLE_BIN="$GRADLE_HOME/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    if ! command -v curl >/dev/null 2>&1; then
        echo "Living Villages: install Gradle ${GRADLE_VERSION} or install curl to download it." >&2
        exit 1
    fi
    if ! command -v unzip >/dev/null 2>&1; then
        echo "Living Villages: unzip is required to bootstrap Gradle ${GRADLE_VERSION}." >&2
        exit 1
    fi

    mkdir -p "$CACHE_ROOT"
    TMP_DIR="$(mktemp -d)"
    trap 'rm -rf "$TMP_DIR"' EXIT HUP INT TERM
    ZIP_PATH="$TMP_DIR/gradle-${GRADLE_VERSION}-bin.zip"
    URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
    echo "Gradle ${GRADLE_VERSION} is not cached; downloading the official distribution..." >&2
    curl --fail --location --retry 2 --connect-timeout 15 "$URL" --output "$ZIP_PATH"

    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s  %s\n' "$GRADLE_SHA256" "$ZIP_PATH" | sha256sum --check --status
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL="$(shasum -a 256 "$ZIP_PATH" | awk '{print $1}')"
        [ "$ACTUAL" = "$GRADLE_SHA256" ] || { echo "Gradle distribution SHA-256 mismatch; aborting." >&2; exit 1; }
    else
        echo "Living Villages: sha256sum or shasum is required to verify Gradle download." >&2
        exit 1
    fi

    unzip -q "$ZIP_PATH" -d "$TMP_DIR"
    rm -rf "$GRADLE_HOME"
    mv "$TMP_DIR/gradle-${GRADLE_VERSION}" "$GRADLE_HOME"
    trap - EXIT HUP INT TERM
    rm -rf "$TMP_DIR"
fi

exec "$GRADLE_BIN" --no-daemon "$@"
