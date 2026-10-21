#!/usr/bin/env bash
# 无 Gradle 的最小构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
set -euo pipefail

cd "$(dirname "$0")"

BT="$HOME/dl-android/bt34/android-14"
AJ="$HOME/dl-android/p31/android-12/android.jar"
JDK="$HOME/command-line-tools/jbr/bin"
OUT=build
export JAVA_HOME="$HOME/command-line-tools/jbr"
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "[1/5] aapt2 compile/link"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 26 \
  --target-sdk-version 29 \
  --auto-add-overlay \
  "$OUT/res.zip"

echo "[2/5] javac"
find src -name '*.java' > "$OUT/sources.txt"
"$JDK/javac" -source 8 -target 8 -Xlint:-options \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/5] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign"
if [ ! -f debug.keystore ]; then
  "$JDK/keytool" -genkeypair -keystore debug.keystore -storepass android \
    -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=TV Zoom Debug"
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out "$OUT/tvzoom.apk" "$OUT/aligned.apk"

echo "OK -> $OUT/tvzoom.apk"
