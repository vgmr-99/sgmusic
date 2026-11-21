#!/usr/bin/env bash
set -euo pipefail
export PATH=/usr/bin:/bin:$PATH

SRC=/c/Users/PC/AppData/Local/Temp/opencode/ffmpeg-build/ffmpeg-6.0
OUT=/c/Users/PC/AppData/Local/Temp/opencode/ffmpeg-build/out-arm64
NDK=/d/ruanjian/kaifaHuanJing/android-sdk/ndk/28.2.13676358
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/windows-x86_64

export PATH="/d/ruanjian/kaifaHuanJing/tools/make:$TOOLCHAIN/bin:$PATH"
export CC=aarch64-linux-android28-clang
export CXX=aarch64-linux-android28-clang++

rm -rf "$OUT"
mkdir -p "$OUT"

cd "$SRC"
make distclean >/dev/null 2>&1 || true

./configure \
  --prefix="$OUT" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --cc="$CC" \
  --cxx="$CXX" \
  --cross-prefix=aarch64-linux-android- \
  --ar=llvm-ar \
  --ranlib=llvm-ranlib \
  --nm=llvm-nm \
  --sysroot="$TOOLCHAIN/sysroot" \
  --extra-cflags="-fPIC -O2 -fvisibility=hidden -ffunction-sections -fdata-sections" \
  --extra-ldflags="-Wl,--gc-sections" \
  --disable-everything \
  --enable-decoder=ape,wmav1,wmav2,wmapro,wmalossless,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar \
  --enable-demuxer=ape,asf,dsf,aiff,iff \
  --enable-protocol=file \
  --enable-swresample \
  --enable-static \
  --disable-shared \
  --disable-programs \
  --disable-doc \
  --disable-network \
  --disable-autodetect \
  --enable-small

make -j4
make install

echo "BUILD_OK"
ls -lh "$OUT/lib"/*.a
