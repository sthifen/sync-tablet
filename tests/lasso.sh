#!/usr/bin/env bash
# Prueba qué agarra el lazo (Java puro) en el PC:  tests/lasso.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src/dev/synctablet"
SRC="$ROOT/android/src/dev/synctablet"
cp "$SRC/LassoPicker.java" "$SRC/ShapeRecognizer.java" "$SRC/ShapeTools.java" "$ROOT/tests/LassoTest.java" "$OUT/src/dev/synctablet/"
if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -d "$OUT/classes" "$OUT"/src/dev/synctablet/*.java
java -cp "$OUT/classes" dev.synctablet.LassoTest
