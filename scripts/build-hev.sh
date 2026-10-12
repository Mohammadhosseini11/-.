#!/usr/bin/env bash
# =============================================================
# بیلد hev-socks5-tunnel با تشخیص خودکار روش
#   مسیر ۱: android/build.sh رسمی مخزن hev
#   مسیر ۲: CMakeLists.txt
#   مسیر ۳: ndk-build (jni/Android.mk)
# خروجی: hev-out/<ABI>/*.a یا *.so
#
# متغیرهای محیطی:
#   NDK_PATH یا ANDROID_NDK_HOME  — مسیر NDK (الزامی)
#   HEV_DIR   (پیش‌فرض third_party/hev-socks5-tunnel)
#   OUT_DIR   (پیش‌فرض hev-out)
#   ABIS      (پیش‌فرض "arm64-v8a armeabi-v7a x86_64")
# =============================================================
set -e

HEV="${HEV_DIR:-third_party/hev-socks5-tunnel}"
OUT="${OUT_DIR:-hev-out}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
NDK="${ANDROID_NDK_HOME:-${NDK_PATH:-}}"

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "FATAL: مسیر NDK مشخص نیست (NDK_PATH یا ANDROID_NDK_HOME)"
  exit 1
fi
if [ ! -d "$HEV" ]; then
  echo "FATAL: پوشهٔ $HEV وجود ندارد — ابتدا مخزن hev را دریافت کنید"
  exit 1
fi
export ANDROID_NDK_HOME="$NDK"

echo "== hev: init submodules =="
(cd "$HEV" && git submodule update --init --recursive) || true

built=0

# ── مسیر ۱: build.sh رسمی ──
if [ "$built" -eq 0 ] && [ -f "$HEV/android/build.sh" ]; then
  echo "== مسیر ۱: build.sh رسمی hev =="
  chmod +x "$HEV/android/build.sh"
  ok=1
  for A in $ABIS; do
    ( cd "$HEV/android" && ./build.sh "$A" ) || { ok=0; break; }
  done
  [ "$ok" -eq 1 ] && built=1
fi

# ── مسیر ۲: CMake ──
if [ "$built" -eq 0 ]; then
  CML=$(find "$HEV" -maxdepth 2 -name CMakeLists.txt 2>/dev/null | head -1 || true)
  if [ -n "$CML" ]; then
    echo "== مسیر ۲: CMake ($(dirname "$CML")) =="
    for A in $ABIS; do
      cmake -S "$(dirname "$CML")" -B "build-hev/$A" \
        -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$A" -DANDROID_PLATFORM=android-24 \
        -DBUILD_SHARED_LIBS=ON -DCMAKE_BUILD_TYPE=Release
      cmake --build "build-hev/$A"
    done
    built=1
  fi
fi

# ── مسیر ۳: ndk-build ──
if [ "$built" -eq 0 ] && [ -f "$HEV/android/jni/Android.mk" ]; then
  echo "== مسیر ۳: ndk-build =="
  ( cd "$HEV/android" && "$NDK/ndk-build" \
      NDK_PROJECT_PATH=. \
      NDK_APPLICATION_MK="$PWD/jni/Application.mk" \
      APP_BUILD_SCRIPT="$PWD/jni/Android.mk" \
      NDK_OUT="$PWD/obj" NDK_LIBS_OUT="$PWD/libs" ) && built=1
fi

if [ "$built" -ne 1 ]; then
  echo "!! هیچ روش بیلد برای hev کار نکرد — ساختار مخزن:"
  find "$HEV" -maxdepth 2 | head -80
  exit 1
fi

# ── جمع‌آوری خروجی‌ها به تفکیک ABI ──
rm -rf "$OUT" && mkdir -p "$OUT"
for A in $ABIS; do
  mkdir -p "$OUT/$A"
  ( find "$HEV" "build-hev/$A" -path "*$A*" \( -name "*.a" -o -name "*.so" \) \
      -exec cp {} "$OUT/$A/" \; 2>/dev/null ) || true
done

echo "== خروجی‌های hev =="
find "$OUT" -type f | sort
N=$(find "$OUT" -type f | wc -l)
if [ "$N" -eq 0 ]; then
  echo "!! هیچ .a/.so از hev پیدا نشد — جستجوی گسترده:"
  find "$HEV" -name "*.a" -o -name "*.so" | head -40
  exit 1
fi
echo "hev build OK ✓"
