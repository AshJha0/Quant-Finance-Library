#!/usr/bin/env bash
# Fetch the public reference files listed in scripts/reference-files.sha256,
# check each SHA-256, unpack them and print the QFL_* variables the reference
# tests read (one NAME=value per line, ready for $GITHUB_ENV).
#
#   scripts/fetch-reference-files.sh OUT_DIR >> "$GITHUB_ENV"
#
# Fails (non-zero exit) on any download error or hash mismatch, so the nightly
# job can never run the reference tests against a missing or changed file.
# Needs curl, unzip, sha256sum and python3 with openpyxl (for the ISDA grids).
# The SIX CFI list is not redistributable and is not fetched; its test stays local.
# The CAT IM schema is fetched but not stored (its terms reserve redistribution).
set -euo pipefail

out="${1:?usage: fetch-reference-files.sh OUT_DIR}"
here="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$out/dl" "$out/isda" "$out/esma"

grep -v '^#' "$here/reference-files.sha256" | while read -r sha file url; do
  [ -z "${sha:-}" ] && continue
  curl -fsSL --retry 3 --max-time 120 -o "$out/dl/$file" "$url" >&2
  if [ "$sha" = UNPINNED ]; then
    got="$(sha256sum "$out/dl/$file" | cut -d' ' -f1)"
    echo "::warning::$file is not pinned; fetched sha256 $got. Review the file, then pin it in scripts/reference-files.sha256" >&2
    rm -f "$out/dl/$file"
    continue
  fi
  echo "$sha  $out/dl/$file" | sha256sum -c - >&2
done

for stem in USD_SOFR_20210426 USD_SOFR_20220621; do
  unzip -oq "$out/dl/$stem.zip" -d "$out/dl/$stem"
  python3 -I "$here/isda-grid-to-csv.py" "$out/dl/$stem/$stem.xlsx" "$out/isda" >&2
done
unzip -oqj "$out/dl/esma65-8-2598_annex_2.zip" -d "$out/esma"

for f in "$out/isda/USD_SOFR_20210426_grid.csv" "$out/isda/USD_SOFR_20220621_grid.csv" \
         "$out/esma/auth.016.001.01_ESMAUG_Reporting_1.1.0.xsd" \
         "$out/esma/auth.031.001.01_ESMAUG_Reporting_1.1.0.xsd" \
         "$out/esma/head.001.001.01_ESMAUG_1.0.0.xsd" "$out/esma/head.003.001.01.xsd"; do
  [ -s "$f" ] || { echo "missing after fetch: $f" >&2; exit 1; }
done

abs="$(cd "$out" && pwd)"
echo "QFL_ISDA_GRID_DIR=$abs/isda"
echo "QFL_ESMA_XSD=$abs/esma/auth.016.001.01_ESMAUG_Reporting_1.1.0.xsd"
echo "QFL_ESMA_AUTH031_XSD=$abs/esma/auth.031.001.01_ESMAUG_Reporting_1.1.0.xsd"
echo "QFL_ESMA_HEAD001_XSD=$abs/esma/head.001.001.01_ESMAUG_1.0.0.xsd"
echo "QFL_ESMA_HEAD003_XSD=$abs/esma/head.003.001.01.xsd"
# the CAT schema is only passed on once its hash is pinned (see reference-files.sha256)
[ -s "$out/dl/cat-im-schema-4.2.0.json" ] && echo "QFL_CAT_SCHEMA=$abs/dl/cat-im-schema-4.2.0.json"
echo "QFL_REQUIRE_REFERENCE_FILES=true"
