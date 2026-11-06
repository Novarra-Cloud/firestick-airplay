#!/usr/bin/env bash
# Cross-compiles OpenSSL's libcrypto as a static library for the Android ABIs used by
# the app. Output: build-deps/openssl/<abi>/{include,lib/libcrypto.a}
#
# Requires ANDROID_NDK_HOME (or ANDROID_NDK_ROOT) pointing at the NDK named in ndk.version.
set -euo pipefail

OPENSSL_VERSION="3.5.9"
OPENSSL_SHA256="603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a"
API_LEVEL=24
ABIS=("armeabi-v7a" "arm64-v8a")

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build-deps/openssl"
WORK="$ROOT/build-deps/work"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"

if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "Set ANDROID_NDK_HOME to the Android NDK ($(cat "$ROOT/ndk.version"))." >&2
    exit 1
fi

all_built=1
for abi in "${ABIS[@]}"; do
    [[ -f "$OUT/$abi/lib/libcrypto.a" ]] || all_built=0
done
if [[ $all_built == 1 ]]; then
    echo "OpenSSL already built in $OUT"
    exit 0
fi

mkdir -p "$WORK"
tarball="$WORK/openssl-$OPENSSL_VERSION.tar.gz"
if [[ ! -f "$tarball" ]]; then
    curl -fsSL -o "$tarball" \
        "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
fi
echo "$OPENSSL_SHA256  $tarball" | sha256sum -c -

export ANDROID_NDK_ROOT="$NDK"
host_tag="$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
export PATH="$NDK/toolchains/llvm/prebuilt/$host_tag/bin:$PATH"

for abi in "${ABIS[@]}"; do
    if [[ -f "$OUT/$abi/lib/libcrypto.a" ]]; then
        continue
    fi
    case "$abi" in
        armeabi-v7a) target=android-arm ;;
        arm64-v8a)   target=android-arm64 ;;
        x86_64)      target=android-x86_64 ;;
        x86)         target=android-x86 ;;
        *) echo "unknown ABI $abi" >&2; exit 1 ;;
    esac
    src="$WORK/src-$abi"
    rm -rf "$src"
    mkdir -p "$src"
    tar -xzf "$tarball" -C "$src" --strip-components=1
    echo "=== Building OpenSSL $OPENSSL_VERSION for $abi"
    (
        cd "$src"
        ./Configure "$target" -D__ANDROID_API__=$API_LEVEL \
            no-shared no-tests no-apps no-docs no-engine no-dso no-ui-console no-legacy \
            no-module no-quic -fPIC \
            --prefix="$OUT/$abi" --openssldir="$OUT/$abi/ssl" --libdir=lib
        make -j"$(nproc)" build_libs >/dev/null
        make install_dev >/dev/null
    )
    rm -rf "$src"
done
echo "OpenSSL libraries are in $OUT"
