#!/usr/bin/env bash
# Run the four latency benchmarks of docs/BENCHMARKS.md three times each with
# the documented JVM flags, then print the min / median / max of every
# reported figure across the runs. The table is the raw material for the
# Results section of docs/BENCHMARKS.md.
#
#   scripts/run-benchmarks.sh                # 3 runs, all four benchmarks
#   RUNS=5 scripts/run-benchmarks.sh         # more runs
#   scripts/run-benchmarks.sh HftBookBenchmark   # one benchmark
#
# Run it on an otherwise idle machine. Raw output of every run is kept under
# target/bench-runs/ so a surprising number can be checked against the log.
set -euo pipefail

cd "$(dirname "$0")/.."
RUNS="${RUNS:-3}"
JVM="${JVM:--Xms1g -Xmx1g -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysPreTouch -XX:+PerfDisableSharedMem}"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ $# -gt 0 ]]; then
  BENCHES=("$@")
else
  BENCHES=(HftLatencyBenchmark HftOrderBenchmark HftQuoterBenchmark HftBookBenchmark)
fi

./mvnw -B -q --no-transfer-progress -DskipTests compile
out="target/bench-runs"
mkdir -p "$out"

echo "JVM flags: $JVM"
echo "Java: $("$JAVA" -version 2>&1 | head -1)"
echo "Commit: $(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
echo "Runs: $RUNS"

for b in "${BENCHES[@]}"; do
  for ((r = 1; r <= RUNS; r++)); do
    echo "running $b ($r/$RUNS)" >&2
    # shellcheck disable=SC2086
    "$JAVA" $JVM -cp target/classes "com.quantfinlib.examples.$b" > "$out/$b.$r.txt" 2>&1
  done
done

# Every number in the output becomes a metric, keyed by the shape of its line
# (the line with all numbers blanked) and its position on that line, so the
# same figure in two runs maps to the same row. Numbers that are equal in
# every run (p99, 1,024 symbols) are taken to be part of a label and skipped.
# Durations (ns, us, µs, ms, s) are converted before taking min/median/max.
PYTHON=python3
"$PYTHON" -c 1 2>/dev/null || PYTHON=python   # Windows has only "python"
"$PYTHON" - "$out" "$RUNS" "${BENCHES[@]}" <<'PY'
import re, statistics, sys
from collections import OrderedDict
out, runs, benches = sys.argv[1], int(sys.argv[2]), sys.argv[3:]
sys.stdout.reconfigure(encoding="utf-8")
NUM = re.compile(r"(-?\d[\d,]*\.?\d*)\s*(ns|us|µs|ms|s)?\b")
TO_NS = {"ns": 1, "us": 1e3, "µs": 1e3, "ms": 1e6, "s": 1e9}

def fmt_time(ns):
    for unit, f in (("ms", 1e6), ("µs", 1e3)):
        if ns >= f:
            return f"{ns / f:.2f} {unit}"
    return f"{ns:.0f} ns"

for b in benches:
    metrics = OrderedDict()
    for r in range(1, runs + 1):
        with open(f"{out}/{b}.{r}.txt", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.rstrip()
                nums = list(NUM.finditer(line))
                if not nums:
                    continue
                shape = NUM.sub("#", line).strip()
                head = shape.split(":", 1)[0] if ":" in shape else shape[:40]
                for i, m in enumerate(nums):
                    unit = m.group(2)
                    # The token holding the number names it (mean=#, p99<=#,
                    # # million ops/sec).
                    before = line[:m.start()].rsplit(" ", 1)[-1]
                    after = " ".join(line[m.end():].split()[:2]) if not before else ""
                    ctx = f"{before}# {after}".strip()
                    try:
                        v = float(m.group(1).replace(",", ""))
                    except ValueError:
                        continue
                    key = (shape, i, f"{head} [{ctx}]")
                    metrics.setdefault(key, []).append((v, unit))
    print(f"\n### {b}\n")
    print("| Measure | Min | Median | Max |")
    print("|---|---|---|---|")
    for (_, _, label), vals in metrics.items():
        if len(vals) != runs:
            continue  # a line that does not appear in every run is not a metric
        units = {u for _, u in vals}
        if units <= {None}:
            xs = [v for v, _ in vals]
            if len(set(xs)) == 1:
                continue  # constant across runs: part of a label (p99, 1,024 symbols)
            lo, med, hi = min(xs), statistics.median(xs), max(xs)
            cells = [f"{x:g}" for x in (lo, med, hi)]
        elif None not in units:
            xs = [v * TO_NS[u] for v, u in vals]
            lo, med, hi = min(xs), statistics.median(xs), max(xs)
            cells = [fmt_time(x) for x in (lo, med, hi)]
        else:
            continue
        print(f"| {label} | {cells[0]} | {cells[1]} | {cells[2]} |")
PY
