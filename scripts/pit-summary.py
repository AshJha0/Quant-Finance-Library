#!/usr/bin/env python3
"""Mutation score per package from a PIT mutations.xml report, as a Markdown table.

    python3 scripts/pit-summary.py target/pit-reports/mutations.xml
    python3 scripts/pit-summary.py REPORT.xml --thresholds scripts/pit-thresholds.txt --run core

Mutation score is killed over all mutants; test strength is killed over the
mutants that at least one test covers. These match the two figures PIT prints.
Timed-out and memory-error mutants count as killed, as in PIT.

With --thresholds and --run, every package listed for that run in the
thresholds file must reach its floor, and must be in the report at all (a
package whose mutants vanished counts as a drop). The exit code is 1 on any
drop, so the nightly job fails.
"""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

KILLED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def read_thresholds(path, run):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                r, pkg, floor = line.split()
                if r == run:
                    out[pkg] = float(floor)
    if not out:
        raise SystemExit(f"no thresholds for run '{run}' in {path}")
    return out


def main(path, thresholds=None, run=None):
    stats = defaultdict(lambda: [0, 0, 0])  # total, killed, no_coverage
    for m in ET.parse(path).getroot().iter("mutation"):
        cls = m.findtext("mutatedClass", "")
        pkg = cls.rsplit(".", 1)[0]
        status = m.get("status", "")
        s = stats[pkg]
        s[0] += 1
        if status in KILLED:
            s[1] += 1
        elif status == "NO_COVERAGE":
            s[2] += 1
    rows = sorted(stats.items())
    tot = [sum(v[i] for _, v in rows) for i in range(3)]
    print("## Mutation testing (PIT)" + (f": {run}" if run else ""))
    print()
    print("| Package | Mutants | Killed | No coverage | Mutation score | Test strength |")
    print("|---|---:|---:|---:|---:|---:|")
    for name, (n, k, nc) in rows + [("total", tot)]:
        covered = n - nc
        score = 100.0 * k / n if n else 0.0
        strength = 100.0 * k / covered if covered else 0.0
        print(f"| {name} | {n} | {k} | {nc} | {score:.1f}% | {strength:.1f}% |")
    if not thresholds:
        return 0
    failed = []
    for pkg, floor in sorted(read_thresholds(thresholds, run).items()):
        if pkg not in stats:
            failed.append(f"{pkg}: no mutants in the report (floor {floor:.0f}%)")
            continue
        n, k, _ = stats[pkg]
        score = 100.0 * k / n if n else 0.0
        if score < floor:
            failed.append(f"{pkg}: {score:.1f}% is below the floor of {floor:.0f}%")
    print()
    if failed:
        print("**Mutation score dropped:**")
        for f in failed:
            print(f"- {f}")
        return 1
    print("Every package is at or above its floor.")
    return 0


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {}
    for key in ("--thresholds", "--run"):
        if key in args:
            i = args.index(key)
            opts[key] = args[i + 1]
            del args[i:i + 2]
    if ("--thresholds" in opts) != ("--run" in opts):
        raise SystemExit("--thresholds and --run go together")
    report = args[0] if args else "target/pit-reports/mutations.xml"
    sys.exit(main(report, opts.get("--thresholds"), opts.get("--run")))
