#!/usr/bin/env python3
"""Germany: Mobilithek AFIR feeds (DATEX II v3, CC0) -> out/de.json.

Machine access with the Mobilithek client certificate (cert.pem + key.pem).
Subscriptions are listed in pipeline/mobilithek_subs.json.

  static feeds  (sites, power, ad-hoc prices) – big, refreshed every few hours, parsed result cached
  dynamic feeds (status, sometimes prices)    – small deltas, merged into a persistent state file

Usage
  python pipeline/de.py --cert cert.pem --key key.pem --cache cache --out out/de.json
  python pipeline/de.py --offline samples/ --cache cache --out out/de.json   (local test with saved files)
"""
import argparse
import datetime as dt
import gzip
import json
import os
import ssl
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(__file__))
from build import STATUS_CODES, as_list, plug_code, r2, text, write  # noqa: E402

PULL = "https://mobilithek.info:8443/mobilithek/api/v1.0/subscription?subscriptionID={}"
DE_VAT = 19.0
STATIC_MAX_AGE = 6 * 3600
STATUS_MAX_AGE = 3 * 3600          # older status -> unknown (deltas may be missed between pulls)

# feeds in priority order: the operators' own data first, aggregators later (dedupe keeps the first)
PRIORITY = ["Tesla", "EnBW mobility+", "VW Group Charging", "EWE Go", "EDRI", "Wirelane",
            "eRound", "vaylens", "chargecloud", "MONTA", "e-clearing.net"]

OP_ALIASES = {
    "ENBW": "EnBW", "ENBW AG": "EnBW", "ENBW ENERGIE BADEN-WÜRTTEMBERG AG": "EnBW",
    "TESLA": "Tesla Supercharger", "TESLA, INC.": "Tesla Supercharger", "TESLA GERMANY GMBH": "Tesla Supercharger",
}


# ---------------------------------------------------------------- download
def fetch(sub_id, cert, key, offline=None, name=None, kind=None):
    if offline:
        p = os.path.join(offline, f"{name.replace(' ', '_')}_{kind}.json")
        if not os.path.exists(p):
            return None
        raw = open(p, "rb").read()
    else:
        ctx = ssl.create_default_context()
        ctx.load_cert_chain(cert, key)
        req = urllib.request.Request(PULL.format(sub_id), headers={"Accept-Encoding": "gzip", "User-Agent": "Ladepilot/1.0"})
        with urllib.request.urlopen(req, timeout=300, context=ctx) as r:
            raw = r.read()
            if r.headers.get("Content-Encoding") == "gzip":
                raw = gzip.decompress(raw)
    if len(raw) < 20:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return tolerant_sites(raw.decode("utf-8", "replace"))


def tolerant_sites(s):
    """Fallback for truncated test samples: decode site objects one by one."""
    dec = json.JSONDecoder()
    sites, key = [], '"energyInfrastructureSite":'
    i = s.find(key)
    while i >= 0:
        j = s.find("[", i) + 1
        while True:
            while j < len(s) and s[j] in " ,\n\r\t":
                j += 1
            if j >= len(s) or s[j] == "]":
                break
            try:
                obj, j = dec.raw_decode(s, j)
            except json.JSONDecodeError:
                break
            sites.append(obj)
        i = s.find(key, j)
    return {"payload": {"aegiEnergyInfrastructureTablePublication": {"energyInfrastructureTable": [{"energyInfrastructureSite": sites}]}}}


def payloads(doc):
    p = doc.get("payload")
    if p is None:
        p = (doc.get("messageContainer") or {}).get("payload")
    return as_list(p)


# ---------------------------------------------------------------- helpers
def unwrap(obj):
    """{'aegiElectricChargingPoint': {...}} -> {...}"""
    if isinstance(obj, dict) and len(obj) == 1:
        v = next(iter(obj.values()))
        if isinstance(v, dict):
            return v
    return obj


def coords(loc):
    """First latitude/longitude pair anywhere inside a locationReference."""
    stack = [loc]
    while stack:
        o = stack.pop()
        if isinstance(o, dict):
            if "latitude" in o and "longitude" in o:
                return o["latitude"], o["longitude"]
            stack.extend(o.values())
        elif isinstance(o, list):
            stack.extend(o)
    return None, None


def address(loc):
    stack = [loc]
    while stack:
        o = stack.pop()
        if isinstance(o, dict):
            if isinstance(o.get("address"), dict):
                a = o["address"]
                lines = sorted(as_list(a.get("addressLine")), key=lambda l: l.get("order", 0))
                street = " ".join(text(l.get("text")) for l in lines).strip()
                city = f"{a.get('postcode', '')} {text(a.get('city'))}".strip()
                return ", ".join(x for x in [street, city] if x), a.get("countryCode")
            stack.extend(o.values())
        elif isinstance(o, list):
            stack.extend(o)
    return "", None


def op_name(*orgs):
    for o in orgs:
        o = unwrap(o or {})
        n = text(o.get("name")) or text(o.get("legalName"))
        if n:
            return OP_ALIASES.get(n.upper(), n)
    return None


def evse_key(s):
    return str(s or "").upper().replace("*", "").replace(" ", "").replace("-", "")


def gross(p):
    v = p.get("value")
    if v is None:
        return None
    if p.get("taxIncluded") is False:
        v = v * (1 + (p.get("taxRate") if p.get("taxRate") is not None else DE_VAT) / 100)
    return float(v)


def _hm(s):
    h, m = str(s)[:5].split(":")
    return int(h) * 60 + int(m)


def active(period, now):
    if not period:
        return True
    a, b = period.get("overallStartTime"), period.get("overallEndTime")
    try:
        if a and dt.datetime.fromisoformat(a.replace("Z", "+00:00")) > now + dt.timedelta(days=1):
            return False
        if b and dt.datetime.fromisoformat(b.replace("Z", "+00:00")) < now:
            return False
    except ValueError:
        pass
    return True


def parse_prices(prices, now):
    """energyPrice list -> (eur/kWh, eur/min, start fee, daily schedule)."""
    kwh = per_min = start = None
    sched = []
    for p in as_list(prices):
        typ = ((p.get("priceType") or {}).get("value")) or ""
        per = p.get("overallPeriod")
        if not active(per, now):
            continue
        v = gross(p)
        if v is None:
            continue
        tba = p.get("timeBasedApplicability") or {}
        if typ == "pricePerKWh":
            tod = [tp for vp in as_list((per or {}).get("validPeriod")) for tp in as_list(vp.get("recurringTimePeriodOfDay"))]
            if tod:
                for tp in tod:
                    try:
                        a, b = _hm(tp["startTimeOfPeriod"]), _hm(tp["endTimeOfPeriod"])
                    except (KeyError, ValueError):
                        continue
                    sched.append([a, b if b > a else 1440, r2(v)])
            elif kwh is None:
                kwh = v
        elif typ == "pricePerMinute":
            if (tba.get("fromMinute") or 0) == 0 and v > 0 and per_min is None:
                per_min = v           # charged from the first minute (later = blocking fee, ignored)
        elif typ in ("flatRate", "basePrice", "pricePerSession") and v > 0 and start is None:
            start = v
    if kwh is not None and kwh < 0.05:
        kwh = None
    return r2(kwh), r2(per_min), r2(start), sorted(sched)


def is_adhoc(rate):
    pol = str(((rate.get("ratePolicy") or {}).get("value")) or "adHoc").lower()
    cur = rate.get("applicableCurrency")
    return pol in ("adhoc", "ad-hoc", "adhocprice") and (not cur or "EUR" in as_list(cur))


def collect_rates(obj, out):
    stack = [obj]
    while stack:
        o = stack.pop()
        if isinstance(o, dict):
            for r in as_list(o.get("energyRate")):
                if isinstance(r, dict) and r.get("idG"):
                    out.setdefault(r["idG"], r)
            stack.extend(v for v in o.values() if isinstance(v, (dict, list)))
        elif isinstance(o, list):
            stack.extend(o)


def rates_of(holder, defs):
    """Ad-hoc rates attached to a refill point / station / site (inline or by reference)."""
    out = []
    for e in as_list(holder.get("electricEnergy")):
        for r in as_list(e.get("energyRate")):
            if is_adhoc(r):
                out.append(r)
        for ref in as_list(e.get("energyRateByReference")):
            r = defs.get(ref.get("idG"))
            if r and is_adhoc(r):
                out.append(r)
    return out


# ---------------------------------------------------------------- static
def parse_static(doc, feed, now):
    sites, adhoc_ids = [], {}
    for pl in payloads(doc):
        pub = pl.get("aegiEnergyInfrastructureTablePublication") or {}
        for t in as_list(pub.get("energyInfrastructureTable")):
            defs = {}
            collect_rates({k: v for k, v in t.items() if k != "energyInfrastructureSite"}, defs)  # table-level rates
            for s in as_list(t.get("energyInfrastructureSite")):
                sdefs = dict(defs)
                collect_rates(s, sdefs)
                for rid, r in sdefs.items():
                    adhoc_ids[rid] = is_adhoc(r)
                row = parse_site(s, sdefs, feed, now)
                if row:
                    sites.append(row)
    return sites, adhoc_ids


def parse_site(s, defs, feed, now):
    loc = s.get("locationReference") or {}
    lat, lon = coords(loc)
    stations = as_list(s.get("energyInfrastructureStation"))
    if lat is None and stations:
        lat, lon = coords(stations[0].get("locationReference") or {})
    if lat is None:
        return None
    addr, country = address(loc)
    if not addr and stations:
        addr, country = address(stations[0].get("locationReference") or {})
    country = (country or "DE").upper()
    if country not in ("DE", "D", "DEU") or not (47.2 < lat < 55.1 and 5.8 < lon < 15.1):
        return None
    op = op_name(s.get("operator"), *(st.get("operator") for st in stations)) or feed
    if feed == "VW Group Charging":
        op = "Elli (VW Group Charging)"      # this feed puts parking-lot names into the operator field
    elif feed == "EWE Go" or "*" in op:
        op = "EWE Go" if feed == "EWE Go" else feed
    site_rates = rates_of(s, defs)
    pts, sched, start = [], [], None
    for st in stations:
        st_rates = rates_of(st, defs) or site_rates
        for rp in as_list(st.get("refillPoint")):
            cp = unwrap(rp)
            conns = as_list(cp.get("connector"))
            watts = max([c.get("maxPowerAtSocket") or 0 for c in conns] + [max(as_list(cp.get("availableChargingPower")) or [0])])
            kw = round(watts / 1000) if watts > 1000 else round(watts)
            plugs = plug_code(((c.get("connectorType") or {}).get("value")) for c in conns)
            kwh = per_min = None
            for r in (rates_of(cp, defs) or st_rates):
                k, m, f, sc = parse_prices(r.get("energyPrice"), now)
                if k is None and m is None and not sc:
                    continue
                kwh, per_min = k, m
                start = start if start is not None else f
                if sc and not sched:
                    sched = sc
                break
            pts.append([cp.get("idG") or "", kw, plugs, kwh, per_min])
    if not pts:
        return None
    name = text(s.get("name")) or (text(stations[0].get("name")) if stations else "") or ""
    if op == "Tesla Supercharger":
        name = "Tesla Supercharger " + name.replace(", Germany", "").replace(" - ", " – ")
    elif not name or name.isdigit():
        name = f"{op} – {addr}" if addr else op
    return {"id": f"DE-{s.get('idG')}", "name": name, "op": op, "lat": round(lat, 5), "lon": round(lon, 5),
            "addr": addr, "pts": pts, "sched": sched or None, "start": start}


# ---------------------------------------------------------------- dynamic
def merge_dynamic(doc, state, adhoc_ids, now_ts, now):
    n = 0
    for pl in payloads(doc):
        pub = pl.get("aegiEnergyInfrastructureStatusPublication") or {}
        for ss in as_list(pub.get("energyInfrastructureSiteStatus")):
            for st in as_list(ss.get("energyInfrastructureStationStatus")):
                for rp in as_list(st.get("refillPointStatus")):
                    cp = unwrap(rp)
                    ref = (cp.get("reference") or {}).get("idG")
                    if not ref:
                        continue
                    k = evse_key(ref)
                    e = state.get(k) or [0, 0, None, None, None, 0]
                    code = STATUS_CODES.get(str(((cp.get("status") or {}).get("value")) or "").upper())
                    if code is not None:
                        e[0], e[1] = code, now_ts
                    ups = as_list(cp.get("energyRateUpdate"))
                    if ups:
                        known = [u for u in ups if adhoc_ids.get((u.get("energyRateReference") or {}).get("idG"))]
                        cand = known or (ups if len(ups) == 1 or not adhoc_ids else ups[:1])
                        for u in cand:
                            kwh, per_min, start, _ = parse_prices(u.get("energyPrice"), now)
                            if kwh is not None or per_min is not None:
                                e[2], e[3], e[4], e[5] = kwh, per_min, start, now_ts
                                break
                    state[k] = e
                    n += 1
    return n


# ---------------------------------------------------------------- BNetzA supplement
# Operators that publish nothing on Mobilithek. Locations come from the Bundesnetzagentur
# Ladesäulenregister; the price is the operator's public ad-hoc list price (marked as estimate).
BNETZA_PAGE = "https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/DownloadundKontakt.html"
SUPPLEMENT = {
    # IONITY Direct Germany from 1 Jul 2026: location based, up to 0.76 €/kWh (0.69 at cheaper sites)
    "IONITY": {"match": "ionity", "eur": 0.76},
}


def fetch_bnetza_csv():
    import re
    ua = {"User-Agent": "Ladepilot/1.0 (personal, non-commercial)"}
    html = urllib.request.urlopen(urllib.request.Request(BNETZA_PAGE, headers=ua), timeout=60).read().decode("utf-8", "replace")
    links = re.findall(r'href="([^"]*Ladesaeulenregister[^"]*\.csv[^"]*)"', html)
    if not links:
        raise RuntimeError("no CSV link on the BNetzA download page")
    url = links[0].replace("&amp;", "&")
    if url.startswith("/"):
        url = "https://www.bundesnetzagentur.de" + url
    raw = urllib.request.urlopen(urllib.request.Request(url, headers=ua), timeout=300).read()
    for enc in ("utf-8-sig", "cp1252"):
        try:
            return raw.decode(enc), url
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1"), url


def parse_bnetza(csv_text):
    import csv
    lines = csv_text.splitlines()
    start = next(i for i, l in enumerate(lines) if "Betreiber" in l and "Breitengrad" in l)
    rows = csv.DictReader(lines[start:], delimiter=";")

    def num(v):
        try:
            return float(str(v).strip().replace(".", "").replace(",", ".")) if "," in str(v) else float(str(v).strip())
        except ValueError:
            return None

    fields = rows.fieldnames or []
    print("  BNetzA columns: " + " | ".join(fields[:20]), file=sys.stderr)

    def col(prefix):
        return next((f for f in fields if f and f.strip().lower().startswith(prefix.lower())), prefix)
    C_LAT, C_LON, C_KW, C_N = col("Breitengrad"), col("Längengrad"), col("Nennleistung Ladeeinrichtung"), col("Anzahl Ladepunkte")
    C_PLUG, C_STATUS = col("Steckertypen1"), col("Status")

    groups = {}
    for r in rows:
        betr = (r.get("Betreiber") or "").strip()
        key = next((k for k, c in SUPPLEMENT.items() if c["match"] in betr.lower()), None)
        if not key:
            continue
        if (r.get(C_STATUS) or "In Betrieb").strip().lower() not in ("in betrieb", ""):
            continue
        lat = num(r.get(C_LAT)); lon = num(r.get(C_LON))
        if lat is None or lon is None:
            continue
        kw = num(r.get(C_KW)) or 0
        n = int(num(r.get(C_N)) or 1)
        plugs = r.get(C_PLUG) or ""
        street = f"{(r.get('Straße') or '').strip()} {(r.get('Hausnummer') or '').strip()}".strip()
        city = f"{(r.get('Postleitzahl') or '').strip()} {(r.get('Ort') or '').strip()}".strip()
        g = groups.setdefault((key, round(lat, 3), round(lon, 3)), {"lat": lat, "lon": lon, "addr": ", ".join(x for x in [street, city] if x),
                                                                "city": (r.get("Ort") or "").strip(), "pts": []})
        per_point_kw = kw / n if n > 1 and kw > 400 else kw    # some entries give the sum of all points
        pcode = plug_code(["T2COMBO" if "CCS" in plugs.upper() or "COMBO" in plugs.upper() else plugs])
        for _ in range(max(1, min(n, 12))):
            g["pts"].append([round(per_point_kw), pcode])
    sites = []
    for (key, _, _), g in groups.items():
        c = SUPPLEMENT[key]
        pts = [["", kw, pl, c["eur"], None, 2] for kw, pl in g["pts"]]   # flag 2 = estimated list price
        sites.append({"id": f"DE-BNA-{key}-{g['lat']:.4f},{g['lon']:.4f}", "name": f"{key} {g['city']}".strip(),
                      "op": key, "lat": round(g["lat"], 5), "lon": round(g["lon"], 5), "addr": g["addr"],
                      "pts": pts, "sched": None, "start": None})
    return sites


# ---------------------------------------------------------------- merge + output
def build(feeds, state, now_ts):
    ops, op_index, out = [], {}, []
    seen_evse, grid = set(), {}
    stats = {}
    for feed in feeds:
        name, sites = feed["name"], feed["sites"]
        kept = 0
        for s in sites:
            pts = [p for p in s["pts"] if not p[0] or evse_key(p[0]) not in seen_evse]
            if not pts:
                continue
            # near-duplicate site from another feed (EVSE ids differ between platforms)
            cell = (round(s["lat"], 3), round(s["lon"], 3))
            maxkw = max(p[1] for p in pts)
            dup = any(abs(o["lat"] - s["lat"]) < 0.0003 and abs(o["lon"] - s["lon"]) < 0.0004 and o["maxkw"] == maxkw and o["feed"] != name
                      for dx in (-0.001, 0, 0.001) for dy in (-0.001, 0, 0.001)
                      for o in grid.get((round(cell[0] + dx, 3), round(cell[1] + dy, 3)), []))
            if dup:
                continue
            for p in pts:
                if p[0]:
                    seen_evse.add(evse_key(p[0]))
            grid.setdefault(cell, []).append({"lat": s["lat"], "lon": s["lon"], "maxkw": maxkw, "feed": name})
            op = s["op"]
            if op not in op_index:
                op_index[op] = len(ops)
                ops.append(op)
            points, start = [], s.get("start")
            for evse, kw, plugs, kwh, per_min, *flags in pts:
                st = state.get(evse_key(evse)) if evse else None
                status = 0
                if st:
                    if st[1] and now_ts - st[1] < STATUS_MAX_AGE:
                        status = st[0]
                    if st[5] and (st[2] is not None or st[3] is not None):
                        kwh, per_min = st[2], st[3]
                        start = st[4] if st[4] is not None else start
                points.append([kw, plugs, kwh, per_min, status] + ([flags[0]] if flags and flags[0] else []))
            row = [s["id"], s["name"], op_index[op], s["lat"], s["lon"], s["addr"], "DE", points, s.get("sched")]
            if start:
                row.append({"fees": {"startFee": start}})
            out.append(row)
            kept += 1
        stats[name] = kept
    return ops, out, stats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cert")
    ap.add_argument("--key")
    ap.add_argument("--offline")
    ap.add_argument("--cache", default="cache")
    ap.add_argument("--out", required=True)
    ap.add_argument("--subs", default=os.path.join(os.path.dirname(__file__), "mobilithek_subs.json"))
    args = ap.parse_args()

    subs = json.load(open(args.subs, encoding="utf-8"))
    os.makedirs(os.path.join(args.cache, "static"), exist_ok=True)
    state_path = os.path.join(args.cache, "state.json")
    state = json.load(open(state_path)) if os.path.exists(state_path) else {}
    now = dt.datetime.now(dt.timezone.utc)
    now_ts = int(time.time())

    feeds = []
    for name in sorted(subs, key=lambda n: PRIORITY.index(n) if n in PRIORITY else 99):
        ids = subs[name]
        cpath = os.path.join(args.cache, "static", name.replace(" ", "_") + ".json")
        cached = json.load(open(cpath)) if os.path.exists(cpath) else None
        if ids.get("stat") and (cached is None or now_ts - cached["fetched"] > STATIC_MAX_AGE or args.offline):
            t0 = time.time()
            try:
                doc = fetch(ids["stat"], args.cert, args.key, args.offline, name, "stat")
                if doc is not None:
                    sites, adhoc = parse_static(doc, name, now)
                    del doc
                    if sites or cached is None:
                        cached = {"fetched": now_ts, "sites": sites, "adhoc": adhoc}
                        json.dump(cached, open(cpath, "w"), separators=(",", ":"))
                    print(f"  {name} static: {len(sites)} sites ({time.time() - t0:.0f}s)", file=sys.stderr)
            except Exception as e:  # keep the old cache if one feed fails
                print(f"  {name} static FAILED: {e}", file=sys.stderr)
        if cached is None:
            continue
        if ids.get("dyn"):
            try:
                doc = fetch(ids["dyn"], args.cert, args.key, args.offline, name, "dyn")
                if doc is not None:
                    n = merge_dynamic(doc, state, cached.get("adhoc") or {}, now_ts, now)
                    print(f"  {name} dynamic: {n} updates", file=sys.stderr)
            except Exception as e:
                print(f"  {name} dynamic FAILED: {e}", file=sys.stderr)
        feeds.append({"name": name, "sites": cached["sites"]})

    # BNetzA register (monthly file) for operators missing on Mobilithek
    bpath = os.path.join(args.cache, "static", "bnetza.json")
    bcache = json.load(open(bpath)) if os.path.exists(bpath) else None
    if not args.offline and (bcache is None or now_ts - bcache["fetched"] > 3 * 86400):
        try:
            text_csv, url = fetch_bnetza_csv()
            bsites = parse_bnetza(text_csv)
            if bsites:
                bcache = {"fetched": now_ts, "url": url, "sites": bsites}
                json.dump(bcache, open(bpath, "w"), separators=(",", ":"))
            print(f"  BNetzA supplement: {len(bsites)} sites from {url.rsplit('/', 1)[-1]}", file=sys.stderr)
        except Exception as e:
            print(f"  BNetzA supplement FAILED: {e}", file=sys.stderr)
    if bcache:
        feeds.append({"name": "BNetzA", "sites": bcache["sites"]})

    # forget state entries not touched for a week
    state = {k: v for k, v in state.items() if now_ts - max(v[1], v[5]) < 7 * 86400}
    json.dump(state, open(state_path, "w"), separators=(",", ":"))

    ops, sites, stats = build(feeds, state, now_ts)
    print("  kept per feed: " + ", ".join(f"{k}={v}" for k, v in stats.items()), file=sys.stderr)
    if len(sites) < (50 if args.offline else 5000):
        sys.exit(f"Only {len(sites)} DE sites – refusing to overwrite good data")
    write(args.out, ops, sites, "Mobilithek – AFIR-Daten der Ladepunktbetreiber (CC0)")


if __name__ == "__main__":
    main()
