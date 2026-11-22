#!/usr/bin/env bash
set -euo pipefail

# Reproducible ARM64 build from the official KataGo sources.
# The executable is *named* libkatago.so only so Android's APK packager
# extracts it into the executable nativeLibraryDir. It is NOT a JNI library.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMMIT="6a1fc5de9fc253723ac475a0683bf0b9d9b7bd19" # upstream v1.17.2
SRC="${KATAGO_SOURCE_DIR:-$ROOT/.native-build/KataGo}"
BUILD="$ROOT/.native-build/android-arm64"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ANDROID_NDK_HOME" || ! -f "$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" ]]; then
  echo "Set ANDROID_NDK_HOME to an installed NDK directory" >&2
  exit 1
fi
if [[ ! -d "$SRC/.git" ]]; then
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch v1.17.2 https://github.com/lightvector/KataGo.git "$SRC"
fi
test "$(git -C "$SRC" rev-parse HEAD)" = "$COMMIT" || {
  echo "KataGo source revision differs from pinned v1.17.2 commit" >&2
  exit 1
}
EIGEN3_INCLUDE_DIRS="${EIGEN3_INCLUDE_DIRS:-/usr/include/eigen3}"
test -f "$EIGEN3_INCLUDE_DIRS/Eigen/Core" || {
  echo "Missing Eigen3 headers (set EIGEN3_INCLUDE_DIRS)" >&2
  exit 1
}

cmake -S "$SRC/cpp" -B "$BUILD" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-34 \
  -DANDROID_STL=c++_static \
  -DCMAKE_CXX_FLAGS="-DBYTE_ORDER=1234 -DLITTLE_ENDIAN=1234 -DBIG_ENDIAN=4321" \
  -DUSE_BACKEND=EIGEN \
  -DEIGEN3_INCLUDE_DIRS="$EIGEN3_INCLUDE_DIRS" \
  -DNO_GIT_REVISION=1 \
  -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
cmake --build "$BUILD" --target katago --parallel "${KATAGO_JOBS:-2}"
test -s "$BUILD/katago"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$BUILD/katago" "$ROOT/app/src/main/jniLibs/arm64-v8a/libkatago.so"
echo "Packaged native executable: $(du -h "$ROOT/app/src/main/jniLibs/arm64-v8a/libkatago.so")"
