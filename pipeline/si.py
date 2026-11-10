#!/usr/bin/env python3
"""Slovenia: NAP Slovenia (MZIE, Prometej IDACS) DATEX II v3.6 XML -> out/si.json.

  table  – sites, power, ad-hoc prices (16 MB) – cached, refreshed every 6 h
  status – full status snapshot (2 MB)         – every run

HTTP Basic auth with the user's nap.si account (NAP_SI_USER / NAP_SI_PASSWORD).
Usage: python pipeline/si.py --cache cache --out out/si.json
"""
import argparse
import base64
import gzip
import json
import os
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(__file__))
from build import STATUS_CODES, plug_code, r2, write  # noqa: E402

BASE = "https://b2b.nap.si/data/b2b.prometej."
TABLE_MAX_AGE = 6 * 3600
OP_ALIASES = {"TESLA ENERGY D.O.O.": "Tesla Supercharger", "IONITY GMBH": "IONITY", "PETROL, D.D": "Petrol",
              "MOL SLOVENIJA D.O.O.": "MOL", "LIDL SLOVENIJA D.O.O.  K.D.": "Lidl", "ELEKTRO LJUBLJANA D.D.": "Elektro Ljubljana (Gremo na elektriko)"}


def fetch(name):
    auth = base64.b64encode(f"{os.environ['NAP_SI_USER']}:{os.environ['NAP_SI_PASSWORD']}".encode()).decode()
    req = urllib.request.Request(BASE + name, headers={"Authorization": "Basic " + auth, "Accept-Encoding": "gzip",
                                                       "User-Agent": "Ladepilot/1.0 (personal, non-commercial)"})
    with urllib.request.urlopen(req, timeout=300) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
    return raw


def strip(tag):
    return tag.rsplit("}", 1)[-1]


def child(el, name):
    for c in el:
        if strip(c.tag) == name:
            return c
    return None


def children(el, name):
    return [c for c in el if strip(c.tag) == name]


def find(el, name):
    for c in el.iter():
        if strip(c.tag) == name:
            return c
    return None


def mtext(el):
    """multilingual text: first com:value"""
    if el is None:
        return ""
    v = find(el, "value")
    return (v.text or "").strip() if v is not None else (el.text or "").strip()


def num(el):
    try:
        return float(el.text)
    except (AttributeError, TypeError, ValueError):
        return None


def parse_table(raw):
    root = ET.fromstring(raw)
    sites = []
    for s in root.iter():
        if strip(s.tag) != "energyInfrastructureSite":
            continue
        loc = child(s, "locationReference")
        cd = find(loc, "coordinatesForDisplay") if loc is not None else None
        lat, lon = (num(find(cd, "latitude")), num(find(cd, "longitude"))) if cd is not None else (None, None)
        if lat is None or lon is None:
            continue
        a = find(loc, "address")
        addr = ""
        if a is not None:
            street = " ".join(mtext(find(l, "text")) for l in children(a, "addressLine")).strip()
            city = f"{(find(a, 'postcode').text if find(a, 'postcode') is not None else '')} {mtext(find(a, 'city'))}".strip()
            addr = ", ".join(x for x in [street, city] if x)
        opel = child(s, "operator")
        op = mtext(child(opel, "name")) if opel is not None else ""
        op = OP_ALIASES.get(op.upper(), op) or "?"
        name = mtext(child(s, "name")) or op
        pts = []
        for st in children(s, "energyInfrastructureStation"):
            for rp in children(st, "refillPoint"):
                conns = children(rp, "connector")
                watts = max([num(child(c, "maxPowerAtSocket")) or 0 for c in conns] or [0])
                kw = round(watts / 1000) if watts > 1000 else round(watts)
                plugs = plug_code((child(c, "connectorType").text if child(c, "connectorType") is not None else "") for c in conns)
                kwh = per_min = start = None
                for rates in children(rp, "rates"):
                    cur = child(rates, "applicableCurrency")
                    if cur is not None and cur.text != "EUR":
                        continue
                    for line in rates.iter():
                        if strip(line.tag) != "rateLine":
                            continue
                        typ = (child(line, "rateLineType").text or "") if child(line, "rateLineType") is not None else ""
                        v = num(child(line, "value"))
                        if v is None:
                            continue
                        tax = find(line, "taxIncluded")
                        if tax is not None and tax.text == "false":
                            v *= 1.22                       # Slovenian VAT
                        t = typ.lower()
                        if t == "perunit" and kwh is None:
                            kwh = v
                        elif ("time" in t or "minute" in t) and per_min is None:
                            per_min = v
                        elif ("flat" in t or "session" in t) and start is None:
                            start = v
                pts.append([rp.get("id") or "", kw, plugs, r2(kwh) if kwh and kwh >= 0.05 else None, r2(per_min) if per_min else None, start])
        if pts:
            sites.append({"id": "SI-" + (s.get("id") or ""), "name": name, "op": op, "lat": round(lat, 5), "lon": round(lon, 5),
                          "addr": addr, "pts": pts})
    return sites


def parse_status(raw):
    root = ET.fromstring(raw)
    out = {}
    for rp in root.iter():
        if strip(rp.tag) != "refillPointStatus":
            continue
        ref = child(rp, "reference")
        st = child(rp, "status")
        if ref is not None and st is not None:
            out[ref.get("id")] = STATUS_CODES.get((st.text or "").upper(), 0)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    tpath = os.path.join(args.cache, "si_table.json")
    cached = json.load(open(tpath)) if os.path.exists(tpath) else None
    if cached is None or time.time() - cached["fetched"] > TABLE_MAX_AGE:
        t0 = time.time()
        sites = parse_table(fetch("energyInfrastructureTablePublication"))
        if sites:
            cached = {"fetched": int(time.time()), "sites": sites}
            json.dump(cached, open(tpath, "w"), separators=(",", ":"))
        print(f"  table: {len(sites)} sites ({time.time() - t0:.0f}s)", file=sys.stderr)
    status = parse_status(fetch("energyInfrastructureStatusPublication"))
    print(f"  status: {len(status)} points", file=sys.stderr)

    ops, op_index, rows = [], {}, []
    for s in cached["sites"]:
        if s["op"] not in op_index:
            op_index[s["op"]] = len(ops)
            ops.append(s["op"])
        points, start = [], None
        for evse, kw, plugs, kwh, per_min, fee in s["pts"]:
            points.append([kw, plugs, kwh, per_min, status.get(evse, 0)])
            start = start or fee
        row = [s["id"], s["name"], op_index[s["op"]], s["lat"], s["lon"], s["addr"], "SI", points, None]
        if start:
            row.append({"fees": {"startFee": r2(start)}})
        rows.append(row)
    if len(rows) < 100:
        sys.exit(f"Only {len(rows)} SI sites – refusing to overwrite good data")
    write(args.out, ops, rows, "NAP Slovenija – MZIE / Prometej IDACS (DATEX II)")


if __name__ == "__main__":
    main()
