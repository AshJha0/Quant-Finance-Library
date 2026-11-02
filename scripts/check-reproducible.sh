#!/usr/bin/env bash
# Reproducible-build check: build the jars twice from clean and compare SHA-256.
# Relies on project.build.outputTimestamp in pom.xml (fixed entry timestamps and
# order). Exits non-zero and prints both hash lists if any jar differs.
set -euo pipefail
cd "$(dirname "$0")/.."
out="${TMPDIR:-/tmp}/qfl-repro.$$"
mkdir -p "$out"
build() {
  ./mvnw -B -q --no-transfer-progress clean package -DskipTests -Djacoco.skip=true "$@"
  find core/target lowlatency/target -maxdepth 1 -name '*.jar' -print0 | sort -z \
    | xargs -0 sha256sum
}
build "$@" > "$out/first.txt"
build "$@" > "$out/second.txt"
cat "$out/first.txt"
if diff -u "$out/first.txt" "$out/second.txt"; then
  echo "Reproducible: $(wc -l < "$out/first.txt") jars identical across two clean builds."
else
  echo "::error::Jars differ between two clean builds (see diff above)." >&2
  exit 1
fi
