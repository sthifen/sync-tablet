#!/usr/bin/env bash
# Prueba el reconocedor de figuras (Java puro) en el PC con trazos «a mano»
# simulados, con 60 temblores distintos:  tests/shapes.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/dev/synctablet"
cp "$ROOT/android/src/dev/synctablet/ShapeRecognizer.java" "$ROOT/android/src/dev/synctablet/ShapeTools.java" "$ROOT/tests/ShapeTest.java" "$OUT/src/dev/synctablet/"
if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -d "$OUT/classes" "$OUT"/src/dev/synctablet/*.java
fails=0
for seed in $(seq 1 60); do
  java -Dseed="$seed" -cp "$OUT/classes" dev.synctablet.ShapeTest | grep "✗" && fails=$((fails + 1)) || true
done
echo "$fails rondas con fallos de 60"
