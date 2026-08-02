#!/usr/bin/env bash
# Builds SNSocial without touching the network.
#
# The normal build is Gradle against the real paper-api. This script exists for the case
# where Maven Central or repo.papermc.io is unreachable - which, for a Russian-hosted box,
# is a Tuesday. It compiles against the tiny compile-only stubs in ./bukkit-stubs, which
# are NOT included in the resulting jar: the server supplies the real classes.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Everything below runs from the project root and uses relative paths on purpose:
# javac is a native Windows binary under Git Bash and cannot resolve MSYS-style
# /d/GitHub/... lines inside an @argfile. Relative paths work identically everywhere.
cd "$ROOT"
OUT="build/offline"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/stubs" "$OUT/jar"

echo "==> stubs"
find tools/offline/bukkit-stubs -name '*.java' > "$OUT/stub-sources.txt"
javac -nowarn -encoding UTF-8 --release 17 -d "$OUT/stubs" "@$OUT/stub-sources.txt"

echo "==> sources"
find src/main/java -name '*.java' > "$OUT/sources.txt"
javac -Xlint:all -encoding UTF-8 --release 17 -cp "$OUT/stubs" -d "$OUT/classes" "@$OUT/sources.txt"

echo "==> resources"
cp -r src/main/resources/. "$OUT/classes/"
# Gradle expands ${version} in plugin.yml via processResources; do the same here so the
# offline jar is not subtly different from the released one.
VERSION="$(grep -oP 'VERSION = "\K[^"]+' src/main/java/network/somikyy/snsocial/core/Version.java)"
sed -i "s/\${version}/$VERSION/g" "$OUT/classes/plugin.yml"
echo "    version $VERSION"

echo "==> jar"
cat > "$OUT/manifest.txt" <<EOF
Implementation-Title: SNSocial
Implementation-Version: $VERSION
Implementation-Vendor: Somikyy Network
EOF
jar --create \
    --file "$OUT/jar/SNSocial-$VERSION.jar" \
    --manifest "$OUT/manifest.txt" \
    -C "$OUT/classes" .

# Stable name for scripts that do not want to know the version number.
cp "$OUT/jar/SNSocial-$VERSION.jar" "$OUT/jar/SNSocial-offline.jar"

echo "OK: $ROOT/$OUT/jar/SNSocial-$VERSION.jar"
