#!/usr/bin/env bash
# Download the released library jars that the api-compat profile (japicmp)
# compares against. They are GitHub release assets, not on Maven Central.
# Releases before 3.0.0 are one jar; from 3.0.0 there are two, the core
# library and the low-latency module, and both are fetched.
#
#   scripts/fetch-api-baseline.sh            # version 3.2.0 (current baseline)
#   scripts/fetch-api-baseline.sh 2.1.0      # another release
#
# Then: ./mvnw -Papi-compat -DskipTests verify
set -euo pipefail

version="${1:-3.2.0}"
repo="AshJha0/Quant-Finance-Library"
dir="${HOME}/.m2/api-baseline"
major="${version%%.*}"
artifacts=(quant-finance-library)
if (( major >= 3 )); then
  artifacts+=(quant-finance-library-lowlatency)
fi

mkdir -p "$dir"
for artifact in "${artifacts[@]}"; do
  jar="${artifact}-${version}.jar"
  url="https://github.com/${repo}/releases/download/v${version}/${jar}"
  if [[ -s "$dir/$jar" ]]; then
    echo "baseline present: $dir/$jar"
    continue
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
done
