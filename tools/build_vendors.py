#!/usr/bin/env python3
"""Builds the compact vendor tables bundled in the APK.

  build_vendors.py oui OUT.tsv oui.csv [mam.csv oui36.csv ...]   # IEEE registry CSVs
  build_vendors.py ble OUT.tsv company_ids.json                  # Nordic bluetooth-numbers-database

Run by CI at build time so the registry data is never committed.
"""
import csv
import json
import sys


def clean(s: str) -> str:
    return " ".join(s.replace("\t", " ").split())


def oui(out, files):
    rows = {}
    for f in files:
        with open(f, newline="", encoding="utf-8", errors="replace") as fh:
            for r in csv.DictReader(fh):
                a = (r.get("Assignment") or "").strip().upper()
                n = clean(r.get("Organization Name") or "")
                if len(a) in (6, 7, 9) and all(c in "0123456789ABCDEF" for c in a) and n:
                    rows[a] = n
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        for a in sorted(rows):
            fh.write(f"{a}\t{rows[a]}\n")
    print(f"{len(rows)} OUI entries", file=sys.stderr)


def ble(out, src):
    data = json.load(open(src, encoding="utf-8"))
    n = 0
    with open(out, "w", encoding="utf-8", newline="\n") as fh:
        for e in data:
            name = clean(str(e.get("name", "")))
            if isinstance(e.get("code"), int) and name:
                fh.write(f"{e['code']}\t{name}\n")
                n += 1
    print(f"{n} BLE company ids", file=sys.stderr)


if __name__ == "__main__":
    if len(sys.argv) < 4 or sys.argv[1] not in ("oui", "ble"):
        sys.exit(__doc__)
    (oui if sys.argv[1] == "oui" else ble)(sys.argv[2], sys.argv[3:] if sys.argv[1] == "oui" else sys.argv[3])
