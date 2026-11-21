"""Reads what the TV last told the phone (from the relay), unlocks it with LINK_KEY and prints a summary.
Only for checking from GitHub that the TV is up to date; nothing is sent to the TV."""
import hashlib, json, os, ssl, sys, time
import paho.mqtt.client as mqtt
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

secret = os.environ["LINK_KEY"].strip()
key = hashlib.sha256(("lrh-key:" + secret).encode()).digest()
base = "lrh/" + hashlib.sha256(("lrh-topic:" + secret).encode()).hexdigest()[:24]
got = {}

def on_message(c, u, m):
    got[m.topic.split("/")[-1]] = m.payload

c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="lrh-probe-%d" % int(time.time()))
c.tls_set(cert_reqs=ssl.CERT_REQUIRED)
c.on_message = on_message
c.on_connect = lambda c, u, f, rc, p=None: c.subscribe(base + "/#")
c.connect("broker.hivemq.com", 8883, 30)
c.loop_start(); time.sleep(10); c.loop_stop()

out = {"topics": sorted(got)}
def opened(b):
    try: return AESGCM(key).decrypt(b[:12], b[12:], None)
    except Exception: return None
if "on" in got: out["online"] = got["on"].decode(errors="replace")
for t in ("state", "tvset", "found", "inst"):
    if t in got:
        p = opened(got[t]); out[t] = json.loads(p) if p else "could not unlock"
if "apps" in got:
    p = opened(got["apps"])
    if p:
        a = json.loads(p); out["apps"] = [(x.get("label"), x.get("on")) for x in a]
if "snap" in got:
    p = opened(got["snap"])
    if p:
        z = p.index(b"\0"); out["snap"] = p[:z].decode()
        open("snap.jpg", "wb").write(p[z + 1:])
print(json.dumps(out, indent=1)[:20000])
