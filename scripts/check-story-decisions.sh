#!/usr/bin/env bash
set -euo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
compiler=(javac)
target=(--release 21)
if ! command -v javac >/dev/null 2>&1; then
  compiler=(java -m jdk.compiler/com.sun.tools.javac.Main)
  # Some development images include the compiler module but omit javac and ct.sym.
  # Only use the system modules fallback on the actual target Java 21 runtime.
  java -XshowSettings:properties -version 2>&1 | grep -q "java.specification.version = 21$"
  target=(-source 21 -target 21)
fi
"${compiler[@]}" "${target[@]}" -Xlint:all -Werror -d "$build" \
  "$root/src/main/java/com/minecolonies/api/story/StoryDecisionLedger.java" \
  "$root/src/testStandalone/java/com/minecolonies/api/story/StoryDecisionLedgerTest.java"
java -cp "$build" com.minecolonies.api.story.StoryDecisionLedgerTest
