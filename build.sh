#!/usr/bin/env bash
# Builds dist/tabu.apk without the Android SDK or Gradle.
# Needs: JDK 17+, curl, unzip, zip. Downloads its tools once into .build-tools/.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_CODE=2
VERSION_NAME=1.1
MIN_SDK=21
TARGET_SDK=34

T=.build-tools
B=build
mkdir -p "$T"

fetch() { # url dest
  [ -s "$2" ] || { echo "Descargando $(basename "$2")"; curl -fsSL -o "$2" "$1"; }
}
# aapt2 + framework resources (bundled in Apktool), dx, android stubs, signer + zipalign.
fetch https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar "$T/apktool.jar"
fetch https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar "$T/dx.jar"
fetch https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar "$T/android.jar"
fetch https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar "$T/uber-apk-signer.jar"
if [ ! -x "$T/aapt2" ]; then
  unzip -o -q -j "$T/apktool.jar" prebuilt/linux/aapt2 prebuilt/android-framework.jar -d "$T"
  chmod +x "$T/aapt2"
fi

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex" dist

echo "== Recursos"
"$T/aapt2" compile --dir app/src/main/res -o "$B/res.zip"
"$T/aapt2" link -o "$B/base.apk" -I "$T/android-framework.jar" \
  --manifest app/src/main/AndroidManifest.xml --java "$B/gen" -A app/src/main/assets \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
  --version-code $VERSION_CODE --version-name $VERSION_NAME \
  "$B/res.zip"

echo "== Java"
javac -nowarn -Xlint:-options --release 8 -encoding UTF-8 -cp "$T/android.jar" -d "$B/classes" \
  $(find app/src/main/java "$B/gen" -name '*.java')

echo "== Dex"
java -cp "$T/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=$MIN_SDK --output="$B/dex/classes.dex" "$B/classes"

echo "== Empaquetar y firmar"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -X ../unsigned.apk classes.dex)
java -jar "$T/uber-apk-signer.jar" --apks "$B/unsigned.apk" -o "$B/signed" \
  --ks keystore/tabu.p12 --ksAlias tabu --ksPass tabu-android --ksKeyPass tabu-android >"$B/sign.log" \
  || { cat "$B/sign.log"; exit 1; }
cp "$B/signed/unsigned-aligned-signed.apk" dist/tabu.apk

echo "OK -> dist/tabu.apk ($(du -h dist/tabu.apk | cut -f1))"
