"""Device-bound authentication: one keypair per device instead of one shared password.

Today every client sends the same relay password (Caddy basic auth), so the hub
cannot tell the phone from the laptop from whoever copied relay_access.txt, and a
single leak means rotating the password everywhere. Here each device registers a
PUBLIC key once. The private key never leaves that device's secure hardware
(Android StrongBox, Windows TPM via Hello) and is unusable without the user's
fingerprint or PIN — so possession of the laptop is not enough.

ES256 (ECDSA P-256) because it is the one algorithm both Android StrongBox and
the Windows TPM expose; Ed25519 would be nicer but is not reliably available on
either.

ADDITIVE ON PURPOSE. Nothing here changes how existing endpoints authenticate.
Enrolment is protected by whatever already guards the hub (Caddy basic auth), and
tokens issued here are enforced only where require_device is applied. Flipping
Caddy off basic auth is a separate, deliberate step, taken once both devices are
enrolled and verified — otherwise the phone locks itself out.
"""
from __future__ import annotations

import base64
import json
import os
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/auth")

DATA = Path.home() / "hub" / "auth_data"
DEVICES = DATA / "devices.json"
_LOCK = threading.Lock()

# A challenge is short-lived and single-use: it only has to survive one round
# trip. Held in memory, so a hub restart invalidates outstanding challenges and
# the client simply asks for another.
CHALLENGE_TTL_S = 120
TOKEN_TTL_S = 12 * 3600
_challenges: dict[str, tuple[str, float]] = {}   # device_id -> (nonce, expires)
_tokens: dict[str, tuple[str, float]] = {}       # token_hash -> (device_id, expires)


def _now() -> float:
    return time.time()


def _ensure() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    if not DEVICES.exists():
        _write({"devices": []})


def _read() -> dict[str, Any]:
    try:
        data = json.loads(DEVICES.read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError, OSError):
        return {"devices": []}
    return data if isinstance(data, dict) else {"devices": []}


def _write(payload: dict[str, Any]) -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    tmp = DEVICES.with_suffix(".json.tmp")
    with _LOCK:
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, DEVICES)


def list_devices() -> list[dict[str, Any]]:
    """Public view: never returns key material."""
    out = []
    for d in _read().get("devices", []):
        out.append({
            "id": d.get("id"),
            "name": d.get("name"),
            "created": d.get("created"),
            "last_seen": d.get("last_seen"),
        })
    return out


def _find(device_id: str) -> Optional[dict[str, Any]]:
    for d in _read().get("devices", []):
        if d.get("id") == device_id:
            return d
    return None


def _load_public_key(pem: str):
    """Parse a PEM public key and identify its algorithm.

    Two are allowed because the platforms differ and neither is negotiable:
      ES256 — Android Keystore/StrongBox issues ECDSA P-256.
      RS256 — Windows Hello (KeyCredentialManager) issues RSA-2048 and signs
              PKCS#1 v1.5 / SHA-256. It cannot be made to produce P-256.
    Anything else is refused rather than guessed at.

    Returns (key, alg).
    """
    try:
        from cryptography.hazmat.primitives.asymmetric import ec, rsa
        from cryptography.hazmat.primitives.serialization import load_pem_public_key
    except ImportError:  # pragma: no cover - depends on the deployed venv
        raise HTTPException(
            500,
            "The hub is missing the 'cryptography' package; install requirements.txt on the relay.",
        )
    try:
        key = load_pem_public_key(pem.encode("utf-8"))
    except Exception as e:
        raise HTTPException(400, f"That does not parse as a PEM public key: {e}")

    if isinstance(key, ec.EllipticCurvePublicKey):
        if key.curve.name != "secp256r1":
            raise HTTPException(400, f"Unsupported curve {key.curve.name}; use P-256 (secp256r1).")
        return key, "ES256"
    if isinstance(key, rsa.RSAPublicKey):
        if key.key_size < 2048:
            raise HTTPException(400, f"RSA key too small ({key.key_size} bits); 2048 is the minimum.")
        return key, "RS256"
    raise HTTPException(400, "Public key must be ECDSA P-256 (Android) or RSA-2048+ (Windows Hello).")


class EnrolIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=1, max_length=4000)


@router.post("/devices")
def enrol(body: EnrolIn):
    """Register a device's public key. Guarded by whatever already fronts the hub."""
    _load_public_key(body.public_key_pem)  # validate before storing
    _ensure()
    data = _read()
    devices = data.get("devices", [])
    if any(d.get("public_key_pem") == body.public_key_pem for d in devices):
        raise HTTPException(409, "That public key is already enrolled.")
    device = {
        "id": secrets.token_hex(8),
        "name": body.name.strip(),
        "public_key_pem": body.public_key_pem,
        "created": _now(),
        "last_seen": None,
    }
    devices.append(device)
    data["devices"] = devices
    _write(data)
    return {"id": device["id"], "name": device["name"], "created": device["created"]}


@router.get("/devices")
def get_devices():
    return {"devices": list_devices()}


@router.delete("/devices/{device_id}")
def revoke(device_id: str):
    """Revoke one device without touching the others — the point of the exercise."""
    data = _read()
    devices = data.get("devices", [])
    kept = [d for d in devices if d.get("id") != device_id]
    if len(kept) == len(devices):
        raise HTTPException(404, f"No enrolled device with id {device_id}.")
    data["devices"] = kept
    _write(data)
    with _LOCK:
        _challenges.pop(device_id, None)
        for th, (did, _exp) in list(_tokens.items()):
            if did == device_id:
                _tokens.pop(th, None)
    return {"ok": True, "revoked": device_id, "remaining": len(kept)}


class ChallengeIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)


@router.post("/challenge")
def challenge(body: ChallengeIn):
    if not _find(body.device_id):
        raise HTTPException(404, "Unknown device. Enrol it first.")
    nonce = secrets.token_urlsafe(32)
    expires = _now() + CHALLENGE_TTL_S
    with _LOCK:
        _challenges[body.device_id] = (nonce, expires)
    return {"nonce": nonce, "expires_in": CHALLENGE_TTL_S}


class TokenIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)
    signature_b64: str = Field(min_length=1, max_length=2000)


@router.post("/token")
def token(body: TokenIn):
    """Exchange a signature over the outstanding challenge for a short-lived token."""
    device = _find(body.device_id)
    if not device:
        raise HTTPException(404, "Unknown device. Enrol it first.")
    with _LOCK:
        entry = _challenges.pop(body.device_id, None)  # single use, win or lose
    if not entry:
        raise HTTPException(400, "No outstanding challenge for this device; request one first.")
    nonce, expires = entry
    if _now() > expires:
        raise HTTPException(400, "Challenge expired; request a new one.")

    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec, padding

    key, alg = _load_public_key(device["public_key_pem"])
    try:
        signature = base64.b64decode(body.signature_b64, validate=True)
    except Exception:
        raise HTTPException(400, "signature_b64 is not valid base64.")
    try:
        if alg == "ES256":
            key.verify(signature, nonce.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        else:
            # RS256: PKCS#1 v1.5 over SHA-256 — exactly what Windows Hello's
            # KeyCredential.RequestSignAsync returns.
            key.verify(signature, nonce.encode("utf-8"), padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature:
        raise HTTPException(401, "Signature does not match this device's enrolled key.")

    raw = secrets.token_urlsafe(32)
    with _LOCK:
        _tokens[_hash_token(raw)] = (body.device_id, _now() + TOKEN_TTL_S)
    data = _read()
    for d in data.get("devices", []):
        if d.get("id") == body.device_id:
            d["last_seen"] = _now()
    _write(data)
    return {"token": raw, "expires_in": TOKEN_TTL_S, "device_id": body.device_id}


def _hash_token(raw: str) -> str:
    import hashlib

    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def resolve_token(raw: Optional[str]) -> Optional[str]:
    """Device id for a bearer token, or None. Expired tokens are dropped on read."""
    if not raw:
        return None
    th = _hash_token(raw)
    with _LOCK:
        entry = _tokens.get(th)
        if not entry:
            return None
        device_id, expires = entry
        if _now() > expires:
            _tokens.pop(th, None)
            return None
    return device_id


def require_device(authorization: str = Header(default="")) -> str:
    """FastAPI dependency enforcing a device token. Not yet applied to any route —
    it goes on once both devices are enrolled and Caddy stops doing basic auth."""
    prefix = "Bearer "
    raw = authorization[len(prefix):] if authorization.startswith(prefix) else ""
    device_id = resolve_token(raw)
    if not device_id:
        raise HTTPException(401, "A valid device token is required.")
    return device_id


@router.get("/whoami")
def whoami(authorization: str = Header(default="")):
    """Lets a client check its token without a protected route existing yet."""
    prefix = "Bearer "
    raw = authorization[len(prefix):] if authorization.startswith(prefix) else ""
    device_id = resolve_token(raw)
    if not device_id:
        return {"authenticated": False, "devices_enrolled": len(list_devices())}
    device = _find(device_id) or {}
    return {"authenticated": True, "device_id": device_id, "name": device.get("name")}
