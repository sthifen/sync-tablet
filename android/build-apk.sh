#!/usr/bin/env bash
# Compila la app Android sin Gradle ni Android Studio (como Encaje):
#   aapt2 (recursos) → javac → d8 (dex) → zipalign → apksigner (clave propia si existe; si no, la de depuración).
#
# Si faltan, descarga build-tools y la plataforma en $ANDROID_HOME
# (por defecto ~/Android/Sdk). Su uso está sujeto a la licencia del Android SDK:
# https://developer.android.com/studio/terms
#
# La librería nativa (native/) necesita rustup, cargo-ndk y el NDK; con
# --no-native, o si faltan, la app sale sin ella y usa lo que trae Android.
#
# Uso:  android/build-apk.sh [--install] [--no-native] [--prueba]
#   --prueba: otra app (dev.synctablet.prueba) firmada con la clave de depuración,
#   para probar al lado de la de verdad sin tocar sus apuntes.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT_VER=36.1.0
BT_ZIP=build-tools_r36.1_linux.zip
PLATFORM=android-36
PLATFORM_ZIP=platform-36_r02.zip
REPO=https://dl.google.com/android/repository
MIN_SDK=26
# La versión de la app es la del programa (Cargo.toml): 0.1.0 → código 100.
VERSION="$(grep -m1 '^version' "$HERE/../Cargo.toml" | cut -d'"' -f2)"
IFS=. read -r V_MAJOR V_MINOR V_PATCH <<<"$VERSION"
VERSION_CODE=$((V_MAJOR * 10000 + V_MINOR * 100 + V_PATCH))
BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
OUT="$HERE/build"
APK="$HERE/synctablet.apk"
PRUEBA=()
if [[ " $* " == *" --prueba "* ]]; then
  APK="$HERE/synctablet-prueba.apk"
  PRUEBA=(--rename-manifest-package dev.synctablet.prueba)
fi

fetch() { # fetch <zip> <destino>
  local tmp; tmp="$(mktemp -d)"
  echo "→ descargando $1"
  curl -fL --progress-bar "$REPO/$1" -o "$tmp/pkg.zip"
  unzip -q "$tmp/pkg.zip" -d "$tmp/x"
  mkdir -p "$(dirname "$2")"
  mv "$tmp/x/"* "$2"
  rm -rf "$tmp"
}
[[ -x "$BT/aapt2" ]] || fetch "$BT_ZIP" "$BT"
[[ -f "$ANDROID_JAR" ]] || fetch "$PLATFORM_ZIP" "$SDK/platforms/$PLATFORM"

if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi

rm -rf "$OUT" && mkdir -p "$OUT"/{gen,classes,dex}

echo "→ recursos"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version $MIN_SDK --target-sdk-version 36 \
  --version-code "$VERSION_CODE" --version-name "$VERSION" "${PRUEBA[@]}" "$OUT/res.zip"

echo "→ java"
"${JAVAC[@]}" -source 17 -target 17 -nowarn -Xlint:-options -encoding UTF-8 \
  -classpath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find "$HERE/src" "$OUT/gen" -name '*.java')

echo "→ dex"
"$BT/d8" --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q "$OUT/base.apk" classes.dex)

NDK="$SDK/ndk/r28c"
if [[ " $* " != *" --no-native "* && -x "$HOME/.cargo/bin/cargo-ndk" && -d "$NDK" ]]; then
  echo "→ librería nativa"
  PATH="$HOME/.cargo/bin:$PATH" ANDROID_NDK_HOME="$NDK" cargo ndk -t arm64-v8a --platform $MIN_SDK \
    --manifest-path "$HERE/../native/Cargo.toml" build --release --lib
  mkdir -p "$OUT/lib/arm64-v8a"
  cp "$HERE/../native/target/aarch64-linux-android/release/libsynctablet.so" "$OUT/lib/arm64-v8a/"
  # Sin comprimir: Android la usa directamente desde el APK.
  (cd "$OUT" && zip -q -0 base.apk lib/arm64-v8a/libsynctablet.so)
else
  echo "→ sin librería nativa"
fi

echo "→ firma"
KEYSTORE="$HERE/debug.keystore"
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Sync Tablet Debug" >/dev/null 2>&1
fi
"$BT/zipalign" -P 16 -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
# Con la clave propia (fuera del repo) y su enlace con la de depuración, las
# tablets se actualizan sin desinstalar. Sin ellas, cualquiera puede compilar
# con la de depuración, pero ese APK no actualiza una instalación ya rotada.
RELEASE_KS="${SYNCTABLET_KEYSTORE:-$HOME/.synctablet/release.jks}"
LINEAGE="${SYNCTABLET_LINEAGE:-$HOME/.synctablet/lineage}"
if [[ -f "$RELEASE_KS" && -f "$LINEAGE" && ${#PRUEBA[@]} -eq 0 ]]; then
  echo "  clave propia: $RELEASE_KS"
  PASS=()
  [[ -n "${SYNCTABLET_KS_PASS:-}" ]] && PASS=(--ks-pass env:SYNCTABLET_KS_PASS)
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --next-signer --ks "$RELEASE_KS" "${PASS[@]}" \
    --lineage "$LINEAGE" --rotation-min-sdk-version 28 --out "$APK" "$OUT/aligned.apk"
else
  echo "  clave de depuración"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"
fi
"$BT/apksigner" verify "$APK"
echo "✓ $APK ($(du -h "$APK" | cut -f1))"

if [[ " $* " == *" --install "* ]]; then
  adb install -r "$APK"
  if [[ ${#PRUEBA[@]} -gt 0 ]]; then adb shell am start -n dev.synctablet.prueba/dev.synctablet.MainActivity
  else adb shell am start -n dev.synctablet/.MainActivity; fi
fi
