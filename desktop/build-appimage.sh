#!/usr/bin/env bash
# El AppImage de Sync Tablet para el escritorio, con su propio Java dentro
# (jlink): desktop/build/synctablet-escritorio-<versión>-x86_64.AppImage.
# Se sube al release junto al APK; la app se actualiza sola desde ahí (Updater).
#
# Necesita appimagetool (https://github.com/AppImage/appimagetool) en el PATH o
# en ~/.cache/synctablet/appimagetool-x86_64.AppImage. Con --appdir se queda en
# la carpeta desktop/build/AppDir (se puede probar con AppDir/AppRun).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
OUT="$HERE/build"
VERSION="$(grep -m1 '^version' "$ROOT/Cargo.toml" | cut -d'"' -f2)"
NAME="synctablet-escritorio-$VERSION-x86_64.AppImage"
APPDIR="$OUT/AppDir"
ONLY_APPDIR=0
ARGS=()
for a in "$@"; do [[ "$a" == --appdir ]] && ONLY_APPDIR=1 || ARGS+=("$a"); done

"$HERE/build.sh" "${ARGS[@]}"

echo "→ Java recortado (jlink)"
if command -v jdeps >/dev/null; then JDEPS=(jdeps); else JDEPS=(java -m jdk.jdeps/com.sun.tools.jdeps.Main); fi
if command -v jlink >/dev/null; then JLINK=(jlink); else JLINK=(java -m jdk.jlink/jdk.tools.jlink.internal.Main); fi
MODULES="$("${JDEPS[@]}" --print-module-deps --ignore-missing-deps "$OUT/synctablet-escritorio.jar")"
# Los que se cargan por servicio o reflexión no los ve jdeps.
MODULES="$MODULES,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/lib/synctablet"
if ! "${JLINK[@]}" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --output "$APPDIR/usr/lib/jvm" 2>"$OUT/jlink.log"; then
  # Fedora cambia java.security (crypto-policies) y jlink se niega: se copia el Java entero.
  echo "  jlink no pudo ($(head -1 "$OUT/jlink.log")); se copia el Java del sistema"
  JHOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  rm -rf "$APPDIR/usr/lib/jvm"
  mkdir -p "$APPDIR/usr/lib/jvm"
  for d in bin lib conf release legal; do [[ -e "$JHOME/$d" ]] && cp -rL "$JHOME/$d" "$APPDIR/usr/lib/jvm/"; done
  rm -rf "$APPDIR/usr/lib/jvm/lib/src.zip" "$APPDIR/usr/lib/jvm/lib/ct.sym" "$APPDIR/usr/lib/jvm/demo"
fi

cp "$OUT/synctablet-escritorio.jar" "$APPDIR/usr/lib/synctablet/"
[[ -f "$OUT/lib/libsynctablet.so" ]] && cp "$OUT/lib/libsynctablet.so" "$APPDIR/usr/lib/synctablet/"
cp "$OUT/lib/synctablet-servidor" "$APPDIR/usr/lib/synctablet/"
cp "$OUT/classes/res/icon.png" "$APPDIR/synctablet.png"
cp "$OUT/classes/res/icon.png" "$APPDIR/.DirIcon"
cat > "$APPDIR/synctablet.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Sync Tablet
Comment=Apuntes a mano, notas, documentos y hojas de cálculo
Exec=AppRun
Icon=synctablet
Terminal=false
Categories=Office;Education;
StartupWMClass=dev-synctablet-desktop-Main
X-AppImage-Version=$VERSION
DESK
cat > "$APPDIR/AppRun" <<RUN
#!/bin/sh
HERE="\$(dirname "\$(readlink -f "\$0")")"
exec "\$HERE/usr/lib/jvm/bin/java" -Dsun.java2d.uiScale=1 -Dsynctablet.version=$VERSION -Dsynctablet.server="\$HERE/usr/lib/synctablet/synctablet-servidor" \\
  -Djava.library.path="\$HERE/usr/lib/synctablet" -Xss4m -Xmx3g --enable-native-access=ALL-UNNAMED \\
  -jar "\$HERE/usr/lib/synctablet/synctablet-escritorio.jar" "\$@"
RUN
chmod +x "$APPDIR/AppRun"
[[ $ONLY_APPDIR == 1 ]] && { echo "listo: $APPDIR"; exit 0; }

echo "→ AppImage"
TOOL="$(command -v appimagetool || true)"
[[ -z "$TOOL" && -x "$HOME/.cache/synctablet/appimagetool-x86_64.AppImage" ]] && TOOL="$HOME/.cache/synctablet/appimagetool-x86_64.AppImage"
if [[ -z "$TOOL" ]]; then
  echo "falta appimagetool: bájalo de https://github.com/AppImage/appimagetool/releases a ~/.cache/synctablet/appimagetool-x86_64.AppImage (chmod +x)" >&2
  exit 1
fi
rm -f "$OUT/$NAME"
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$TOOL" --no-appstream "$APPDIR" "$OUT/$NAME" >/dev/null
(cd "$OUT" && sha256sum "$NAME" > "$NAME.sha256")
echo "listo: $OUT/$NAME"
echo "  al publicar, añade su línea a SHA256SUMS: $(cat "$OUT/$NAME.sha256")"
