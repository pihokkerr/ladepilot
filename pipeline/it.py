#!/usr/bin/env python3
"""Italy: Piattaforma Unica Nazionale (PUN, MASE) -> out/it.json.

The public map of the Italian ministry is served by an AWS API gateway; the web page signs its
requests with anonymous (unauthenticated) Cognito credentials. We do exactly the same, gently:
  - /chargepoints/public/map/search  every run  -> all EVSEs with live status + coordinates
  - /chargepoints/group              every 12 h -> details (operator, address, power, PUN tariffs)

Usage: python pipeline/it.py --cache cache --out out/it.json
"""
import argparse
import json
import os
import sys
import time

import boto3
import requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest

sys.path.insert(0, os.path.dirname(__file__))
from build import STATUS_CODES, plug_code, r2, write  # noqa: E402

API = "https://api.pun.piattaformaunicanazionale.it/v1"
POOL = "eu-south-1:e3b2ab05-2046-43dd-8ed0-c0f14c69d507"
REGION = "eu-south-1"
DETAIL_MAX_AGE = 12 * 3600
SKIP_STATUS = {"REMOVED", "PLANNED"}


class Pun:
    def __init__(self):
        ci = boto3.client("cognito-identity", region_name=REGION)
        iid = ci.get_id(IdentityPoolId=POOL)["IdentityId"]
        c = ci.get_credentials_for_identity(IdentityId=iid)["Credentials"]
        self.creds = boto3.Session(aws_access_key_id=c["AccessKeyId"], aws_secret_access_key=c["SecretKey"],
                                   aws_session_token=c["SessionToken"]).get_credentials()
        self.http = requests.Session()
        self.http.headers["User-Agent"] = "Ladepilot/1.0 (personal, non-commercial)"

    def post(self, path, body):
        data = json.dumps(body)
        for attempt in range(4):
            req = AWSRequest(method="POST", url=API + path, data=data, headers={"Content-Type": "application/json"})
            SigV4Auth(self.creds, "execute-api", REGION).add_auth(req)
            r = self.http.post(API + path, data=data, headers=dict(req.headers), timeout=180)
            if r.status_code == 200:
                return r.json()
            time.sleep(3 * (attempt + 1))
        r.raise_for_status()


def fetch_status(pun):
    out, page = {}, 0
    while True:
        js = pun.post("/chargepoints/public/map/search", {"page": page, "size": 12000})
        for c in js.get("content") or []:
            out[c["evse_id"]] = (c.get("status") or "UNKNOWN", c.get("coordinates") or {})
        if js.get("last", True) or not js.get("content"):
            break
        page += 1
        time.sleep(0.5)
    return out


def fetch_details(pun, ids):
    det = {}
    for k in range(0, len(ids), 500):
        for x in pun.post("/chargepoints/group", ids[k:k + 500]) or []:
            det[x["evse_id"]] = slim(x)
        time.sleep(0.3)
    return det


def slim(x):
    loc = x.get("location") or {}
    conns = x.get("connectors") or []
    watts = max([c.get("max_electric_power") or 0 for c in conns] or [0])
    power_type = next((c.get("power_type") for c in conns if c.get("power_type")), "")
    t = x.get("punTariffsDetails") or {}
    kw = round(watts / 1000) if watts > 1000 else round(watts)
    key = "acTariff" if power_type.startswith("AC") or kw <= 43 else ("hpcTariff" if kw >= 150 else "dcTariff")
    tar = t.get(key) or t.get("dcTariff" if key == "hpcTariff" else "acTariff") or {}
    return {
        "loc": x.get("locationId") or loc.get("_id"),
        "op": (x.get("businessName") or loc.get("party_id") or "?").strip(),
        "addr": ", ".join(v for v in [loc.get("address") or "", f"{loc.get('postal_code') or ''} {loc.get('city') or ''}".strip()] if v),
        "city": loc.get("city") or "",
        "lat": (loc.get("coordinates") or x.get("coordinates") or {}).get("latitude"),
        "lon": (loc.get("coordinates") or x.get("coordinates") or {}).get("longitude"),
        "kw": kw,
        "plugs": plug_code(c.get("standard") for c in conns),
        "eur": tar.get("energy"), "min": tar.get("time"), "act": tar.get("activation"),
    }


OP_ALIASES = {"TESLA": "Tesla Supercharger", "TESLA ITALY S.R.L.": "Tesla Supercharger", "IONITY GMBH": "IONITY"}


def build(status, det):
    ops, op_index, by_loc = [], {}, {}
    for evse, (st, coords) in status.items():
        if st.upper() in SKIP_STATUS:
            continue
        d = det.get(evse)
        if not d or d["lat"] is None:
            continue
        g = by_loc.setdefault(d["loc"] or evse, {"d": d, "pts": []})
        eur = d["eur"] if d["eur"] and d["eur"] >= 0.05 else None
        per_min = d["min"] if d["min"] and d["min"] > 0 else None
        g["pts"].append([d["kw"], d["plugs"], r2(eur), r2(per_min), STATUS_CODES.get(st.upper(), 0)])
        if d["act"] and d["act"] > 0:
            g["start"] = d["act"]
    sites = []
    for loc, g in by_loc.items():
        d = g["d"]
        op = OP_ALIASES.get(d["op"].upper(), d["op"])
        if "TESLA" in op.upper():
            op = "Tesla Supercharger"
        if op not in op_index:
            op_index[op] = len(ops)
            ops.append(op)
        name = f"{op} {d['city']}".strip() if op == "Tesla Supercharger" or not d["addr"] else f"{op} – {d['addr']}"
        row = [f"IT-{loc}", name, op_index[op], round(d["lat"], 5), round(d["lon"], 5), d["addr"], "IT", g["pts"], None]
        if g.get("start"):
            row.append({"fees": {"startFee": r2(g["start"])}})
        sites.append(row)
    return ops, sites


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    os.makedirs(args.cache, exist_ok=True)
    dpath = os.path.join(args.cache, "it_details.json")
    cached = json.load(open(dpath)) if os.path.exists(dpath) else {"fetched": 0, "det": {}}

    pun = Pun()
    t0 = time.time()
    status = fetch_status(pun)
    print(f"  status: {len(status)} EVSEs ({time.time() - t0:.0f}s)", file=sys.stderr)
    det = cached["det"]
    missing = [e for e in status if e not in det]
    if time.time() - cached["fetched"] > DETAIL_MAX_AGE:
        t0 = time.time()
        det = fetch_details(pun, list(status.keys()))
        cached = {"fetched": int(time.time()), "det": det}
        print(f"  details: {len(det)} ({time.time() - t0:.0f}s)", file=sys.stderr)
    elif missing:
        det.update(fetch_details(pun, missing))
        print(f"  details for {len(missing)} new EVSEs", file=sys.stderr)
    json.dump(cached, open(dpath, "w"), separators=(",", ":"))

    ops, sites = build(status, det)
    n_price = sum(1 for s in sites for p in s[7] if p[2] is not None or p[3] is not None)
    print(f"  sites: {len(sites)}, points with price: {n_price}", file=sys.stderr)
    if len(sites) < 5000:
        sys.exit(f"Only {len(sites)} IT sites – refusing to overwrite good data")
    write(args.out, ops, sites, "Piattaforma Unica Nazionale (MASE) – piattaformaunicanazionale.it")


if __name__ == "__main__":
    main()
