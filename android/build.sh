#!/bin/bash
# Veglia · Android build. Copyright (c) 2026 Evelyn & River — MIT License.
#
# Builds an UNSIGNED apk, then signs it with a DEBUG keystore it generates on
# the fly. The debug keystore/password below are throwaway demo values — fine
# for installing on your own phone, NOT for distribution. Generate your own key
# for anything you share:  keytool -genkeypair -keystore my.jks -alias veglia ...
set -e

# --- adjust these to your machine -------------------------------------------
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/android-sdk}
PLATFORM=$ANDROID_HOME/platforms/android-34/android.jar
BUILD_TOOLS=$ANDROID_HOME/build-tools/34.0.0
# ----------------------------------------------------------------------------

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SRC=$PROJECT/app/src/main
OUT=$PROJECT/build
PKG_PATH=dev/veglia/companion
LIBS=$(find $PROJECT/libs -name "*.jar" | paste -sd: -)

rm -rf $OUT
mkdir -p $OUT/gen $OUT/classes $OUT/apk $OUT/compiled_res

echo "=== Compiling resources ==="
$BUILD_TOOLS/aapt2 compile --dir $SRC/res -o $OUT/compiled_res/

echo "=== Linking resources ==="
$BUILD_TOOLS/aapt2 link \
    -o $OUT/apk/app.unsigned.apk \
    -I $PLATFORM \
    --manifest $SRC/AndroidManifest.xml \
    --java $OUT/gen \
    --auto-add-overlay \
    -R $OUT/compiled_res/*.flat

echo "=== Compiling Java ==="
find $SRC/java -name "*.java" > $OUT/sources.txt
echo "$OUT/gen/$PKG_PATH/R.java" >> $OUT/sources.txt
javac \
    -source 11 -target 11 \
    -classpath $PLATFORM:$LIBS \
    -d $OUT/classes \
    @$OUT/sources.txt

echo "=== Creating DEX ==="
$BUILD_TOOLS/d8 \
    --output $OUT/apk/ \
    --lib $PLATFORM \
    $(find $OUT/classes -name "*.class") \
    $(find $PROJECT/libs -name "*.jar")

echo "=== Building APK ==="
cd $OUT/apk
cp app.unsigned.apk app.tmp.apk
zip -d app.tmp.apk classes.dex 2>/dev/null || true
zip -j app.tmp.apk classes.dex
mv app.tmp.apk app.unsigned.apk

echo "=== Generating DEBUG signing key (throwaway; replace for release) ==="
# Kept at $PROJECT (not $OUT, which build.sh wipes every run) so re-installs
# over an existing app don't hit Android's "signatures don't match" refusal.
DEBUG_KS=$PROJECT/debug.jks
if [ ! -f $DEBUG_KS ]; then
    keytool -genkeypair -v \
        -keystore $DEBUG_KS \
        -keyalg RSA -keysize 2048 \
        -validity 10000 \
        -alias veglia \
        -storepass veglia-debug \
        -keypass veglia-debug \
        -dname "CN=Veglia Debug"
fi

echo "=== Aligning ==="
$BUILD_TOOLS/zipalign -f 4 app.unsigned.apk app.aligned.apk

echo "=== Signing (debug) ==="
$BUILD_TOOLS/apksigner sign \
    --ks $DEBUG_KS \
    --ks-pass pass:veglia-debug \
    --key-pass pass:veglia-debug \
    --ks-key-alias veglia \
    --out $PROJECT/Veglia.apk \
    app.aligned.apk

echo ""
echo "=== Done! ==="
echo "APK: $PROJECT/Veglia.apk"
ls -lh $PROJECT/Veglia.apk
