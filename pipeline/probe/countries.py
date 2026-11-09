import json, urllib.request, gzip, collections, re
for c in ["at", "de", "hu"]:
    r = urllib.request.urlopen(urllib.request.Request(f"https://ladepilot.pages.dev/api/{c}", headers={"Accept-Encoding": "gzip", "User-Agent": "Mozilla/5.0 Ladepilot-probe"}), timeout=120)
    raw = r.read(); enc = r.headers.get("Content-Encoding")
    print("ENC", c, enc, len(raw))
    if enc == "gzip": raw = gzip.decompress(raw)
    d = json.loads(raw)
    cnt = collections.Counter(d["ops"][s[2]] for s in d["sites"])
    print("OPS", c, json.dumps(cnt.most_common(70), ensure_ascii=False))
    for pat in ["ionity", "tesla", "shell", "smatrics", "wien", "enbw", "ewe", "aral", "linz", "omv", "mol", "lidl", "evn", "kelag", "ella", "energie steiermark", "salzburg", "vbg|illwerke|vkw", "tiwag", "mobiliti", "e.on", "elli", "fastned", "allego", "petrol"]:
        m = {k: v for k, v in cnt.items() if re.search(pat, k, re.I)}
        print("MATCH", c, pat, json.dumps(m, ensure_ascii=False)[:500])
