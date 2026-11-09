import json, urllib.request, gzip, collections
H = {"User-Agent": "Ladepilot/1.0 (personal, non-commercial)", "Accept": "application/json", "Accept-Encoding": "gzip"}
def get(u):
    r = urllib.request.urlopen(urllib.request.Request(u, headers=H), timeout=120); raw = r.read()
    if r.headers.get("Content-Encoding") == "gzip": raw = gzip.decompress(raw)
    return json.loads(raw)
subs = get("https://olcsontoltok.hu/api/subscriptions")
for s in subs:
    s.pop("market", None)
    print("SUB", json.dumps(s, ensure_ascii=False)[:700])
doc = get("https://olcsontoltok.hu/api/chargers/map-catalog")
print("TOPKEYS", list(doc.keys()))
cs = doc["chargers"]
keys = collections.Counter(k for c in cs for k in c.keys())
print("CHKEYS", keys.most_common())
def find(o, path=""):
    if isinstance(o, dict):
        for k, v in o.items():
            if "sub" in k.lower() or "tarif" in k.lower(): print("SUBFIELD", path + "." + k, json.dumps(v, ensure_ascii=False)[:600])
            find(v, path + "." + k)
    elif isinstance(o, list) and o:
        find(o[0], path + "[]")
for c in cs[:400]:
    find(c)
print("SAMPLE", json.dumps(next(c for c in cs if "Mobiliti" in (c.get("operatorName") or "") ), ensure_ascii=False)[:3500])
