#!/usr/bin/env bash
# Prueba el reconocedor de materias (Java puro) en el PC:  tests/topics.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -d "$OUT" "$ROOT/android/src/dev/synctablet/Topics.java" "$ROOT/tests/TopicsTest.java"
java -Dfile.encoding=UTF-8 -cp "$OUT" dev.synctablet.TopicsTest
