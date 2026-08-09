#!/bin/bash
# Compile libwhisper.so (avec whisper_jni.cpp inclus) pour Android arm64-v8a.
# Nécessite : Android NDK 27+, CMake, QEMU user-mode sur hôte aarch64.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$SCRIPT_DIR")"
THIRD_PARTY="$ROOT/third_party"
JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/27.3.13750724}"

mkdir -p "$JNILIBS"

echo "=== Clone whisper.cpp (si absent) ==="
mkdir -p "$THIRD_PARTY"
cd "$THIRD_PARTY"
if [ ! -d whisper.cpp ]; then
    git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git
fi

echo "=== Copie du JNI + patch CMake ==="
cp "$ROOT/app/src/main/cpp/whisper_jni.cpp" whisper.cpp/src/
cd whisper.cpp
if ! grep -q "whisper_jni.cpp" src/CMakeLists.txt; then
    sed -i '/whisper\.cpp/a\            whisper_jni.cpp' src/CMakeLists.txt
fi

echo "=== Configuration CMake (NDK $NDK) ==="
rm -rf build-android
mkdir -p build-android && cd build-android
cmake .. \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-29 \
    -DWHISPER_SUPPORT_VULKAN=OFF \
    -DCMAKE_BUILD_TYPE=Release \
    -DWHISPER_BUILD_TESTS=OFF \
    -DWHISPER_BUILD_EXAMPLES=OFF 2>&1 | tail -5

echo "=== Compilation ==="
make -j"$(nproc)" whisper 2>&1 | tail -5

echo "=== Copie des .so ==="
find . -name "libwhisper.so" -o -name "libggml*.so" | head
cp src/libwhisper.so "$JNILIBS/" 2>/dev/null || cp bin/libwhisper.so "$JNILIBS/"
find . -name "libggml*.so" -exec cp {} "$JNILIBS/" \;
# libc++_shared.so du MÊME NDK que la compilation
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNILIBS/" 2>/dev/null || true
# libomp.so (OpenMP) — dépendance des libs ggml quand OpenMP est activé (piège v0.1.1)
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18/lib/linux/aarch64/libomp.so" "$JNILIBS/" 2>/dev/null || true

echo "=== Résultat ==="
ls -lh "$JNILIBS/"

echo "=== Vérification dépendances NEEDED ==="
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
MISSING=0
for lib in "$JNILIBS"/*.so; do
    name="$(basename "$lib")"
    for dep in $($READELF -d "$lib" | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/'); do
        case "$dep" in
            libc.so|libm.so|libdl.so|liblog.so|libz.so|libandroid.so) ;;  # système Android
            *)
                if [ ! -f "$JNILIBS/$dep" ]; then
                    echo "  ❌ $name → $dep MANQUANTE"
                    MISSING=1
                fi
                ;;
        esac
    done
done
if [ "$MISSING" -eq 0 ]; then
    echo "  ✓ Toutes les dépendances natives sont couvertes"
fi

echo "=== Vérification symboles JNI ==="
$READELF -Ws "$JNILIBS/libwhisper.so" | grep "Java_" || echo "ATTENTION: aucun symbole Java_ trouvé"
