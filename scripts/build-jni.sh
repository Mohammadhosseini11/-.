#!/usr/bin/env bash
# =============================================================
# بیلد پل JNI (libgozar-tun.so) و لینک با hev
# خروجی: app/src/main/jniLibs/<ABI>/libgozar-tun.so
#        (+ libhev-socks5-tunnel.so اگر hev داینامیک بود)
#
# متغیرهای محیطی:
#   NDK_PATH یا ANDROID_NDK_HOME  — مسیر NDK (الزامی)
#   HEV_OUT  (پیش‌فرض hev-out)
#   ABIS     (پیش‌فرض "arm64-v8a armeabi-v7a x86_64")
# =============================================================
set -e

NDK="${ANDROID_NDK_HOME:-${NDK_PATH:-}}"
HEV_OUT="${HEV_OUT:-hev-out}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "FATAL: مسیر NDK مشخص نیست"
  exit 1
fi
if [ ! -f "app/src/main/cpp/native-tun.c" ]; then
  echo "FATAL: app/src/main/cpp/native-tun.c پیدا نشد — از ریشهٔ ریپو اجرا کنید"
  exit 1
fi

for ABI in $ABIS; do
  STATIC_ARG=""
  A_FILE=$(find "$HEV_OUT/$ABI" -name "*.a" 2>/dev/null | head -1 || true)
  if [ -n "$A_FILE" ]; then
    echo "[$ABI] hev استاتیک → لینک مستقیم: $A_FILE"
    STATIC_ARG="-DHEV_STATIC_LIB=$PWD/$A_FILE"
  else
    echo "[$ABI] hev داینامیک → dlopen در زمان اجرا"
  fi
  cmake -S app/src/main/cpp -B build-shim/$ABI \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release $STATIC_ARG
  cmake --build build-shim/$ABI
  mkdir -p app/src/main/jniLibs/$ABI
  find build-shim/$ABI -name "libgozar-tun.so" -exec cp {} app/src/main/jniLibs/$ABI/ \;
  if [ -z "$A_FILE" ]; then
    find "$HEV_OUT/$ABI" -name "libhev-socks5-tunnel.so" \
      -exec cp {} app/src/main/jniLibs/$ABI/ \; 2>/dev/null || true
  fi
done

echo "== jniLibs =="
ls -la app/src/main/jniLibs/*/
echo "JNI bridge build OK ✓"
