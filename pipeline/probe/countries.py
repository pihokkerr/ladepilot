import json, urllib.request
for u in ["https://olcsontoltok.hu/api/subscriptions/for-pricing-selector", "https://olcsontoltok.hu/api/subscriptions"]:
    try:
        req = urllib.request.Request(u, headers={"User-Agent": "Ladepilot/1.0 (personal, non-commercial)", "Accept": "application/json"})
        raw = urllib.request.urlopen(req, timeout=60).read().decode()
        print("HUSUB", u, len(raw))
        js = json.loads(raw)
        print("HUSUBJSON", json.dumps(js, ensure_ascii=False)[:12000])
    except Exception as e:
        print("HUSUB ERR", u, e)
