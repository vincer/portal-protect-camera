#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Set ANDROID_HOME or ANDROID_SDK_ROOT}}"
bt="$sdk/build-tools/37.0.0"
jar="$sdk/platforms/android-36/android.jar"
mkdir -p build/classes build/dex
javac -source 8 -target 8 -classpath "$jar" -d build/classes *.java
"$bt/d8" --lib "$jar" --min-api 28 --output build/dex build/classes/local/codex/portalprotectcamera/*.class
"$bt/aapt" package -f -M AndroidManifest.xml -I "$jar" -A assets -F build/camera-unsigned.apk
(cd build/dex && zip -q -u ../camera-unsigned.apk classes.dex)
if [[ ! -f build/camera.keystore ]]; then
  keytool -genkeypair -keystore build/camera.keystore -storepass android -keypass android -alias camera -dname "CN=Portal Protect Camera" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$bt/zipalign" -f 4 build/camera-unsigned.apk build/camera-aligned.apk
"$bt/apksigner" sign --ks build/camera.keystore --ks-pass pass:android --out build/portal-protect-camera.apk build/camera-aligned.apk
echo "Built build/portal-protect-camera.apk"
