"""Locks and unlocks files with the TV key (LINK_KEY), the same way the apps do.

  seal.py open  IN OUT        unlock a file made by "seal"
  seal.py seal  IN OUT        lock a file for the apps (AES-GCM: 12-byte iv + data)
  seal.py cbc   IN OUT        lock a file for the laptop script (AES-CBC + HMAC-SHA256, which Windows PowerShell can open)
The key comes from the LINK_KEY environment variable.
"""
import hashlib, hmac, os, sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding

secret = os.environ["LINK_KEY"].strip()
gcm_key = hashlib.sha256(("lrh-key:" + secret).encode()).digest()
mode, src, dst = sys.argv[1:4]
data = open(src, "rb").read()
if mode == "open":
    out = AESGCM(gcm_key).decrypt(data[:12], data[12:], None)
elif mode == "seal":
    iv = os.urandom(12); out = iv + AESGCM(gcm_key).encrypt(iv, data, None)
elif mode == "cbc":
    enc = hashlib.sha256(("lrh-file-enc:" + secret).encode()).digest()
    mac = hashlib.sha256(("lrh-file-mac:" + secret).encode()).digest()
    iv = os.urandom(16); p = padding.PKCS7(128).padder(); body = p.update(data) + p.finalize()
    e = Cipher(algorithms.AES(enc), modes.CBC(iv)).encryptor(); ct = iv + e.update(body) + e.finalize()
    out = ct + hmac.new(mac, ct, hashlib.sha256).digest()
else:
    sys.exit("unknown mode " + mode)
open(dst, "wb").write(out)
