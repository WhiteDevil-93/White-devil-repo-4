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

Deployment hardening (see docs/DEVICE_AUTH_MIGRATION.md):
  * /challenge, /token and enrolment are throttled per IP and per device, with 429s.
  * Enrolment needs a short-lived single-use code minted on the VM (CLI) or by an
    already-enrolled device — the shared password alone cannot enrol anything.
  * /api/auth/verify is a Caddy forward_auth target: device token, or (while
    HUB_AUTH_MODE=permissive, the default) the legacy basic-auth password.
  * Issued tokens survive a hub restart.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import secrets
import threading
import time
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
from pydantic import BaseModel, Field

from ratelimit import Throttle

# Child of uvicorn's logger so INFO reaches journald; a bare logger would drop it (root is WARNING).
log = logging.getLogger("uvicorn.error.hub.auth")

router = APIRouter(prefix="/api/auth")

DATA = Path.home() / "hub" / "auth_data"
DEVICES = DATA / "devices.json"
# Re-entrant: read-modify-write of devices.json / tokens.json holds it across _write.
_LOCK = threading.RLock()

# A challenge is short-lived and single-use: it only has to survive one round
# trip. Held in memory, so a hub restart invalidates outstanding challenges and
# the client simply asks for another.
CHALLENGE_TTL_S = 120
TOKEN_TTL_S = 12 * 3600
_challenges: dict[str, tuple[str, float]] = {}   # device_id -> (nonce, expires)
_tokens: dict[str, tuple[str, float]] = {}       # token_hash -> (device_id, expires)
_tokens_src: Optional[Path] = None               # file _tokens was last loaded from

ENROL_CODE_TTL_S = 10 * 60
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O/1/I
_CODE_LEN = 10                                        # ~50 bits

# Throttling. (limit, window_s) for rate limits; (max_fails, window_s, lock_s) for lockouts.
RATE_CHALLENGE_IP = (30, 60)
RATE_CHALLENGE_DEVICE = (10, 60)
RATE_TOKEN_IP = (20, 60)
RATE_ENROL_IP = (10, 60)
RATE_MINT_DEVICE = (5, 60)
LOCK_DEVICE_IP = (5, 600, 900)     # bad signatures for one device from one IP
LOCK_IP = (20, 600, 900)           # any failed credential from one IP
LOCK_CODE_GLOBAL = (5, 600, 300)   # bad enrolment codes from anywhere; also voids all live codes

BASIC_CACHE_TTL_S = 300

def _now() -> float:
    return time.time()


_throttle = Throttle(lambda: _now())  # late-bound so tests can move the clock via _now


def _too_many(wait: int, what: str) -> HTTPException:
    return HTTPException(
        429,
        f"Too many {what}; retry in {wait} seconds.",
        headers={"Retry-After": str(wait)},
    )


def _client_ip(request: Request) -> str:
    """The real client, not Caddy. X-Forwarded-For is honoured only when the socket peer
    is loopback (i.e. it is Caddy talking to us), and only its LAST entry is used: that
    is the one Caddy itself appended, whereas earlier entries are client-supplied."""
    peer = request.client.host if request.client else "unknown"
    if peer in ("127.0.0.1", "::1", "localhost"):
        xff = request.headers.get("x-forwarded-for", "")
        if xff.strip():
            return xff.split(",")[-1].strip() or peer
    return peer


def _rate(key: str, cfg: tuple[int, int], what: str) -> None:
    wait = _throttle.hit(key, cfg[0], cfg[1])
    if wait:
        raise _too_many(wait, what)


def _check_locked(*keys: str) -> None:
    wait = max((_throttle.locked_for(k) for k in keys), default=0)
    if wait:
        raise _too_many(wait, "failed attempts")


def _fail(key: str, cfg: tuple[int, int, int]) -> None:
    _throttle.fail(key, *cfg)


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


def _atomic_write(path: Path, payload: Any) -> None:
    """tmp file + os.replace, as in agentic/store.py, so a crash mid-write never leaves a
    half-written file. Owner-only permissions where the OS honours them."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with _LOCK:
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:  # pragma: no cover - Windows
            pass
        os.replace(tmp, path)


def _write(payload: dict[str, Any]) -> None:
    _atomic_write(DEVICES, payload)


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


# --------------------------------------------------------------------------- persistence
# Tokens are stored as SHA-256 hashes (never the bearer value), so a copy of tokens.json
# cannot be replayed. One file, rewritten atomically on issue/revoke.

def _tokens_path() -> Path:
    return DATA / "tokens.json"


def _tokens_sync() -> None:
    """Load tokens from disk the first time (or when DATA moves). Caller holds _LOCK."""
    global _tokens_src
    path = _tokens_path()
    if _tokens_src == path:
        return
    _tokens.clear()
    try:
        raw = json.loads(path.read_text(encoding="utf-8")).get("tokens", {})
        now = _now()
        for th, pair in raw.items():
            did, exp = pair
            if isinstance(did, str) and isinstance(exp, (int, float)) and exp > now:
                _tokens[th] = (did, float(exp))
    except FileNotFoundError:
        pass
    except (ValueError, OSError, TypeError, AttributeError) as e:
        # Unreadable file: everyone signs in again rather than the hub failing to boot.
        log.warning("ignoring unreadable %s: %s", path, e)
    _tokens_src = path


def _tokens_save() -> None:
    """Persist live tokens. Caller holds _LOCK."""
    now = _now()
    for th, (_did, exp) in list(_tokens.items()):
        if exp <= now:
            _tokens.pop(th, None)
    _atomic_write(_tokens_path(), {"tokens": {th: [d, e] for th, (d, e) in _tokens.items()}})


# --------------------------------------------------------------------------- enrolment codes
# One file per code, named by the code's hash. Minting is create; consuming is unlink,
# which the OS makes succeed for exactly one caller, so two racing enrolments cannot
# both spend the same code. The CLI (a separate process) and the server share the
# directory with no further coordination.

def _codes_dir() -> Path:
    return DATA / "enrol_codes"


def _normalise_code(code: str) -> str:
    return "".join(c for c in code.upper() if c.isalnum())


def _code_path(code: str) -> Path:
    return _codes_dir() / (hashlib.sha256(_normalise_code(code).encode()).hexdigest() + ".json")


def mint_enrol_code(minted_by: str = "cli", ttl_s: int = ENROL_CODE_TTL_S) -> str:
    """Create a single-use enrolment code, valid for ttl_s. Returns it as XXXXX-XXXXX."""
    raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN))
    now = _now()
    _prune_codes()
    _atomic_write(_code_path(raw), {"created": now, "expires": now + ttl_s, "by": minted_by})
    return f"{raw[:5]}-{raw[5:]}"


def _prune_codes() -> None:
    now = _now()
    d = _codes_dir()
    if not d.is_dir():
        return
    for p in d.glob("*.json"):
        try:
            if json.loads(p.read_text(encoding="utf-8")).get("expires", 0) <= now:
                p.unlink(missing_ok=True)
        except (ValueError, OSError):
            p.unlink(missing_ok=True)


def _void_all_codes() -> None:
    d = _codes_dir()
    if d.is_dir():
        for p in d.glob("*.json"):
            p.unlink(missing_ok=True)


def _code_is_live(code: str) -> bool:
    try:
        return json.loads(_code_path(code).read_text(encoding="utf-8")).get("expires", 0) > _now()
    except (FileNotFoundError, ValueError, OSError):
        return False


def _consume_code(code: str) -> bool:
    """Spend a code. True for exactly one caller."""
    if not _code_is_live(code):
        _code_path(code).unlink(missing_ok=True)
        return False
    try:
        _code_path(code).unlink()
    except FileNotFoundError:
        return False
    return True


# --------------------------------------------------------------------------- routes

class EnrolIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=1, max_length=4000)
    enrol_code: str = Field(min_length=1, max_length=64)


def _admin(request: Request) -> str:
    """Guard for list/revoke. Permissive mode leaves them exactly as before (Caddy is the
    gate); device-only mode makes the hub enforce a device token itself, so a Caddy
    matcher mistake cannot leave revocation open to the internet."""
    if auth_mode() == "permissive":
        return ""
    return require_access(request)


def _device(authorization: str = Header(default="")) -> str:
    return require_device(authorization)


@router.post("/devices")
def enrol(body: EnrolIn, request: Request):
    """Register a device's public key. Needs a live single-use enrolment code, so the
    shared relay password alone is not enough. Mint one with `python -m auth mint-code`
    on the VM, or POST /api/auth/enrol-codes from an already-enrolled device."""
    ip = _client_ip(request)
    _check_locked(f"code:{ip}", "code:*")
    _rate(f"enrol:{ip}", RATE_ENROL_IP, "enrolment attempts")
    if not _code_is_live(body.enrol_code):
        _fail(f"code:{ip}", LOCK_IP)
        if _throttle.fail("code:*", *LOCK_CODE_GLOBAL):
            _void_all_codes()  # someone is guessing from several addresses: burn every live code
            log.warning("enrolment code guessing detected; all live codes voided")
        raise HTTPException(403, "Enrolment code is invalid, expired, or already used.")
    _load_public_key(body.public_key_pem)  # validate before storing / spending the code
    _ensure()
    with _LOCK:
        data = _read()
        devices = data.get("devices", [])
        if any(d.get("public_key_pem") == body.public_key_pem for d in devices):
            raise HTTPException(409, "That public key is already enrolled.")
        if not _consume_code(body.enrol_code):  # lost a race for the same code
            raise HTTPException(403, "Enrolment code is invalid, expired, or already used.")
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


@router.post("/enrol-codes")
def new_enrol_code(device_id: str = Depends(_device)):
    """Mint a code from an enrolled device. Deliberately requires a DEVICE token, never
    the basic-auth password: otherwise a leaked password could mint its own codes."""
    _rate(f"mint:{device_id}", RATE_MINT_DEVICE, "enrolment codes requested")
    return {"code": mint_enrol_code(minted_by=device_id), "expires_in": ENROL_CODE_TTL_S}


@router.get("/devices")
def get_devices(_who: str = Depends(_admin)):
    return {"devices": list_devices()}


@router.delete("/devices/{device_id}")
def revoke(device_id: str, _who: str = Depends(_admin)):
    """Revoke one device without touching the others — the point of the exercise."""
    with _LOCK:
        data = _read()
        devices = data.get("devices", [])
        kept = [d for d in devices if d.get("id") != device_id]
        if len(kept) == len(devices):
            raise HTTPException(404, f"No enrolled device with id {device_id}.")
        data["devices"] = kept
        _write(data)
        _challenges.pop(device_id, None)
        _tokens_sync()
        for th, (did, _exp) in list(_tokens.items()):
            if did == device_id:
                _tokens.pop(th, None)
        _tokens_save()
    return {"ok": True, "revoked": device_id, "remaining": len(kept)}


class ChallengeIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)


@router.post("/challenge")
def challenge(body: ChallengeIn, request: Request):
    ip = _client_ip(request)
    _check_locked(f"ip:{ip}")
    _rate(f"challenge-ip:{ip}", RATE_CHALLENGE_IP, "challenge requests")
    if not _find(body.device_id):
        _fail(f"ip:{ip}", LOCK_IP)  # probing for device ids
        raise HTTPException(404, "Unknown device. Enrol it first.")
    _rate(f"challenge-dev:{body.device_id}", RATE_CHALLENGE_DEVICE, "challenge requests for this device")
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
    ip = _client_ip(request)
    pair = f"tok:{body.device_id}:{ip}"
    _check_locked(pair, f"ip:{ip}")
    _rate(f"token-ip:{ip}", RATE_TOKEN_IP, "token requests")
    device = _find(body.device_id)
    if not device:
        _fail(f"ip:{ip}", LOCK_IP)
        raise HTTPException(404, "Unknown device. Enrol it first.")
    with _LOCK:
        entry = _challenges.pop(body.device_id, None)  # single use, win or lose
    if not entry:
        _fail(pair, LOCK_DEVICE_IP)
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
        _fail(pair, LOCK_DEVICE_IP)
        raise HTTPException(400, "signature_b64 is not valid base64.")
    try:
        if alg == "ES256":
            key.verify(signature, nonce.encode("utf-8"), ec.ECDSA(hashes.SHA256()))
        else:
            # RS256: PKCS#1 v1.5 over SHA-256 — exactly what Windows Hello's
            # KeyCredential.RequestSignAsync returns.
            key.verify(signature, nonce.encode("utf-8"), padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature:
        _fail(pair, LOCK_DEVICE_IP)
        _fail(f"ip:{ip}", LOCK_IP)
        raise HTTPException(401, "Signature does not match this device's enrolled key.")

    _throttle.reset(pair)
    raw = secrets.token_urlsafe(32)
    with _LOCK:
        _tokens_sync()
        _tokens[_hash_token(raw)] = (body.device_id, _now() + TOKEN_TTL_S)
        _tokens_save()
        data = _read()
        for d in data.get("devices", []):
            if d.get("id") == body.device_id:
                d["last_seen"] = _now()
        _write(data)
    return {"token": raw, "expires_in": TOKEN_TTL_S, "device_id": body.device_id}


def _hash_token(raw: str) -> str:
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def resolve_token(raw: Optional[str]) -> Optional[str]:
    """Device id for a bearer token, or None. Expired tokens are dropped on read, and a
    token whose device is no longer enrolled is dead even if nobody purged it (e.g. the
    device was removed by editing devices.json while the hub was down)."""
    if not raw:
        return None
    th = _hash_token(raw)
    with _LOCK:
        _tokens_sync()
        entry = _tokens.get(th)
        if not entry:
            return None
        device_id, expires = entry
        if _now() > expires:
            _tokens.pop(th, None)
            return None
    if not _find(device_id):
        return None
    return device_id


def _bearer(authorization: str) -> str:
    prefix = "Bearer "
    return authorization[len(prefix):] if authorization.startswith(prefix) else ""


def require_device(authorization: str = Header(default="")) -> str:
    """FastAPI dependency enforcing a device token. Not yet applied to any existing
    route — it goes on once both devices are enrolled and Caddy stops doing basic auth."""
    device_id = resolve_token(_bearer(authorization))
    if not device_id:
        raise HTTPException(401, "A valid device token is required.")
    return device_id


# --------------------------------------------------------------------------- forward auth
# Caddy's forward_auth asks /api/auth/verify about every /api/* request. In permissive
# mode (default) it accepts a device token OR the legacy basic-auth password, so the
# phone keeps working through the migration. In device-only mode only tokens pass.

def auth_mode() -> str:
    """HUB_AUTH_MODE: 'permissive' (default) or 'device-only'. Anything unrecognised is
    treated as permissive — failing closed here would lock the operator out over a typo."""
    raw = os.environ.get("HUB_AUTH_MODE", "permissive").strip().lower().replace("_", "-")
    if raw in ("device-only", "permissive"):
        return raw
    log.warning("unrecognised HUB_AUTH_MODE=%r; treating as permissive", raw)
    return "permissive"


def _basic_file() -> Path:
    return Path(os.environ.get("HUB_BASIC_AUTH_FILE") or (DATA / "basic_users"))


_basic_users: dict[str, bytes] = {}
_basic_sig: Optional[tuple] = None
_basic_ok: dict[str, float] = {}          # hmac(header) -> expiry of a verified credential
_basic_key = secrets.token_bytes(32)      # per-process, so the cache holds no reusable secret


def _plain_bcrypt(h: str) -> str:
    """Older Caddyfiles store the bcrypt hash base64-wrapped ('JDJh...'). Accept that form
    so pasting the Caddyfile's hash unchanged works instead of silently never matching."""
    if h.startswith("$2"):
        return h
    try:
        dec = base64.b64decode(h, validate=True).decode("ascii")
        return dec if dec.startswith("$2") else h
    except Exception:
        return h


def _load_basic() -> dict[str, bytes]:
    """`user:bcrypt-hash` lines — the same hash `caddy hash-password` prints, so the
    existing Caddyfile credentials can be pasted straight in."""
    global _basic_sig
    path = _basic_file()
    try:
        st = path.stat()
        sig = (str(path), st.st_mtime_ns, st.st_size)
    except OSError:
        sig = None
    with _LOCK:
        if sig != _basic_sig:
            _basic_users.clear()
            _basic_ok.clear()  # credentials removed from the file stop working now
            if sig is not None:
                for line in path.read_text(encoding="utf-8").splitlines():
                    line = line.strip()
                    if line and not line.startswith("#") and ":" in line:
                        user, _, h = line.partition(":")
                        _basic_users[user] = _plain_bcrypt(h.strip()).encode()
            _basic_sig = sig
        return dict(_basic_users)


def _basic_valid(authorization: str) -> bool:
    if not authorization.startswith("Basic "):
        return False
    try:
        user, _, password = base64.b64decode(authorization[6:], validate=True).decode("utf-8").partition(":")
    except Exception:
        return False
    users = _load_basic()
    tag = hmac.new(_basic_key, authorization.encode(), hashlib.sha256).hexdigest()
    now = _now()
    with _LOCK:
        if _basic_ok.get(tag, 0) > now:
            return True
    h = users.get(user)
    if h is None:
        return False
    try:
        import bcrypt

        ok = bcrypt.checkpw(password.encode("utf-8"), h)
    except ImportError:  # pragma: no cover - depends on the deployed venv
        log.error("bcrypt is not installed; basic-auth fallback cannot verify passwords")
        return False
    except ValueError:
        return False
    if ok:
        with _LOCK:
            # bcrypt at Caddy's default cost is ~1s: cache successes so page loads stay fast.
            _basic_ok[tag] = now + BASIC_CACHE_TTL_S
            if len(_basic_ok) > 1000:
                for k in [k for k, v in _basic_ok.items() if v <= now]:
                    _basic_ok.pop(k, None)
    return ok


_legacy_logged: dict[str, float] = {}


def _note_legacy(ip: str) -> None:
    """Once a minute per client, log that the shared password was used. This is the
    evidence the operator needs before flipping to device-only: `journalctl -u forge-hub
    | grep 'legacy basic'` must go quiet for every client they care about."""
    now = _now()
    if now - _legacy_logged.get(ip, -1e9) >= 60:
        _legacy_logged[ip] = now
        if len(_legacy_logged) > 1000:
            _legacy_logged.clear()
        log.info("legacy basic-auth accepted from %s", ip)


def _authenticate(request: Request) -> tuple[str, str]:
    """(method, identity) for an acceptable credential, else raises 401/429.
    Only credentials that were PRESENTED and wrong count towards the lockout — an
    anonymous request (a browser's first hit) is simply challenged."""
    ip = _client_ip(request)
    _check_locked(f"ip:{ip}")
    authorization = request.headers.get("authorization", "")
    mode = auth_mode()
    challenge_hdr = (
        'Basic realm="forge-hub", charset="UTF-8"' if mode == "permissive" else 'Bearer realm="forge-hub"'
    )
    if not authorization:
        raise HTTPException(401, "Authentication required.", headers={"WWW-Authenticate": challenge_hdr})
    if authorization.startswith("Bearer "):
        did = resolve_token(_bearer(authorization))
        if did:
            return "device", did
    elif mode == "permissive" and authorization.startswith("Basic ") and _basic_valid(authorization):
        _note_legacy(ip)
        return "basic", "basic"
    _fail(f"ip:{ip}", LOCK_IP)
    raise HTTPException(401, "Invalid credentials.", headers={"WWW-Authenticate": challenge_hdr})


def require_access(request: Request) -> str:
    """Credential check shared by /verify and the admin routes; see _authenticate."""
    return _authenticate(request)[1]


@router.api_route("/verify", methods=["GET", "HEAD"])
def verify(request: Request, response: Response):
    """Caddy forward_auth target. 2xx = let the request through; anything else is
    relayed to the client as-is (including 429 and WWW-Authenticate)."""
    method, ident = _authenticate(request)
    response.headers["X-Auth-Method"] = method
    if method == "device":
        response.headers["X-Device-Id"] = ident
    return {"ok": True, "method": method}


@router.get("/whoami")
def whoami(authorization: str = Header(default="")):
    """Lets a client check its token without a protected route existing yet."""
    device_id = resolve_token(_bearer(authorization))
    if not device_id:
        return {"authenticated": False, "devices_enrolled": len(list_devices())}
    device = _find(device_id) or {}
    return {"authenticated": True, "device_id": device_id, "name": device.get("name")}


# --------------------------------------------------------------------------- operator CLI
# Run on the VM as the hub's own user (same home directory as the service):
#   cd ~/hub && .venv/bin/python -m auth mint-code
# Shell access to the VM is the root of trust for the FIRST device; nothing reachable
# over HTTP can mint a code without an enrolled device's token.

def _cli(argv: list[str]) -> int:
    import argparse
    import getpass

    p = argparse.ArgumentParser(prog="python -m auth")
    sub = p.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("mint-code", help="print a single-use enrolment code")
    m.add_argument("--ttl", type=int, default=ENROL_CODE_TTL_S, help="seconds valid (default 600)")
    sub.add_parser("status", help="mode, basic-auth users, devices, live codes")
    sub.add_parser("list", help="enrolled devices")
    r = sub.add_parser("revoke", help="revoke a device by id (works while the hub is down)")
    r.add_argument("device_id")
    sub.add_parser("basic-hash", help="prompt for a password, print a user:bcrypt line for HUB_BASIC_AUTH_FILE")
    a = p.parse_args(argv)

    if a.cmd == "mint-code":
        print(mint_enrol_code(ttl_s=a.ttl))
        print(f"valid {a.ttl}s, single use", file=__import__("sys").stderr)
    elif a.cmd == "list":
        for d in list_devices():
            print(d["id"], d["name"], "last_seen=", d["last_seen"])
    elif a.cmd == "revoke":
        with _LOCK:
            data = _read()
            kept = [d for d in data.get("devices", []) if d.get("id") != a.device_id]
            if len(kept) == len(data.get("devices", [])):
                print("no such device", file=__import__("sys").stderr)
                return 1
            data["devices"] = kept
            _write(data)
        # Its tokens need no purge: resolve_token() refuses tokens of unenrolled devices.
        print("revoked", a.device_id)
    elif a.cmd == "status":
        live = len(list(_codes_dir().glob("*.json"))) if _codes_dir().is_dir() else 0
        print("mode:", auth_mode())
        print("basic file:", _basic_file(), "users:", len(_load_basic()))
        print("devices:", len(list_devices()), "| live enrolment codes:", live)
    elif a.cmd == "basic-hash":
        import bcrypt

        user = input("username: ").strip()
        pw = getpass.getpass("password: ")
        print(f"{user}:{bcrypt.hashpw(pw.encode(), bcrypt.gensalt(rounds=12)).decode()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(_cli(__import__("sys").argv[1:]))
