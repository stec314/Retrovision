#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 stec314 and the Retrovision contributors
"""Regenerate the notable-device signature subset from a Fieldwatch signature export.

Usage: import_fieldwatch.py path/to/fieldwatch-signatures-v2.json
Keeps only kinds that matter for counter-surveillance; router/ISP/home fleets are noise here.
Fieldwatch is MIT (c) Off Grid Pete LLC: keep the attribution header and NOTICE.
"""
import json
import sys

OUT = "android/core/src/main/resources/dev/retrovision/core/identity/notable-signatures.tsv"
KEEP = {"HACKING", "SURVEILLANCE", "LAW_ENFORCEMENT", "GLASSES", "DRONE"}
RECORDERS = {"Bee Pendant", "Fieldy", "Friend Pendant", "Limitless Pendant", "Omi", "Plaud Note"}
RULES = {"NAME_GLOB", "NAME_CONTAINS", "OUI", "SERVICE_UUID", "MANUFACTURER_ID",
         "MANUFACTURER_DATA", "SERVICE_DATA", "VENDOR_IE_OUI", "MAC_PREFIX"}


def clean(s):
    return (s or "").replace("\t", " ").replace("\n", " ").strip()


def main(src):
    d = json.load(open(src))
    out = [
        "# Retrovision notable-device signatures.",
        "# Derived from the Fieldwatch signature catalog v%s (c) 2026 Off Grid Pete LLC, MIT License," % d["catalogVersion"],
        "# https://github.com/OffGridPete/Fieldwatch  -- subset: hacking tools, surveillance, law-enforcement",
        "# gear, smart glasses, recording pendants, drones. Regenerate with tools/notable/import_fieldwatch.py.",
        "# F<TAB>id<TAB>name<TAB>kind<TAB>note ; R<TAB>rule<TAB>radio(WIFI|BLE|*)<TAB>text<TAB>companyId<TAB>dataPrefixHex",
    ]
    for f in d["fleets"]:
        kind = "RECORDER" if f["name"] in RECORDERS else f.get("kind")
        if kind not in KEEP | {"RECORDER"} or not f.get("enabled", True):
            continue
        out.append("\t".join(["F", f["id"], f["name"], kind, clean(f.get("attentionNote") or f.get("notes"))]))
        for r in f["rules"]:
            if r.get("enabled", True) and r["kind"] in RULES:
                out.append("\t".join(["R", r["kind"], r["radio"] or "*", clean(r["text"]), str(r["companyId"]), r["dataPrefixHex"]]))
    open(OUT, "w").write("\n".join(out) + "\n")
    print("wrote", OUT)


if __name__ == "__main__":
    main(sys.argv[1])
