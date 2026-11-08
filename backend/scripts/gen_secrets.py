"""
Generate the secrets this app needs. Run once on your computer:

    pip install cryptography
    python backend/scripts/gen_secrets.py

Copy the output into Render → Environment. Never commit these values.
"""

import base64
import secrets

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


key = ec.generate_private_key(ec.SECP256R1())
private_raw = key.private_numbers().private_value.to_bytes(32, "big")
public_raw = key.public_key().public_bytes(Encoding.X962, PublicFormat.UncompressedPoint)

print(f"VAPID_PRIVATE_KEY={b64url(private_raw)}")
print(f"VAPID_PUBLIC_KEY={b64url(public_raw)}")
print(f"CRON_SECRET={secrets.token_urlsafe(32)}")
print(f"ACTION_SECRET={secrets.token_urlsafe(32)}")
