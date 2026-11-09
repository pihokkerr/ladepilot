import json, urllib.request, gzip
H = {"User-Agent": "Ladepilot/1.0 (personal, non-commercial)", "Accept": "application/json", "Accept-Encoding": "gzip"}
def get(u):
    r = urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=120); raw = r.read()
    if r.headers.get("Content-Encoding") == "gzip": raw = gzip.decompress(raw)
    return json.loads(raw)
base = "https://olcsontoltok.hu/api/chargers/map-catalog"
def prices(doc):
    out = {}
    for c in doc["chargers"]:
        if c["id"] in (1232,) or "Tesla" in c["name"] and len(out) < 3 or "MOL" in (c.get("operatorName") or "") and len(out) < 5:
            out[c["id"]] = (c["name"][:40], (c.get("schedule") or [{}])[0].get("price"))
    return out
ref = prices(get(base)); print("REF", ref)
for q in ["subscriptionId=12", "subscriptionIds=12", "subscriptions=12", "subscription=12", "subscriptionIds[]=12", "subs=12", "subscriptionId=5", "subscriptionIds=5,12,9"]:
    try:
        d = get(base + "?" + q); p = prices(d)
        print("Q", q, "DIFF" if p != ref else "same", {k: v for k, v in p.items() if ref.get(k) != v})
    except Exception as e:
        print("Q", q, "ERR", e)
