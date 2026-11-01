#!/usr/bin/env python3
"""Mutation score per package from a PIT mutations.xml report, as a Markdown table.

    python3 scripts/pit-summary.py target/pit-reports/mutations.xml

Mutation score is killed over all mutants; test strength is killed over the
mutants that at least one test covers. These match the two figures PIT prints.
Timed-out and memory-error mutants count as killed, as in PIT.
"""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

KILLED = {"KILLED", "TIMED_OUT", "MEMORY_ERROR", "RUN_ERROR"}


def main(path: str) -> None:
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
    print("## Mutation testing (PIT)")
    print()
    print("| Package | Mutants | Killed | No coverage | Mutation score | Test strength |")
    print("|---|---:|---:|---:|---:|---:|")
    for name, (n, k, nc) in rows + [("total", tot)]:
        covered = n - nc
        score = 100.0 * k / n if n else 0.0
        strength = 100.0 * k / covered if covered else 0.0
        print(f"| {name} | {n} | {k} | {nc} | {score:.1f}% | {strength:.1f}% |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "target/pit-reports/mutations.xml")
