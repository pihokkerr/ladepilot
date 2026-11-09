#!/usr/bin/env python3
"""Countries without an open national feed (e.g. Croatia): Open Charge Map -> out/<cc>.json.

Open Charge Map (openchargemap.org) is a community database (CC BY 4.0 for most data).
It has locations, power and connectors, but no live status and only free-text prices.
Prices: parsed from the free text where possible, otherwise the public list price of the
big networks (LIST_PRICES) – both flagged as estimate (flag 2).

Usage: OCM_API_KEY=... python pipeline/ocm.py --country HR --out out/hr.json
"""
import argparse
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from build import plug_code, r2, write  # noqa: E402

API = "https://api.openchargemap.io/v3"

# public ad-hoc list prices per network: (operator regex, [(max_kw, eur_per_kwh), ...], source)
LIST_PRICES = {
    "HR": [
        # HEP ELEN from 1 Apr 2025 (outside motorways: ≤22 kW 0.35, ≤50 kW 0.45; motorway fast chargers 0.57)
        (r"\bELEN\b|HEP", [(22.1, 0.35), (50, 0.45), (9999, 0.57)]),
        (r"IONITY", [(9999, 0.69)]),
    ],
}

PLUG_IDS = {33: "T2COMBO", 32: "T1COMBO", 2: "CHADEMO", 25: "T2", 1036: "T2", 1: "T1", 28: "SCHUKO", 27: "TESLA", 30: "TESLA"}


def get(path, **params):
    params["key"] = os.environ["OCM_API_KEY"]
    url = f"{API}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": "Ladepilot/1.0 (personal, non-commercial)", "X-API-Key": params["key"]})
    with urllib.request.urlopen(req, timeout=180) as r:
        return json.loads(r.read())


def parse_cost(text):
    """'0,45 €/kWh' -> 0.45 (only clear per-kWh euro prices)."""
    if not text:
        return None
    m = re.search(r"(\d+[.,]\d+)\s*(?:€|eur|euro)\s*/\s*kwh", text, re.I) or re.search(r"(?:€|eur)\s*(\d+[.,]\d+)\s*/\s*kwh", text, re.I)
    if not m:
        return None
    v = float(m.group(1).replace(",", "."))
    return v if 0.05 <= v <= 1.5 else None


def list_price(cc, op, kw):
    for pat, steps in LIST_PRICES.get(cc, []):
        if re.search(pat, op, re.I):
            for max_kw, eur in steps:
                if kw <= max_kw:
                    return eur
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--country", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    cc = args.country.upper()
    if not os.environ.get("OCM_API_KEY"):
        print("OCM_API_KEY is not set – skipping", file=sys.stderr)
        sys.exit(0)

    ref = get("referencedata")
    op_names = {o["ID"]: o["Title"] for o in ref.get("Operators") or []}
    pois = get("poi", countrycode=cc, maxresults=20000, compact="true", verbose="false")
    print(f"  OCM {cc}: {len(pois)} POIs", file=sys.stderr)

    ops, op_index, sites = [], {}, []
    for p in pois:
        a = p.get("AddressInfo") or {}
        lat, lon = a.get("Latitude"), a.get("Longitude")
        if lat is None or lon is None:
            continue
        if (p.get("StatusTypeID") in (100, 150, 200, 210)):   # not operational / planned / removed
            continue
        if p.get("UsageTypeID") in (2, 6):                       # private / members only
            continue
        op = op_names.get(p.get("OperatorID")) or "?"
        if op.lower().startswith("(unknown") or op == "?":
            op = (a.get("Title") or "Ismeretlen").split(" - ")[0][:40]
        text_price = parse_cost(p.get("UsageCost"))
        points = []
        for c in p.get("Connections") or []:
            if c.get("StatusTypeID") in (100, 150, 200, 210):
                continue
            kw = round(c.get("PowerKW") or 0)
            plugs = plug_code([PLUG_IDS.get(c.get("ConnectionTypeID"), "")])
            eur = text_price or list_price(cc, op, kw)
            for _ in range(max(1, min(int(c.get("Quantity") or 1), 12))):
                points.append([kw, plugs, r2(eur), None, 0] + ([2] if eur is not None else []))
        if not points:
            continue
        if op not in op_index:
            op_index[op] = len(ops)
            ops.append(op)
        addr = ", ".join(x for x in [a.get("AddressLine1") or "", f"{a.get('Postcode') or ''} {a.get('Town') or ''}".strip()] if x)
        name = a.get("Title") or op
        sites.append([f"{cc}-OCM-{p.get('ID')}", name, op_index[op], round(lat, 5), round(lon, 5), addr, cc, points, None])
    priced = sum(1 for s in sites if any(pt[2] is not None for pt in s[7]))
    print(f"  sites: {len(sites)}, with (estimated) price: {priced}", file=sys.stderr)
    if len(sites) < 50:
        sys.exit(f"Only {len(sites)} sites – refusing to overwrite good data")
    write(args.out, ops, sites, "Open Charge Map (openchargemap.org, CC BY 4.0) + listaárak")


if __name__ == "__main__":
    main()
