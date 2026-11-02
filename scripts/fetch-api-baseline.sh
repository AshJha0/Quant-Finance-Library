#!/usr/bin/env bash
# Download the released library jar that the api-compat profile (japicmp)
# compares against. The jar is a GitHub release asset; 2.0.0 is not on
# Maven Central.
#
#   scripts/fetch-api-baseline.sh            # version 2.3.0 (current baseline)
#   scripts/fetch-api-baseline.sh 2.1.0      # another release
#
# Then: ./mvnw -Papi-compat -DskipTests verify
set -euo pipefail

version="${1:-2.3.0}"
repo="AshJha0/Quant-Finance-Library"
dir="${HOME}/.m2/api-baseline"
jar="quant-finance-library-${version}.jar"
url="https://github.com/${repo}/releases/download/v${version}/${jar}"

mkdir -p "$dir"
if [[ -s "$dir/$jar" ]]; then
  echo "baseline present: $dir/$jar"
  exit 0
fi
echo "downloading $url"
curl -fsSL --retry 3 -o "$dir/$jar.part" "$url"
# A jar is a zip file: check the magic number before accepting it.
if [[ "$(head -c 2 "$dir/$jar.part")" != "PK" ]]; then
  echo "downloaded file is not a jar" >&2
  rm -f "$dir/$jar.part"
  exit 1
fi
mv "$dir/$jar.part" "$dir/$jar"
echo "baseline saved: $dir/$jar"
