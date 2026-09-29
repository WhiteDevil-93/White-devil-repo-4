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
import hashlib
import hmac
import json
import os
import secrets
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
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
ENROL_CODE_TTL_S = 15 * 60

_challenges: dict[str, tuple[str, float]] = {}   # device_id -> (nonce, expires)
_tokens: dict[str, tuple[str, float]] = {}       # token_hash -> (device_id, expires)

# Rate limiting. /challenge and /token were unbounded: anyone past Caddy could
# mint nonces forever, or grind signatures against an enrolled device id. Two
# independent buckets per request, because they stop different things -- the
# per-device bucket stops one stolen id being hammered, the per-IP bucket stops
# one host cycling through every enrolled id.
#
# bucket -> (limit, window_seconds)
RATE_LIMITS: dict[str, tuple[int, int]] = {
    "challenge_device": (10, 60),
    "challenge_ip": (30, 60),
    "token_device": (10, 60),
    "token_ip": (30, 60),
    "enrol_code_ip": (10, 60),
}
_rate: dict[str, deque] = {}

# Enrolment codes: short-lived, single-use. Getting past Caddy is no longer
# enough to add a device -- you also need a code that someone already inside
# issued in the last quarter hour.
_enrol_codes: dict[str, float] = {}              # code_hash -> expires

# Which DATA directory the in-memory caches were last loaded from. Tests
# monkeypatch DATA to a per-test tmp_path, so keying the load on the path
# (rather than a plain "already loaded" bool) makes the reload happen by
# itself instead of serving one test's tokens to the next.
_loaded_from: Optional[Path] = None

DEFAULT_CONFIG: dict[str, Any] = {
    # Both default to the permissive setting. Nothing in this module changes
    # how anything authenticates until the operator deliberately flips these.
    "require_enrol_code": False,
    "forward_auth_mode": "permissive",
}
FORWARD_AUTH_MODES = ("permissive", "basic_or_token", "strict")


def _now() -> float:
    return time.time()


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------

def _config_path() -> Path:
    return DATA / "auth_config.json"


def config() -> dict[str, Any]:
    """Effective config. The environment wins over the file, deliberately.

    If the operator sets forward_auth_mode=strict and locks themselves out,
    the file lives on a VM they may no longer be able to reach *through the
    API*. An env var on the systemd unit is reachable over plain SSH, so it
    overrides the file. See docs/DEVICE_AUTH_MIGRATION.md.
    """
    cfg = dict(DEFAULT_CONFIG)
    try:
        stored = json.loads(_config_path().read_text(encoding="utf-8"))
        if isinstance(stored, dict):
            for k in DEFAULT_CONFIG:
                if k in stored:
                    cfg[k] = stored[k]
    except (FileNotFoundError, ValueError, OSError):
        pass

    env_code = os.environ.get("HUB_REQUIRE_ENROL_CODE")
    if env_code is not None:
        cfg["require_enrol_code"] = env_code.strip().lower() in ("1", "true", "yes", "on")
    env_mode = (os.environ.get("HUB_FORWARD_AUTH_MODE") or "").strip().lower()
    if env_mode in FORWARD_AUTH_MODES:
        cfg["forward_auth_mode"] = env_mode
    return cfg


def _save_config(patch: dict[str, Any]) -> dict[str, Any]:
    DATA.mkdir(parents=True, exist_ok=True)
    try:
        stored = json.loads(_config_path().read_text(encoding="utf-8"))
        if not isinstance(stored, dict):
            stored = {}
    except (FileNotFoundError, ValueError, OSError):
        stored = {}
    stored.update(patch)
    _atomic_write_json(_config_path(), stored)
    return config()


# ---------------------------------------------------------------------------
# Atomic persistence -- the tmp-file + os.replace-under-a-lock pattern from
# hub/agentic/store.py:150, so a crash mid-write can never leave a truncated
# file where a valid one used to be.
# ---------------------------------------------------------------------------

def _atomic_write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with _LOCK:
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        os.replace(tmp, path)


def _tokens_path() -> Path:
    return DATA / "tokens.json"


def _codes_path() -> Path:
    return DATA / "enrol_codes.json"


def _load_state() -> None:
    """Rehydrate tokens and enrolment codes from disk, once per DATA dir.

    Tokens used to live only in a module-level dict, so every hub restart --
    including an unattended `systemctl restart forge-hub` after a deploy --
    logged every device out and demanded a fresh fingerprint prompt on a phone
    that might be nowhere near the operator. Only SHA-256 hashes are stored,
    so reading the file gives you nothing you can present as a bearer token.
    """
    global _loaded_from
    if _loaded_from == DATA:
        return
    _loaded_from = DATA
    now = _now()

    try:
        raw = json.loads(_tokens_path().read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError, OSError):
        raw = {}
    if isinstance(raw, dict):
        for key, value in raw.items():
            try:
                device_id, expires = value
                if float(expires) > now:
                    _tokens[str(key)] = (str(device_id), float(expires))
            except (TypeError, ValueError):
                continue

    try:
        raw = json.loads(_codes_path().read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError, OSError):
        raw = {}
    if isinstance(raw, dict):
        for key, value in raw.items():
            try:
                if float(value) > now:
                    _enrol_codes[str(key)] = float(value)
            except (TypeError, ValueError):
                continue


def _save_tokens() -> None:
    with _LOCK:
        now = _now()
        for th, (_did, exp) in list(_tokens.items()):
            if exp <= now:
                _tokens.pop(th, None)
        payload = {th: [did, exp] for th, (did, exp) in _tokens.items()}
    _atomic_write_json(_tokens_path(), payload)


def _save_codes() -> None:
    with _LOCK:
        now = _now()
        for ch, exp in list(_enrol_codes.items()):
            if exp <= now:
                _enrol_codes.pop(ch, None)
        payload = dict(_enrol_codes)
    _atomic_write_json(_codes_path(), payload)


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

def _client_ip(request: Optional[Request]) -> str:
    """The caller's address as Caddy saw it.

    uvicorn binds 127.0.0.1, so request.client.host is always the proxy itself
    and useless as a bucket key. The real address is whatever Caddy put in
    X-Forwarded-For; taking the first hop is safe here precisely because
    nothing but Caddy can reach the socket.
    """
    if request is None:
        return "unknown"
    xff = request.headers.get("x-forwarded-for", "")
    if xff:
        first = xff.split(",")[0].strip()
        if first:
            return first
    return request.client.host if request.client else "unknown"


def _rate_check(bucket: str, subject: str) -> None:
    """Raise 429 if `subject` has spent its allowance in `bucket`."""
    limit, window = RATE_LIMITS[bucket]
    key = bucket + ":" + subject
    now = _now()
    with _LOCK:
        hits = _rate.setdefault(key, deque())
        while hits and hits[0] <= now - window:
            hits.popleft()
        if len(hits) >= limit:
            retry_after = max(1, int(hits[0] + window - now) + 1)
            raise HTTPException(
                429,
                f"Too many requests: {limit} per {window}s for this {bucket.rsplit('_', 1)[1]}. "
                f"Try again in {retry_after}s.",
                headers={"Retry-After": str(retry_after)},
            )
        hits.append(now)


def reset_rate_limits() -> None:
    """Escape hatch for the operator (and for tests) -- see the migration doc."""
    with _LOCK:
        _rate.clear()


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


def _basic_credential() -> Optional[tuple[str, str]]:
    """The hub's own copy of the relay password, as (user, sha256-hex), or None.

    Caddy holds the only copy today. Once Caddy stops doing basic auth, *something*
    has to still verify it during migration, or the fallback is not a fallback --
    it is "any Basic header at all". Sourced from the environment first so the
    operator can set it on the systemd unit without writing a secret to disk.
    """
    user = os.environ.get("HUB_BASIC_AUTH_USER")
    password = os.environ.get("HUB_BASIC_AUTH_PASSWORD")
    if user and password:
        return user, hashlib.sha256(password.encode("utf-8")).hexdigest()
    try:
        stored = json.loads((DATA / "basic_auth.json").read_text(encoding="utf-8"))
    except (FileNotFoundError, ValueError, OSError):
        return None
    if isinstance(stored, dict) and stored.get("user") and stored.get("sha256"):
        return str(stored["user"]), str(stored["sha256"])
    return None


def _check_basic(header: str) -> bool:
    """True if this Authorization: Basic header matches the hub's credential."""
    cred = _basic_credential()
    if not cred:
        return False
    try:
        decoded = base64.b64decode(header.split(" ", 1)[1], validate=True).decode("utf-8")
        user, _, password = decoded.partition(":")
    except Exception:
        return False
    want_user, want_hash = cred
    got_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
    # Both compared in constant time; `and` would short-circuit on the username
    # and leak which half was wrong through timing.
    ok_user = hmac.compare_digest(user, want_user)
    ok_pass = hmac.compare_digest(got_hash, want_hash)
    return ok_user and ok_pass


def _deny(detail: str, offer_basic: bool) -> HTTPException:
    headers = {"WWW-Authenticate": 'Basic realm="forge-hub"'} if offer_basic else {}
    return HTTPException(401, detail, headers=headers)


def _decide(authorization: str) -> dict[str, Any]:
    """The single acceptance decision, shared by forward_auth and require_admin.

    Three modes, so the switch from "Caddy checks a shared password" to "FastAPI
    checks a device token" is not one all-or-nothing cutover:

      permissive    (default) a device token, OR any Basic header. Correct ONLY
                    while Caddy still runs basic_auth in front -- Caddy has
                    already rejected a wrong password, so anything arriving here
                    with a Basic header was vouched for. This is the mode that
                    makes the migration reversible, and the default keeps this
                    whole module additive.
      basic_or_token  a device token, OR a Basic header the hub verifies itself
                    against HUB_BASIC_AUTH_USER/PASSWORD. The mode to run in
                    while Caddy's basic_auth is gone but the desktop app and
                    browser still have no device token.
      strict        a device token, nothing else.

    Raises HTTPException on refusal; returns a description of who got in.
    """
    mode = config().get("forward_auth_mode", "permissive")
    if mode not in FORWARD_AUTH_MODES:
        mode = "permissive"

    if authorization.startswith("Bearer "):
        device_id = resolve_token(authorization[len("Bearer "):])
        # A token is not enough on its own: the device must still be enrolled.
        # revoke() purges live tokens, but checking here means a token that
        # somehow outlived its device (a hand-edited devices.json, a restore
        # from an older tokens.json) is still refused.
        device = _find(device_id) if device_id else None
        if not device:
            raise _deny("That device token is invalid, expired, or revoked.", mode != "strict")
        return {"ok": True, "method": "device-token", "device_id": device.get("id"),
                "name": device.get("name"), "mode": mode}

    if authorization.startswith("Basic "):
        if mode == "strict":
            raise _deny(
                "Basic auth is no longer accepted (forward_auth_mode=strict). "
                "Enrol this device and present a device token.",
                False,
            )
        if mode == "permissive":
            return {"ok": True, "method": "basic-passthrough", "mode": mode}
        # basic_or_token
        if not _basic_credential():
            # Refusing loudly beats silently degrading to permissive: a
            # misconfigured relay that waves everything through is exactly the
            # failure this endpoint exists to prevent.
            raise HTTPException(
                503,
                "forward_auth_mode=basic_or_token but the hub has no basic-auth "
                "credential. Set HUB_BASIC_AUTH_USER and HUB_BASIC_AUTH_PASSWORD, "
                "or drop back to forward_auth_mode=permissive.",
            )
        if not _check_basic(authorization):
            raise _deny("Bad relay username or password.", True)
        return {"ok": True, "method": "basic", "mode": mode}

    if mode == "permissive":
        # Caddy's basic_auth is still in front in this mode, but a request with
        # no Authorization header at all never passed it -- so this is only
        # reachable locally (127.0.0.1:9000) or if the operator has already
        # removed basic_auth without moving off permissive, which the migration
        # doc says not to do.
        raise _deny("A device token or the relay password is required.", True)
    raise _deny(
        "A device token is required." if mode == "strict"
        else "A device token or the relay password is required.",
        mode != "strict",
    )


def require_admin(authorization: str = Header(default="")) -> dict[str, Any]:
    """Guard for the endpoints that can change who gets in.

    /api/auth/* has to sit OUTSIDE Caddy's forward_auth -- a device cannot
    present a token before it has one -- so once Caddy stops doing basic auth,
    revoke and config would otherwise be reachable by anyone who found the URL.
    Under `permissive` this is a no-op (Caddy is still checking the password in
    front), which is what keeps the change additive.
    """
    if config().get("forward_auth_mode", "permissive") == "permissive":
        return {"ok": True, "method": "edge", "mode": "permissive"}
    return _decide(authorization)


@router.get("/forward")
def forward_auth(response: Response, authorization: str = Header(default="")):
    """Caddy's `forward_auth` target. 2xx lets the request through, 401 stops it.

    Success echoes the device back in headers so Caddy can `copy_headers` them
    upstream and the hub can log which device did what.
    """
    verdict = _decide(authorization)
    if verdict.get("device_id"):
        response.headers["X-Device-Id"] = str(verdict["device_id"])
        response.headers["X-Device-Name"] = str(verdict.get("name") or "")
    response.headers["X-Auth-Method"] = str(verdict["method"])
    return verdict


def _hash_code(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


@router.post("/enrol-code")
def issue_enrol_code(request: Request, _admin: dict = Depends(require_admin)):
    """Mint a short-lived, single-use enrolment code.

    Whoever holds the relay password can reach this, which is the point: the
    password alone no longer enrols a device, because a code is only valid for
    ENROL_CODE_TTL_S and dies the moment it is spent. An attacker with a leaked
    password would have to both issue and spend one inside that window, and the
    result is a device in GET /api/auth/devices that the operator did not add.
    """
    _rate_check("enrol_code_ip", _client_ip(request))
    _ensure()
    _load_state()
    raw = secrets.token_urlsafe(9)
    with _LOCK:
        _enrol_codes[_hash_code(raw)] = _now() + ENROL_CODE_TTL_S
    _save_codes()
    return {"code": raw, "expires_in": ENROL_CODE_TTL_S}


def _consume_enrol_code(raw: Optional[str]) -> None:
    """Spend a code, or raise. A no-op while the gate is off.

    The entry is popped *before* it is checked for expiry, so an expired code
    cannot be retried and two concurrent enrolments cannot spend the same one.
    """
    if not config().get("require_enrol_code"):
        return
    _load_state()
    if not raw:
        raise HTTPException(
            403,
            "An enrolment code is required. Issue one from an already-trusted "
            "session: POST /api/auth/enrol-code",
        )
    ch = _hash_code(raw)
    with _LOCK:
        expires = _enrol_codes.pop(ch, None)
    _save_codes()
    if expires is None:
        raise HTTPException(403, "That enrolment code is unknown or has already been used.")
    if _now() > expires:
        raise HTTPException(403, "That enrolment code has expired; issue a new one.")


class EnrolIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=1, max_length=4000)
    # Optional so the already-enrolled phone and the current Android build keep
    # working. It only becomes mandatory once require_enrol_code is turned on.
    enrol_code: Optional[str] = Field(default=None, max_length=128)


@router.post("/devices")
def enrol(body: EnrolIn):
    """Register a device's public key. Guarded by whatever already fronts the hub."""
    _load_public_key(body.public_key_pem)  # validate before storing
    _consume_enrol_code(body.enrol_code)
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
def revoke(device_id: str, _admin: dict = Depends(require_admin)):
    """Revoke one device without touching the others — the point of the exercise."""
    data = _read()
    devices = data.get("devices", [])
    kept = [d for d in devices if d.get("id") != device_id]
    if len(kept) == len(devices):
        raise HTTPException(404, f"No enrolled device with id {device_id}.")
    data["devices"] = kept
    _write(data)
    _load_state()
    with _LOCK:
        _challenges.pop(device_id, None)
        for th, (did, _exp) in list(_tokens.items()):
            if did == device_id:
                _tokens.pop(th, None)
    # Without this the purge lived only in memory: a restart would rehydrate
    # the revoked device's token straight back out of tokens.json.
    _save_tokens()
    return {"ok": True, "revoked": device_id, "remaining": len(kept)}


class ChallengeIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)


@router.post("/challenge")
def challenge(body: ChallengeIn, request: Request):
    # Counted before the lookup, so probing for valid device ids is bounded
    # too -- otherwise the 404/200 split is a free enumeration oracle.
    _rate_check("challenge_ip", _client_ip(request))
    _rate_check("challenge_device", body.device_id)
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
def token(body: TokenIn, request: Request):
    """Exchange a signature over the outstanding challenge for a short-lived token."""
    # Counted before verification, so *failed* signature attempts burn the
    # allowance. Counting only successes would leave the grind unbounded.
    _rate_check("token_ip", _client_ip(request))
    _rate_check("token_device", body.device_id)
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
    _load_state()
    with _LOCK:
        _tokens[_hash_token(raw)] = (body.device_id, _now() + TOKEN_TTL_S)
    _save_tokens()
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
    _load_state()
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


# ---------------------------------------------------------------------------
# Forward auth -- the endpoint Caddy calls instead of doing basic auth itself
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Config endpoints -- how the operator throws the switch, and unthrows it
# ---------------------------------------------------------------------------

class ConfigIn(BaseModel):
    require_enrol_code: Optional[bool] = None
    forward_auth_mode: Optional[str] = None


@router.get("/config")
def get_config():
    cfg = config()
    return {
        **cfg,
        "devices_enrolled": len(list_devices()),
        "basic_credential_configured": _basic_credential() is not None,
        "modes": list(FORWARD_AUTH_MODES),
    }


@router.post("/config")
def set_config(body: ConfigIn, _admin: dict = Depends(require_admin)):
    patch: dict[str, Any] = {}
    if body.require_enrol_code is not None:
        patch["require_enrol_code"] = bool(body.require_enrol_code)
    if body.forward_auth_mode is not None:
        mode = body.forward_auth_mode.strip().lower()
        if mode not in FORWARD_AUTH_MODES:
            raise HTTPException(400, f"forward_auth_mode must be one of {FORWARD_AUTH_MODES}.")
        # The lockout guard. Turning off the password fallback with nothing
        # enrolled to replace it bricks the API from every device at once, and
        # the operator is usually nowhere near the laptop when they try it.
        if mode != "permissive" and not list_devices():
            raise HTTPException(
                409,
                f"Refusing forward_auth_mode={mode}: no devices are enrolled, so "
                "nothing could authenticate afterwards. Enrol a device first.",
            )
        patch["forward_auth_mode"] = mode
    if not patch:
        raise HTTPException(400, "Nothing to set.")
    _ensure()
    return _save_config(patch)
