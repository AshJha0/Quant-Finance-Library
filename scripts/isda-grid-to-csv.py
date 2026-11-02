#!/usr/bin/env python3
"""Convert an ISDA CDS Standard Model RFR test grid (.xlsx from cdsmodel.com)
into two CSV files that IsdaGridVerificationTest reads.

The grid files are copyright ISDA / IHS Markit ("All rights reserved"), so
neither the workbook nor the CSVs belong in this repository. Write the CSVs
to a folder outside the repo and point QFL_ISDA_GRID_DIR at it.

Usage (run with python -I; needs openpyxl):
    python -I scripts/isda-grid-to-csv.py GRID.xlsx OUT_DIR

Writes OUT_DIR/<stem>_grid.csv (one row per test case: inputs and expected
outputs) and OUT_DIR/<stem>_curve.csv (instrument type, tenor, rate).
"""
import csv
import datetime
import os
import sys

import openpyxl

GRID_COLS = ["tradeDate", "maturityDate", "currency", "couponBp", "quotedSpreadBp",
             "recovery", "cleanUpfront", "cashSettlementAmount", "cleanPrice",
             "daysAccrued", "accruedPremium", "cashSettleDate", "startDate"]


def iso(v):
    if isinstance(v, datetime.datetime):
        return v.date().isoformat()
    if isinstance(v, int) and 19000101 <= v <= 21001231:   # 2021 grid stores yyyymmdd ints
        s = str(v)
        return f"{s[:4]}-{s[4:6]}-{s[6:]}"
    raise ValueError(f"not a date: {v!r}")


def main(path, out_dir):
    stem = os.path.splitext(os.path.basename(path))[0]
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
    grid, curve = wb.worksheets[0], wb.worksheets[1]
    n = 0
    with open(os.path.join(out_dir, stem + "_grid.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(GRID_COLS)
        for i, r in enumerate(grid.iter_rows(values_only=True)):
            if i == 0 or r is None or r[0] is None:
                continue
            r = list(r[:13])
            out = [iso(r[0]), iso(r[1]), str(r[2]).strip()]
            out += [repr(float(x)) for x in r[3:11]]
            out += [iso(r[11]), iso(r[12])]
            w.writerow(out)
            n += 1
    m = 0
    with open(os.path.join(out_dir, stem + "_curve.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["type", "tenor", "rate"])
        for r in curve.iter_rows(values_only=True):
            if r and r[0] in ("S", "M") and r[1] and isinstance(r[2], (int, float)):
                w.writerow([r[0], str(r[1]).strip(), repr(float(r[2]))])
                m += 1
    print(f"{stem}: {n} grid rows, {m} curve instruments")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
