#!/usr/bin/env bash
# Sync Tablet para el escritorio (Linux): la app de la tablet tal cual, sobre
# una capa que imita la API de Android con Java2D/Swing (sin webview).
#
#   desktop/build.sh            → desktop/build/synctablet-escritorio.jar y la librería nativa
#   desktop/build.sh --install  → además, la añade al menú de aplicaciones
#   desktop/build.sh --no-native → sin recompilar la librería en Rust
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
OUT="$HERE/build"
VERSION="$(grep -m1 '^version' "$ROOT/Cargo.toml" | cut -d'"' -f2)"

if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
if command -v jar >/dev/null; then JAR=(jar); else JAR=(java -m jdk.jartool/sun.tools.jar.Main); fi

rm -rf "$OUT/classes" "$OUT/gen"
mkdir -p "$OUT/classes/res" "$OUT/gen/dev/synctablet" "$OUT/lib"

echo "→ recursos"
python3 - "$ROOT/android/res/values/strings.xml" "$OUT/gen/dev/synctablet/R.java" <<'PY'
import sys, re, xml.etree.ElementTree as ET
src, dst = sys.argv[1], sys.argv[2]
names = [(s.get('name'), (s.text or '')) for s in ET.parse(src).getroot().findall('string')]
def lit(t):
    return '"' + t.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') + '"'
lines = ['package dev.synctablet;', '', '/** Lo que genera aapt en Android: los identificadores de los recursos. */', 'public final class R {',
         '    public static final class drawable { public static final int ic_launcher = 0x7f010000; private drawable() {} }',
         '    public static final class string {']
for i, (n, _) in enumerate(names):
    lines.append('        public static final int %s = 0x%x;' % (n, 0x7f020000 + i))
lines.append('        private string() {}')
lines.append('    }')
lines.append('    public static void register() {')
for i, (n, t) in enumerate(names):
    lines.append('        android.content.res.Resources.registerString(string.%s, %s);' % (n, lit(t)))
lines.append('    }')
lines.append('    private R() {}')
lines.append('}')
open(dst, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
PY
if command -v magick >/dev/null; then
  magick -background none -density 384 "$ROOT/android/icon.svg" -resize 256x256 "$OUT/classes/res/icon.png" 2>/dev/null || true
elif command -v rsvg-convert >/dev/null; then
  rsvg-convert -w 256 -h 256 "$ROOT/android/icon.svg" -o "$OUT/classes/res/icon.png" || true
fi

echo "→ javac"
find "$HERE/src" "$HERE/launcher" "$OUT/gen" "$ROOT/android/src" -name '*.java' > "$OUT/sources.txt"
"${JAVAC[@]}" -encoding UTF-8 -nowarn -Xlint:none -Xmaxerrs 2000 -d "$OUT/classes" @"$OUT/sources.txt"

echo "→ jar"
printf 'Main-Class: dev.synctablet.desktop.Main\nImplementation-Version: %s\n' "$VERSION" > "$OUT/manifest.txt"
"${JAR[@]}" --create --file "$OUT/synctablet-escritorio.jar" --manifest "$OUT/manifest.txt" -C "$OUT/classes" .

if [[ " $* " != *" --no-native "* ]]; then
  echo "→ librería nativa (Rust)"
  (cd "$ROOT/native" && cargo build --release --quiet)
fi
if [[ -f "$ROOT/native/target/release/libsynctablet.so" ]]; then
  cp "$ROOT/native/target/release/libsynctablet.so" "$OUT/lib/"
else
  echo "  (sin librería nativa: PDF, hojas de cálculo y Office no estarán disponibles)"
fi

# El servidor (src/) va con la app: ella lo arranca sola, sin abrir localhost.
echo "→ servidor (Rust)"
(cd "$ROOT" && cargo build --release --quiet)
cp "$ROOT/target/release/synctablet" "$OUT/lib/synctablet-servidor"

cat > "$OUT/synctablet-escritorio" <<SH
#!/usr/bin/env bash
DIR="\$(cd "\$(dirname "\$(readlink -f "\$0")")" && pwd)"
exec java -Dsun.java2d.uiScale=1 -Dsynctablet.version=$VERSION -Djava.library.path="\$DIR/lib" -Dsynctablet.server="\$DIR/lib/synctablet-servidor" -Xss4m -Xmx3g \\
  --enable-native-access=ALL-UNNAMED -jar "\$DIR/synctablet-escritorio.jar" "\$@"
SH
chmod +x "$OUT/synctablet-escritorio"

if [[ " $* " == *" --install "* ]]; then
  APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
  ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor/256x256/apps"
  mkdir -p "$APPS" "$ICONS"
  [[ -f "$OUT/classes/res/icon.png" ]] && cp "$OUT/classes/res/icon.png" "$ICONS/synctablet.png"
  cat > "$APPS/synctablet-escritorio.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Sync Tablet
Comment=Apuntes a mano, notas, documentos y hojas de cálculo
Exec=$OUT/synctablet-escritorio
Icon=synctablet
Terminal=false
Categories=Office;Education;
StartupWMClass=dev-synctablet-desktop-Main
DESK
  echo "→ instalada en el menú de aplicaciones"
fi
echo "listo: $OUT/synctablet-escritorio"
