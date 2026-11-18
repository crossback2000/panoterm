#!/usr/bin/env bash
# Builds the Go core (tsnet + SSH + SFTP) into app/libs/core.aar with gomobile.
# Linux/macOS counterpart of build-core.ps1 (used by GitHub Actions).
# Needs: Go (version from core/go.mod), JDK 17, Android SDK + NDK (x86_64 host — Google
# does not ship the NDK for ARM Linux, so build on CI or an x86_64 machine).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
export ANDROID_HOME
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  if [ -d "$ANDROID_HOME/ndk/29.0.14206865" ]; then
    ANDROID_NDK_HOME="$ANDROID_HOME/ndk/29.0.14206865"
  else
    ANDROID_NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)
  fi
fi
[ -n "$ANDROID_NDK_HOME" ] && [ -d "$ANDROID_NDK_HOME" ] || { echo "NDK not found under $ANDROID_HOME/ndk" >&2; exit 1; }
export ANDROID_NDK_HOME
echo "SDK: $ANDROID_HOME"
echo "NDK: $ANDROID_NDK_HOME"

cd "$ROOT/core"
export PATH="$(go env GOPATH)/bin:$PATH"
# gomobile/gobind at the same x/mobile version as go.mod (kept there by tools.go).
go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind
go mod download
gomobile init
mkdir -p "$ROOT/app/libs"
# CORE_TARGETS: release builds only ship arm64 (see app/build.gradle.kts abiFilters), so the
# release workflow sets CORE_TARGETS=android/arm64; the default also builds the emulator ABI.
TARGETS=${CORE_TARGETS:-android/arm64,android/amd64}
echo "Targets: $TARGETS"
gomobile bind -target="$TARGETS" -androidapi 31 -trimpath -ldflags='-s -w' -o "$ROOT/app/libs/core.aar" .
echo "Built app/libs/core.aar"
