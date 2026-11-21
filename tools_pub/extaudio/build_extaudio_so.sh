#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/bin:/bin:$PATH

BASE=/c/Users/PC/AppData/Local/Temp/opencode/ffmpeg-build
OUT=$BASE/out-arm64
NDK=/d/ruanjian/kaifaHuanJing/android-sdk/ndk/28.2.13676358
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/windows-x86_64
export PATH="$TOOLCHAIN/bin:$PATH"

mkdir -p "$BASE/jniLibs/arm64-v8a"

aarch64-linux-android28-clang \
  -shared -fPIC -O2 -fvisibility=hidden \
  -ffunction-sections -fdata-sections \
  -Wl,--gc-sections \
  -Wl,-z,relro,-z,now \
  -Wl,--exclude-libs,ALL \
  -I"$OUT/include" \
  "$BASE/extaudio_jni.c" \
  -L"$OUT/lib" \
  -lavformat -lavcodec -lswresample -lavutil \
  -lm -llog -lz \
  -o "$BASE/jniLibs/arm64-v8a/libextaudio.so"

llvm-strip "$BASE/jniLibs/arm64-v8a/libextaudio.so" || true
echo "SO_OK"
ls -lh "$BASE/jniLibs/arm64-v8a/libextaudio.so"
