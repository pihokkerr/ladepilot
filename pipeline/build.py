#!/usr/bin/env python3
"""Ladepilot data pipeline.

Downloads the official AFIR / DATEX II charging data and condenses it into a
small JSON file the map can load quickly (even in the car browser).

Sources
  AT  E-Control Ladestellenverzeichnis (api.e-control.at), all operators:
      - table publication  -> sites, coordinates, power, connectors
      - status publication -> live status + ad-hoc prices per charge point
  DE  Mobilithek AFIR datasets (DATEX II, CC0). For now: Tesla static snapshot
      (contains time-of-day ad-hoc prices). More operators can be added the
      same way once machine access to Mobilithek is set up.

Output format (compact arrays to keep the file small)
  {
    "generated": "<ISO time>",
    "source": "...",
    "ops":   ["Operator A", "Operator B", ...],
    "sites": [[id, name, opIndex, lat, lon, address, country, points, schedule?], ...]
  }
  points   = [[kw, plugs, eurPerKwh|null, eurPerMin|null, status, flags?], ...]
             flags: 1 = free of charge (FREE pricing policy)
  plugs    = short string, e.g. "CCS", "T2", "CCS+CHA"
  status   = 0 unknown, 1 available, 2 occupied, 3 out of service
  schedule = optional [[startMinute, endMinute, eurPerKwh], ...] (local wall clock) or null
  extra    = optional {"cur": "HUF", "fx": HUF per EUR, "abs": [[epochSec, eurPerKwh], ...],
              "fees": {...}, "card": 1}  – abs = absolute price schedule (latest entry <= now applies)

Usage
  python build.py at  --out out/at.json        (needs env ECONTROL_API_KEY)
  python build.py at  --table t.json --status s.json --out out/at.json  (offline)
  python build.py de-tesla --input tesla_static.json --out site/public/data/de_tesla.json
"""
import argparse
import datetime as dt
import gzip
import json
import os
import sys
import time
import urllib.error
import urllib.request

ECONTROL_BASE = "https://api.e-control.at/charge/1.0"
ECONTROL_TABLE = "/datex2/v3.5/energy-infrastructure-table-publication"
ECONTROL_STATUS = "/datex2/v3.5/energy-infrastructure-status-publication"
# The API checks the Referer against the domain registered with the key.
REFERER = os.environ.get("ECONTROL_REFERER", "https://ladepilot.pages.dev/")

STATUS_CODES = {
    "AVAILABLE": 1,
    "CHARGING": 2, "OCCUPIED": 2, "RESERVED": 2, "BLOCKED": 2,
    "INOPERATIVE": 3, "OUTOFORDER": 3, "OUT_OF_ORDER": 3, "FAULTED": 3,
    "UNAVAILABLE": 3, "CLOSED": 3, "PLANNED": 3, "REMOVED": 3,
}


# ---------------------------------------------------------------- helpers
class UpstreamDown(Exception):
    """The data provider answered with a server error several times – keep the old data."""


def fetch_json(path, api_key):
    req = urllib.request.Request(
        ECONTROL_BASE + path,
        headers={"apiKey": api_key, "Referer": REFERER,
                 "Accept": "application/json", "Accept-Encoding": "gzip"},
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                raw = resp.read()
                if resp.headers.get("Content-Encoding") == "gzip":
                    raw = gzip.decompress(raw)
            break
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as e:
            code = getattr(e, "code", None)
            if code is not None and code < 500 and code != 429:
                raise                                   # 4xx = our problem (key, referer) – fail loudly
            print(f"  {path}: attempt {attempt + 1} failed ({e})", file=sys.stderr)
            if attempt == 3:
                raise UpstreamDown(f"{path}: {e}")
            time.sleep(30 * (attempt + 1))
    print(f"  {path}: {len(raw) / 1e6:.1f} MB", file=sys.stderr)
    return json.loads(raw)


def as_list(x):
    if x is None:
        return []
    return x if isinstance(x, list) else [x]


def text(v):
    """Multilingual string -> plain text. Handles both DATEX JSON flavours:
    {"values": {"value": [{"value": ...}]}} (E-Control) and
    {"values": [{"value": ...}]} (Mobilithek)."""
    if not isinstance(v, dict):
        return v if isinstance(v, str) else ""
    vals = v.get("values")
    if isinstance(vals, dict):
        vals = vals.get("value")
    for item in as_list(vals):
        if isinstance(item, dict) and item.get("value"):
            return str(item["value"]).strip()
    return ""


def find_key(obj, key, depth=0):
    """Depth-first search for the first list stored under `key`."""
    if depth > 8:
        return None
    if isinstance(obj, dict):
        if key in obj and isinstance(obj[key], list):
            return obj[key]
        for v in obj.values():
            r = find_key(v, key, depth + 1)
            if r is not None:
                return r
    elif isinstance(obj, list):
        for v in obj[:5]:
            r = find_key(v, key, depth + 1)
            if r is not None:
                return r
    return None


PLUG_SHORT = [
    ("T_2_COMBO", "CCS"), ("T2COMBO", "CCS"), ("T_1_COMBO", "CCS1"), ("T1COMBO", "CCS1"),
    ("CHADEMO", "CHA"), ("TESLA", "TSL"), ("T_2", "T2"), ("T2", "T2"),
    ("T_1", "T1"), ("T1", "T1"), ("DOMESTIC", "Schuko"), ("SCHUKO", "Schuko"),
]


def plug_code(connector_types):
    out = []
    for ct in connector_types:
        u = str(ct or "").upper()
        for needle, short in PLUG_SHORT:
            if needle in u:
                if short not in out:
                    out.append(short)
                break
    # A charge point that offers CCS is a DC point; drop exotic duplicates.
    return "+".join(out[:3]) if out else "?"


def r2(x):
    return None if x is None else round(float(x), 3)


# ---------------------------------------------------------------- Austria
def parse_at_rates(new_rates):
    """E-Control status -> (eur_per_kwh, eur_per_min, free)."""
    kwh = per_min = None
    policies = {str((x or {}).get("value")).upper()
                for x in as_list(((new_rates or {}).get("energyPricingPolicy") or {}).get("pricingPolicy"))}
    if "FREE" in policies:
        return 0.0, None, True
    for coll in as_list((new_rates or {}).get("rateLineCollection")):
        for line in as_list(coll.get("rateLine")):
            typ = str(((line.get("rateLineType") or {}).get("value")) or "").upper()
            val = line.get("value")
            if val is None:
                continue
            if typ == "PER_UNIT" and kwh is None:
                kwh = val
            elif typ == "INCREMENTING_RATE" and per_min is None:
                period = str(line.get("incrementPeriod") or "PT1M").upper()
                minutes = 1.0
                if period.startswith("PT") and period.endswith("M"):
                    try:
                        minutes = float(period[2:-1]) or 1.0
                    except ValueError:
                        pass
                elif period.startswith("PT") and period.endswith("H"):
                    try:
                        minutes = float(period[2:-1]) * 60 or 60.0
                    except ValueError:
                        pass
                per_min = val / minutes
    # 0 or ~0 €/kWh without FREE policy = per-minute tariff or placeholder, not a real kWh price
    if kwh is not None and kwh < 0.05:
        kwh = None
    return r2(kwh), (r2(per_min) if per_min else None), False


def build_at(table, status):
    # 1) live status + price per refill point id
    live = {}
    site_status = find_key(status, "energyInfrastructureSiteStatus") or []
    for ss in site_status:
        for st in as_list(ss.get("energyInfrastructureStationStatus")):
            for rp in as_list(st.get("refillPointStatus")):
                rid = (rp.get("reference") or {}).get("id")
                if not rid:
                    continue
                code = STATUS_CODES.get(str(((rp.get("status") or {}).get("value")) or "").upper(), 0)
                kwh, per_min, free = parse_at_rates(rp.get("newRates"))
                live[rid] = (code, kwh, per_min, free)

    # 2) static sites
    sites_raw = []
    for t in as_list(find_key(table, "energyInfrastructureTable") or table.get("energyInfrastructureTable")):
        sites_raw.extend(as_list(t.get("energyInfrastructureSite")))

    ops, op_index, sites = [], {}, []
    for s in sites_raw:
        loc = s.get("locationReference") or {}
        c = loc.get("coordinatesForDisplay") or (loc.get("pointByCoordinates") or {}).get("pointCoordinates") or {}
        lat, lon = c.get("latitude"), c.get("longitude")
        if lat is None or lon is None:
            continue
        op = text((s.get("operator") or {}).get("name")) or (s.get("operator") or {}).get("id") or "?"
        if op not in op_index:
            op_index[op] = len(ops)
            ops.append(op)
        addr = ((loc.get("_LocationReferenceExtension") or {}).get("facilityLocation") or {}).get("address") or {}
        street = " ".join(text(l.get("text")) for l in sorted(as_list(addr.get("addressLine")), key=lambda l: l.get("order", 0)))
        address = ", ".join(x for x in [street.strip(), f"{addr.get('postcode', '')} {text(addr.get('city'))}".strip()] if x)
        country = addr.get("countryCode") or "AT"

        points = []
        for st in as_list(s.get("energyInfrastructureStation")):
            for rp in as_list(st.get("refillPoint")):
                conns = as_list(rp.get("connector"))
                watts = max((cn.get("maxPowerAtSocket") or 0) for cn in conns) if conns else 0
                kw = round(watts / 1000) if watts > 1000 else round(watts)  # some report kW
                plugs = plug_code(((cn.get("connectorType") or {}).get("value")) for cn in conns)
                code, kwh, per_min, free = live.get(rp.get("id"), (0, None, None, False))
                pt = [kw, plugs, kwh, per_min, code]
                if free:
                    pt.append(1)
                points.append(pt)
        if not points:
            continue
        name = text(s.get("name")) or op
        sites.append([s.get("id"), name, op_index[op], round(lat, 5), round(lon, 5), address, country, points])

    return ops, sites, len(live)


# ---------------------------------------------------------------- Germany (Tesla static, Mobilithek)
def _hm(s):
    """'08:00:00+01:00' -> minutes after midnight (wall clock, offset ignored)."""
    h, m = s[:5].split(":")
    return int(h) * 60 + int(m)


def build_de_tesla(doc):
    payload = doc.get("payload") if "payload" in doc else doc.get("messageContainer", {}).get("payload")
    payload = payload[0] if isinstance(payload, list) else payload
    pub = payload["aegiEnergyInfrastructureTablePublication"]
    tables = as_list(pub["energyInfrastructureTable"])
    ops, sites = ["Tesla Supercharger"], []
    for t in tables:
        for s in as_list(t.get("energyInfrastructureSite")):
            loc = s.get("locationReference") or {}
            c = (loc.get("locPointLocation") or {}).get("coordinatesForDisplay") or {}
            if c.get("latitude") is None:
                continue
            addr = (((loc.get("locPointLocation") or {}).get("locLocationExtensionG") or {})
                    .get("FacilityLocation") or {}).get("address") or {}
            street = " ".join(text(l.get("text")) for l in sorted(as_list(addr.get("addressLine")), key=lambda l: l.get("order", 0)))
            address = ", ".join(x for x in [street.strip(), f"{addr.get('postcode', '')} {text(addr.get('city'))}".strip()] if x)
            base, schedule, points = None, [], []
            for st in as_list(s.get("energyInfrastructureStation")):
                for e in as_list(st.get("electricEnergy")):
                    for rate in as_list(e.get("energyRate")):
                        for p in as_list(rate.get("energyPrice")):
                            if (p.get("priceType") or {}).get("value") != "pricePerKWh":
                                continue
                            per = p.get("overallPeriod")
                            if not per:
                                base = p.get("value")
                                continue
                            for vp in as_list(per.get("validPeriod")):
                                for tp in as_list(vp.get("recurringTimePeriodOfDay")):
                                    a, b = _hm(tp["startTimeOfPeriod"]), _hm(tp["endTimeOfPeriod"])
                                    schedule.append([a, b if b > a else 1440, r2(p.get("value"))])
                for rp in as_list(st.get("refillPoint")):
                    cp = rp.get("aegiElectricChargingPoint") or rp
                    conns = as_list(cp.get("connector"))
                    watts = max((cn.get("maxPowerAtSocket") or 0) for cn in conns) if conns else 0
                    plugs = plug_code(((cn.get("connectorType") or {}).get("value")) for cn in conns)
                    points.append([round(watts / 1000), plugs, r2(base), None, 0])
            if not points:
                continue
            for p in points:
                p[2] = r2(base)
            name = "Tesla Supercharger " + text(s.get("name")).replace(", Germany", "").replace(" - ", " – ")
            row = [s.get("idG"), name, 0, round(c["latitude"], 5), round(c["longitude"], 5), address, "DE", points]
            if schedule:
                row.append(sorted(schedule))
            sites.append(row)
    return ops, sites



# ---------------------------------------------------------------- Hungary (OTPR / olcsontoltok.hu)
# Used with permission of ELMOB (Magyar Elektromobilitás Egyesület). Fetch gently (every 30 min).
HU_URL = "https://olcsontoltok.hu/api/chargers/map-catalog"


def fetch_hu():
    req = urllib.request.Request(HU_URL, headers={"User-Agent": "Ladepilot/1.0 (personal, non-commercial)",
                                                  "Accept": "application/json", "Accept-Encoding": "gzip"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read()
        if resp.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
    print(f"  map-catalog: {len(raw) / 1e6:.1f} MB", file=sys.stderr)
    return json.loads(raw)


def _epoch(iso):
    return int(dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp())


def build_hu(doc):
    eur_per_huf = (doc.get("ratesFromHuf") or {}).get("EUR") or (1 / 400)
    ops, op_index, sites = [], {}, []
    for c in doc.get("chargers") or []:
        lat, lon = c.get("lat"), c.get("lng")
        if lat is None or lon is None or not (45.5 < lat < 48.7 and 16 < lon < 23):
            continue
        if c.get("usageType") == "private":
            continue
        op = (c.get("operatorName") or "?").strip()
        if op.upper() == "MVM MOBILITI KFT.":
            op = "MVM Mobiliti"
        if op not in op_index:
            op_index[op] = len(ops)
            ops.append(op)
        sched = [s for s in (c.get("schedule") or []) if s.get("at")]
        first = sched[0] if sched else {}
        conn_prices = [x.get("amount") for x in (first.get("connectors") or [])]
        status = 3 if first.get("open") is False else 0
        points = []
        for i, cn in enumerate(c.get("connectors") or []):
            huf = conn_prices[i] if i < len(conn_prices) else first.get("price")
            plugs = plug_code([{"CCS Type 2": "T_2_COMBO", "Type 2": "T_2", "CHAdeMO": "CHADEMO"}.get(cn.get("type"), cn.get("type"))])
            points.append([round(cn.get("powerKw") or 0), plugs, r2(huf * eur_per_huf) if huf is not None else None, None, status])
        if not points:
            continue
        fees = {k: v for k, v in (c.get("extraFees") or {}).items() if v is not None}
        extra = {"cur": "HUF", "fx": round(1 / eur_per_huf, 2)}
        abs_sched = [[_epoch(s["at"]), r2(s["price"] * eur_per_huf)] for s in sched if s.get("price") is not None]
        if len(abs_sched) > 1:
            extra["abs"] = abs_sched
        if fees:
            extra["fees"] = fees
        if c.get("acceptsBankCard"):
            extra["card"] = 1
        name = (c.get("name") or op).replace("Tesla - ", "Tesla Supercharger ").replace(", Hungary", "")
        sites.append([f"HU-{c.get('id')}", name, op_index[op], round(lat, 5), round(lon, 5),
                      c.get("county") or "", "HU", points, None, extra])
    return ops, sites

# ---------------------------------------------------------------- main
def write(out, ops, sites, source):
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    doc = {
        "generated": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "source": source,
        "ops": ops,
        "sites": sites,
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
    n_points = sum(len(s[7]) for s in sites)
    print(f"wrote {out}: {len(sites)} sites, {n_points} points, {os.path.getsize(out) / 1e6:.2f} MB", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("at")
    a.add_argument("--out", required=True)
    a.add_argument("--table")
    a.add_argument("--status")
    a.add_argument("--min-sites", type=int, default=1000)
    h = sub.add_parser("hu")
    h.add_argument("--out", required=True)
    h.add_argument("--input")
    d = sub.add_parser("de-tesla")
    d.add_argument("--input", required=True)
    d.add_argument("--out", required=True)
    args = ap.parse_args()

    if args.cmd == "at":
        if args.table and args.status:
            table = json.load(open(args.table, encoding="utf-8"))
            status = json.load(open(args.status, encoding="utf-8"))
        else:
            key = os.environ.get("ECONTROL_API_KEY")
            if not key:
                sys.exit("ECONTROL_API_KEY is not set")
            print("Downloading E-Control data …", file=sys.stderr)
            try:
                status = fetch_json(ECONTROL_STATUS, key)
                table = fetch_json(ECONTROL_TABLE, key)
            except UpstreamDown as e:
                # E-Control is temporarily down (e.g. HTTP 507): keep the data from the last good run
                print(f"::warning::E-Control nem elérhető, a korábbi adat marad: {e}", file=sys.stderr)
                sys.exit(0)
        ops, sites, n_live = build_at(table, status)
        print(f"  live entries: {n_live}", file=sys.stderr)
        if len(sites) < args.min_sites:
            sys.exit(f"Only {len(sites)} sites parsed – refusing to overwrite good data")
        write(args.out, ops, sites, "E-Control Ladestellenverzeichnis (ladestellen.at)")
    elif args.cmd == "hu":
        doc = json.load(open(args.input, encoding="utf-8")) if args.input else fetch_hu()
        ops, sites = build_hu(doc)
        if len(sites) < 500:
            sys.exit(f"Only {len(sites)} HU sites parsed – refusing to overwrite good data")
        write(args.out, ops, sites, "OTPR – Országos TöltőPont Regiszter (ELMOB), olcsontoltok.hu")
    elif args.cmd == "de-tesla":
        ops, sites = build_de_tesla(json.load(open(args.input, encoding="utf-8")))
        write(args.out, ops, sites, "Mobilithek – Tesla Germany (AFIR, CC0)")


if __name__ == "__main__":
    main()
