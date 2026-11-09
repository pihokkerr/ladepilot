"""One-off probe: what do the SI / IT / HR charging data portals expose? Logs pages, links and XHR calls."""
import json, re, sys
from playwright.sync_api import sync_playwright

PAGES = [
    "https://www.nap.si/sl/e-mobility", "https://www.nap.si/en/e-mobility", "https://gis.realis.si/en/nap",
    "https://www.piattaformaunicanazionale.it/idr",
    "https://nap.hr", "https://www.nap.hr", "https://nap.hrvatske-ceste.hr", "https://elen.hep.hr", "https://www.elen.hr",
]
with sync_playwright() as p:
    b = p.chromium.launch()
    for url in PAGES:
        print(f"\n######## {url}")
        pg = b.new_page()
        calls = []
        def on_resp(r):
            ct = r.headers.get("content-type", "")
            if r.request.resource_type in ("xhr", "fetch") or "json" in ct or "xml" in ct:
                try:
                    body = r.body()[:600].decode("utf-8", "replace")
                except Exception:
                    body = "?"
                calls.append((r.status, r.url, ct, len(r.body()) if r.ok else 0, body))
        pg.on("response", on_resp)
        try:
            pg.goto(url, timeout=45000, wait_until="networkidle")
            pg.wait_for_timeout(4000)
            print("TITLE:", pg.title(), "| FINAL:", pg.url)
            txt = pg.inner_text("body")[:2500].replace("\n", " ")
            print("TEXT:", txt)
            links = pg.eval_on_selector_all("a[href]", "els => els.map(e => e.href + ' | ' + e.innerText.trim().slice(0,60))")
            for l in links:
                if re.search(r"api|datex|download|json|xml|e-mob|polni|charg|ricaric|dataset|ocpi|registr|login|prijav", l, re.I):
                    print("LINK:", l)
        except Exception as e:
            print("ERR:", e)
        for c in calls[:40]:
            print(f"CALL {c[0]} {c[1][:300]} [{c[2]}] {c[3]}B :: {c[4][:400]!r}")
        pg.close()
