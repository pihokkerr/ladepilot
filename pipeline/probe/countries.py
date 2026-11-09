"""Probe 4: NAP.si charging samples + PUN tariff coverage."""
import json, re, collections
import boto3, requests
from botocore.auth import SigV4Auth
from botocore.awsrequest import AWSRequest
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    b = p.chromium.launch(); pg = b.new_page()
    for ds in ["46963663-38dd-eb04-43a9-cca9bdc0e4ba", "acc8a643-9dac-ecad-58da-0ce20f88f4bd"]:
        pg.goto(f"https://www.nap.si/en/dataset_code?id={ds}&data=codesample", timeout=60000, wait_until="networkidle"); pg.wait_for_timeout(2000)
        t = pg.inner_text("body")
        i = t.find("<")
        print("SISAMPLE", ds, len(t), "::", re.sub(r"\s+", " ", t[i:i+5000]))
        for kw in ["energyRate", "energyPrice", "pricePerKWh", "value>", "operator"]:
            print("   count", kw, t.count(kw))

API = "https://api.pun.piattaformaunicanazionale.it/v1"
ci = boto3.client("cognito-identity", region_name="eu-south-1")
iid = ci.get_id(IdentityPoolId="eu-south-1:e3b2ab05-2046-43dd-8ed0-c0f14c69d507")["IdentityId"]
cr = ci.get_credentials_for_identity(IdentityId=iid)["Credentials"]
creds = boto3.Session(aws_access_key_id=cr["AccessKeyId"], aws_secret_access_key=cr["SecretKey"], aws_session_token=cr["SessionToken"]).get_credentials()
def call(method, path, body=None):
    data = json.dumps(body) if body is not None else None
    req = AWSRequest(method=method, url=API + path, data=data, headers={"Content-Type": "application/json"})
    SigV4Auth(creds, "execute-api", "eu-south-1").add_auth(req)
    return requests.request(method, API + path, data=data, headers=dict(req.headers), timeout=180)
r = call("POST", "/chargepoints/public/map/search", {"page": 0, "size": 12000})
ids = [c["evse_id"] for c in r.json()["content"]]
print("page0", len(ids))
stats = collections.Counter(); samples = []
for k in range(0, 3000, 500):
    g = call("POST", "/chargepoints/group", ids[k:k+500])
    print("group", k, g.status_code, len(g.text))
    for x in g.json():
        t = x.get("punTariffsDetails") or {}
        has = any(v for tt in t.values() if tt for v in tt.values())
        stats["evse"] += 1; stats["tariff"] += has
        if has and len(samples) < 6:
            samples.append((x["evse_id"], x.get("businessName"), t, [c.get("max_electric_power") for c in x.get("connectors", [])]))
print("STATS", dict(stats))
for s in samples: print("TSAMPLE", json.dumps(s, ensure_ascii=False)[:800])
print("LOCSAMPLE", json.dumps(g.json()[0]["location"], ensure_ascii=False)[:1200])
