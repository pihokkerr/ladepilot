"""Probe 3: NAP.si charging datasets + PUN API access."""
import json, re, urllib.request
import boto3, requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page()
    pg.goto("https://www.nap.si/en/datasets", timeout=60000, wait_until="networkidle"); pg.wait_for_timeout(3000)
    txt = pg.inner_text("body")
    blocks = txt.split("\n")
    for i, l in enumerate(blocks):
        if re.search(r"recharg|charging|electric|energy infra|alternative fuel|e-mob|polnil|AFIR", l, re.I):
            print("NAPLINE:", " / ".join(x.strip() for x in blocks[max(0, i-1):i+6] if x.strip())[:600])
    links = pg.eval_on_selector_all("a[href*='datasets_details']", "els => els.map(e => e.href)")
    print("NAP detail links:", len(links))
    for l in links:
        pg2 = b.new_page()
        try:
            pg2.goto(l, timeout=30000, wait_until="domcontentloaded"); pg2.wait_for_timeout(800)
            t = pg2.inner_text("body")
            if re.search(r"recharg|charging|polnil|energy infrastructure|AFIR", t, re.I):
                print("NAPDS:", l, "::", re.sub(r"\s+", " ", t)[200:2200])
                for a in pg2.eval_on_selector_all("a[href]", "els => els.map(e => e.href + ' | ' + e.innerText.trim().slice(0,60))"):
                    if re.search(r"code|sample|download|api|login|subscri|naroč", a, re.I):
                        print("   DSLINK:", a)
        except Exception as e:
            print("ERR", l, e)
        pg2.close()

API = "https://api.pun.piattaformaunicanazionale.it/v1"
print("\n#### PUN unsigned")
r = requests.post(API + "/chargepoints/public/map/search", json={"page": 0, "size": 5}, timeout=60)
print(r.status_code, r.text[:300])
ci = boto3.client("cognito-identity", region_name="eu-south-1")
iid = ci.get_id(IdentityPoolId="eu-south-1:e3b2ab05-2046-43dd-8ed0-c0f14c69d507")["IdentityId"]
cr = ci.get_credentials_for_identity(IdentityId=iid)["Credentials"]
sess = boto3.Session(aws_access_key_id=cr["AccessKeyId"], aws_secret_access_key=cr["SecretKey"], aws_session_token=cr["SessionToken"])
creds = sess.get_credentials()
def call(method, path, body=None):
    data = json.dumps(body) if body is not None else None
    req = AWSRequest(method=method, url=API + path, data=data, headers={"Content-Type": "application/json"})
    SigV4Auth(creds, "execute-api", "eu-south-1").add_auth(req)
    r = requests.request(method, API + path, data=data, headers=dict(req.headers), timeout=120)
    return r
r = call("POST", "/chargepoints/public/map/search", {"page": 0, "size": 5})
print("#### signed search", r.status_code, r.text[:600])
ids = [c["evse_id"] for c in r.json()["content"]] + ["IT*TSL*E0DZ6PC", "IT*IOY*E418702"]
r = call("POST", "/chargepoints/group", ids)
print("#### group", r.status_code, len(r.text))
try:
    js = r.json()
    print(json.dumps(js[0], ensure_ascii=False)[:3000])
    print("KEYS:", sorted({k for x in js for k in x.keys()}))
    for x in js:
        print("TARIFFISH:", x.get("evse_id"), {k: v for k, v in x.items() if re.search("tarif|price|cost", k, re.I)})
except Exception as e:
    print("ERR", e, r.text[:500])
for path in ["/tariffs/price-range", "/tariffs?evse_id=IT*TSL*E0DZ6PC", "/tariffs/IT*TSL*E0DZ6PC", "/chargepoints/IT*TSL*E0DZ6PC", "/chargepoints/IT*TSL*E0DZ6PC/tariffs", "/chargepoints/public/IT*TSL*E0DZ6PC"]:
    r = call("GET", path)
    print("####", path, r.status_code, r.text[:800])
r = call("POST", "/chargepoints/public/map/search", {"page": 0, "size": 5, "filters": {"powerTypes": ["DC"]}})
print("#### search total keys", r.status_code, {k: v for k, v in r.json().items() if k != "content"} if r.ok else r.text[:300])
