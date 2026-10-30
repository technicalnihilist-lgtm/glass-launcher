#!/usr/bin/env bash
# Builds a signed, installable Glass Launcher APK with the Android SDK build-tools
# (no Android Studio / Gradle required).
set -e
ulimit -v unlimited 2>/dev/null || true
SDK="${ANDROID_HOME:?set ANDROID_HOME}"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
OUT=build
rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/compiled/res.zip"

"$BT/aapt2" link -o "$OUT/base.apk" -I "$PLAT" \
  --manifest app/src/main/AndroidManifest.xml -R "$OUT/compiled/res.zip" \
  -A app/src/main/assets --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code 7 --version-name "2.5" \
  --auto-add-overlay

javac -nowarn -Xlint:-options -source 11 -target 11 -classpath "$PLAT" \
  -d "$OUT/classes" $(find app/src/main/java "$OUT/gen" -name '*.java')

"$BT/d8" --release --min-api 24 --lib "$PLAT" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -storepass glasslauncher -keypass glasslauncher \
    -alias glass -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Glass Launcher, O=Glass, C=IN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks keystore.jks --ks-pass pass:glasslauncher --key-pass pass:glasslauncher \
  --ks-key-alias glass --v1-signing-enabled true --v2-signing-enabled true \
  --out GlassLauncher.apk "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs GlassLauncher.apk | head -4
echo "OK -> $(pwd)/GlassLauncher.apk  ($(du -h GlassLauncher.apk | cut -f1))"
