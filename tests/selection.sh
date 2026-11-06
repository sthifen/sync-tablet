#!/usr/bin/env bash
# Prueba las cuentas de mover, estirar y girar (Java puro):  tests/selection.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/dev/synctablet"
cp "$ROOT/android/src/dev/synctablet/SelectionMath.java" "$ROOT/tests/SelectionTest.java" "$OUT/src/dev/synctablet/"
if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -d "$OUT/classes" "$OUT"/src/dev/synctablet/*.java
java -cp "$OUT/classes" dev.synctablet.SelectionTest
