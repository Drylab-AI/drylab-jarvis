#!/usr/bin/env bash
# Build the volrecd daemon with the NDK and package the Magisk module zip.
#
# Usage:
#   ANDROID_HOME=~/Android/Sdk ./build.sh
#
# Output: build/volrec.zip  (flashable Magisk module)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# Pick the newest installed NDK unless NDK_VERSION is set.
NDK_VERSION="${NDK_VERSION:-$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)}"
NDK="$ANDROID_HOME/ndk/$NDK_VERSION"
echo "Using NDK: $NDK"

HOST_TAG="linux-x86_64"
CLANG="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android34-clang"
if [ ! -x "$CLANG" ]; then
    echo "ERROR: clang not found at $CLANG" >&2
    exit 1
fi

BUILD="$HERE/build"
STAGE="$BUILD/stage"
rm -rf "$BUILD"
mkdir -p "$STAGE"

echo "Compiling volrecd (aarch64)..."
"$CLANG" -O2 -Wall -Wextra -static-libgcc \
    -o "$STAGE/volrecd" "$HERE/src/volrecd.c"

echo "Assembling module..."
cp -r "$HERE/module/." "$STAGE/"

# Standard Magisk module installer bits (update-binary is a stub; Magisk reads
# customize.sh). module.prop + customize.sh are the real content.
mkdir -p "$STAGE/META-INF/com/google/android"
cat > "$STAGE/META-INF/com/google/android/update-binary" <<'EOF'
#!/sbin/sh
#################
# Magisk module installer stub
#################
umask 022
OUTFD=$2
ZIPFILE=$3
. /data/adb/magisk/util_functions.sh 2>/dev/null || true
install_module
exit 0
EOF
echo '#MAGISK' > "$STAGE/META-INF/com/google/android/updater-script"

chmod 0755 "$STAGE/volrecd" "$STAGE/service.sh" "$STAGE/uninstall.sh" "$STAGE/customize.sh"

( cd "$STAGE" && zip -qr "$BUILD/volrec.zip" . )
echo "Built: $BUILD/volrec.zip"
unzip -l "$BUILD/volrec.zip"
