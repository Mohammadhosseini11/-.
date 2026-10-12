#!/usr/bin/env bash
# =============================================================
# بیلد hev-socks5-tunnel با تشخیص خودکار روش
#   مسیر ۱: android/build.sh رسمی (قدیمی)
#   مسیر ۲: ndk-build با Android.mk در ریشه (ساختار فعلی hev) — مسیرهای مطلق
#   مسیر ۳: ndk-build با android/jni/Android.mk (قدیمی)
#   مسیر ۴: CMakeLists.txt
# خروجی: hev-out/<ABI>/*.so
# =============================================================
set -e

HEV="${HEV_DIR:-third_party/hev-socks5-tunnel}"
OUT="${OUT_DIR:-hev-out}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
NDK="${ANDROID_NDK_HOME:-${NDK_PATH:-}}"
ROOT="$PWD"

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "FATAL: مسیر NDK مشخص نیست (NDK_PATH یا ANDROID_NDK_HOME)"
  exit 1
fi
# تبدیل به مسیر مطلق
NDK="$(cd "$NDK" && pwd)"
HEV="$(cd "$HEV" && pwd)"

if [ ! -d "$HEV" ]; then
  echo "FATAL: پوشهٔ $HEV وجود ندارد"
  exit 1
fi
export ANDROID_NDK_HOME="$NDK"

echo "== hev: init submodules =="
(cd "$HEV" && git submodule update --init --recursive) || true

echo "== hev: فایل‌های بیلد موجود =="
ls -la "$HEV/Android.mk" "$HEV/Application.mk" 2>/dev/null || true

built=0

# ── مسیر ۱: build.sh رسمی (قدیمی) ──
if [ "$built" -eq 0 ] && [ -f "$HEV/android/build.sh" ]; then
  echo "== مسیر ۱: android/build.sh =="
  chmod +x "$HEV/android/build.sh"
  ok=1
  for A in $ABIS; do
    ( cd "$HEV/android" && ./build.sh "$A" ) || { ok=0; break; }
  done
  [ "$ok" -eq 1 ] && built=1
fi

# ── مسیر ۲: ndk-build با Android.mk ریشه (مسیرهای مطلق) ──
if [ "$built" -eq 0 ] && [ -f "$HEV/Android.mk" ]; then
  echo "== مسیر ۲: ndk-build با Android.mk ریشه =="
  APPMK="$HEV/Application.mk"
  [ -f "$APPMK" ] || APPMK="$HEV/Android.mk"
  ok=1
  for A in $ABIS; do
    ( cd "$HEV" && "$NDK/ndk-build" -B \
        "NDK_PROJECT_PATH=$HEV" \
        "NDK_APPLICATION_MK=$APPMK" \
        "APP_BUILD_SCRIPT=$HEV/Android.mk" \
        "NDK_OUT=$HEV/obj-$A" \
        "NDK_LIBS_OUT=$HEV/libs-$A" \
        "APP_ABI=$A" \
        "APP_PLATFORM=android-24" \
        "APP_STL=none" ) || { ok=0; break; }
    mkdir -p "$OUT/$A"
    find "$HEV/libs-$A/$A" -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
    find "$HEV/libs-$A" -maxdepth 1 -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
  done
  [ "$ok" -eq 1 ] && built=1
fi

# ── مسیر ۳: ndk-build با android/jni/Android.mk (قدیمی) ──
if [ "$built" -eq 0 ] && [ -f "$HEV/android/jni/Android.mk" ]; then
  echo "== مسیر ۳: android/jni/Android.mk =="
  ( cd "$HEV/android" && "$NDK/ndk-build" \
      NDK_PROJECT_PATH=. \
      NDK_APPLICATION_MK="$PWD/jni/Application.mk" \
      APP_BUILD_SCRIPT="$PWD/jni/Android.mk" \
      NDK_OUT="$PWD/obj" NDK_LIBS_OUT="$PWD/libs" ) && built=1
  if [ "$built" -eq 1 ]; then
    for A in $ABIS; do
      mkdir -p "$OUT/$A"
      find "$HEV/android/libs/$A" -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
    done
  fi
fi

# ── مسیر ۴: CMake ──
if [ "$built" -eq 0 ]; then
  CML=$(find "$HEV" -maxdepth 2 -name CMakeLists.txt 2>/dev/null | head -1 || true)
  if [ -n "$CML" ]; then
    echo "== مسیر ۴: CMake ($(dirname "$CML")) =="
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

if [ "$built" -ne 1 ]; then
  echo "!! هیچ روش بیلد کار نکرد"
  exit 1
fi

# ── جمع‌آوری نهایی خروجی‌ها (از همهٔ مسیرهای ممکن) ──
mkdir -p "$OUT"
for A in $ABIS; do
  mkdir -p "$OUT/$A"
  find "$HEV/libs-$A/$A"     -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
  find "$HEV/libs-$A"        -maxdepth 1 -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
  find "$HEV/android/libs/$A" -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
  find "build-hev/$A"        -name "*.so" -exec cp {} "$OUT/$A/" \; 2>/dev/null || true
done

echo "== خروجی‌های hev =="
find "$OUT" -type f | sort
N=$(find "$OUT" -type f | wc -l)
if [ "$N" -eq 0 ]; then
  echo "!! هیچ .a/.so پیدا نشد — جستجوی گسترده:"
  find "$HEV" -name "*.so" -o -name "*.a" | head -40
  exit 1
fi
echo "hev build OK ✓"
