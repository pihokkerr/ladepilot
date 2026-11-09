"""Probe 2: Slovenian NAP datasets + PUN list API calls."""
import re
from playwright.sync_api import sync_playwright

def log_calls(pg, calls):
    def on_resp(r):
        if r.request.resource_type in ("xhr", "fetch"):
            try:
                body = r.body()[:900].decode("utf-8", "replace")
            except Exception:
                body = "?"
            calls.append((r.status, r.request.method, r.url, body, (r.request.post_data or "")[:300]))
    pg.on("response", on_resp)

with sync_playwright() as p:
    b = p.chromium.launch()
    for url in ["https://www.nap.si/en/datasets", "https://www.nap.si/en/datasets?q=charging", "https://www.nap.si/en/id-codes"]:
        print(f"\n######## {url}")
        pg = b.new_page(); calls = []; log_calls(pg, calls)
        try:
            pg.goto(url, timeout=60000, wait_until="networkidle"); pg.wait_for_timeout(3000)
            print("TEXT:", pg.inner_text("body")[:4000].replace("\n", " | "))
            for l in pg.eval_on_selector_all("a[href]", "els => els.map(e => e.href + ' | ' + e.innerText.trim().slice(0,80))"):
                if re.search(r"dataset|e-mob|charg|polni|afir|energy|datex|download|api", l, re.I):
                    print("LINK:", l)
        except Exception as e:
            print("ERR:", e)
        for c in calls[:30]:
            print("CALL", c[0], c[1], c[2][:300], "::", c[3][:700].replace("\n", " "), "POST:", c[4])
        pg.close()

    print("\n######## PUN list")
    pg = b.new_page(viewport={"width": 1400, "height": 1000}); calls = []; log_calls(pg, calls)
    try:
        pg.goto("https://www.piattaformaunicanazionale.it/idr", timeout=60000, wait_until="networkidle")
        pg.wait_for_timeout(5000)
        for label in ["Lista", "Mappa"]:
            try:
                pg.get_by_text(label, exact=True).first.click(); pg.wait_for_timeout(6000)
            except Exception as e:
                print("click", label, e)
        pg.mouse.move(700, 600); pg.mouse.wheel(0, -600); pg.wait_for_timeout(5000)
        print("TEXT:", pg.inner_text("body")[:3000].replace("\n", " | "))
    except Exception as e:
        print("ERR:", e)
    for c in calls:
        if "arcgis.com/4" in c[2] or "t9n" in c[2]:
            continue
        print("CALL", c[0], c[1], c[2][:400], "::", c[3][:900].replace("\n", " "), "POST:", c[4])
