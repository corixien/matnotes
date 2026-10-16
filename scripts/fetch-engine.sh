#!/usr/bin/env bash
# Fetch the prebuilt LibreOffice engine (MPL-2.0) from the official TDF Android APK on F-Droid
# and unpack native libs + assets into the app. Nothing here is committed (see .gitignore).
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION_CODE=131   # org.documentfoundation.libreoffice 26.2.6.3, arm64-v8a
APK=engine/lo.apk
mkdir -p engine
if [ ! -f "$APK" ]; then
  curl -fL -o "$APK" "https://f-droid.org/repo/org.documentfoundation.libreoffice_${VERSION_CODE}.apk"
fi
rm -rf app/src/main/jniLibs app/src/main/assets
mkdir -p app/src/main/jniLibs app/src/main/assets
unzip -q -o "$APK" 'lib/arm64-v8a/*' -d engine/x
unzip -q -o "$APK" 'assets/*' -d engine/x
mv engine/x/lib/arm64-v8a app/src/main/jniLibs/
cp -r engine/x/assets/. app/src/main/assets/
rm -rf engine/x
echo "engine ready"
