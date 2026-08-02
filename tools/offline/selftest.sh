#!/usr/bin/env bash
# SNSocial self-test: compiles CoreSelfTest against the offline-built core classes and
# runs it. No network, no server, no JUnit - a JDK and bash are the whole toolchain,
# both present on the developer's Git-for-Windows box and on any Linux CI runner.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
CLASSES="build/offline/classes"
TEST_OUT="build/selftest/classes"

if [[ ! -d "$CLASSES" ]]; then
  echo "build first: tools/offline/build-offline.sh" >&2
  exit 1
fi

# javac/java are native Windows binaries under Git Bash: the classpath separator is the
# OS's, not the shell's.
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

rm -rf "$TEST_OUT"
mkdir -p "$TEST_OUT"

echo "==> compile self-test"
javac -Xlint:all -encoding UTF-8 --release 17 \
      -cp "$CLASSES" -d "$TEST_OUT" tools/offline/selftest-src/CoreSelfTest.java

echo "==> layering invariant: core/ must not know Bukkit exists"
if grep -rn "import org.bukkit\|import io.papermc\|import net.kyori\|import me.clip" \
        src/main/java/network/somikyy/snsocial/core; then
  echo "FAILED: core/ imports server classes" >&2
  exit 1
fi
echo "    core is clean"

echo "==> run assertions"
java -cp "$CLASSES$SEP$TEST_OUT" CoreSelfTest

echo "==> API surface of the offline build (org.bukkit refs)"
bash tools/offline/api-surface.sh "$CLASSES" > build/selftest/api-surface.txt
if [[ -f tools/offline/bukkit-api-surface.txt ]]; then
  if ! diff -u tools/offline/bukkit-api-surface.txt build/selftest/api-surface.txt; then
    echo "FAILED: the org.bukkit references changed. If the change is intended, re-record:" >&2
    echo "  bash tools/offline/api-surface.sh build/offline/classes > tools/offline/bukkit-api-surface.txt" >&2
    exit 1
  fi
  echo "    matches the recorded surface"
else
  echo "    (no recorded surface yet - record with:"
  echo "     bash tools/offline/api-surface.sh build/offline/classes > tools/offline/bukkit-api-surface.txt)"
fi

echo
echo "SELF-TEST OK"
