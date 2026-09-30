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

router = APIRouter(prefix="/api/auth")

DATA = Path.home() / "hub" / "auth_data"
DEVICES = DATA / "devices.json"
# Re-entrant, because every read-modify-write of devices.json / tokens.json holds it
# across the write. With a plain Lock those had to release between the read and the
# write, which lost concurrent enrolments and let a slow /token write resurrect a
# device that /revoke had just removed.
_LOCK = threading.RLock()

# A challenge is short-lived and single-use: it only has to survive one round
# trip. Held in memory, so a hub restart invalidates outstanding challenges and
# the client simply asks for another.
CHALLENGE_TTL_S = 120
TOKEN_TTL_S = 12 * 3600
ENROL_CODE_TTL_S = 15 * 60

# "device_id|client ip" -> (nonce, expires). Keyed by the address that asked, not the
# device alone: with one slot per device, anyone who knew a device id could replace the
# phone's nonce between its /challenge and its /token, making the phone's own signature
# fail -- and, with failures now locking the (device, IP) pair, get the phone locked out.
_challenges: dict[str, tuple[str, float]] = {}
_tokens: dict[str, tuple[str, float]] = {}       # token_hash -> (device_id, expires)

# Rate limiting. /challenge and /token were unbounded: anyone past Caddy could
# mint nonces forever, or grind signatures against an enrolled device id. Two
# independent buckets per request, because they stop different things -- the
# per-device bucket stops one stolen id being hammered, the per-IP bucket stops
# one host cycling through every enrolled id.
#
# The per-device buckets are keyed (device, client IP), NOT device alone. Keyed on
# the device alone, anyone who knew a device id could spend its whole allowance
# from their own address and lock the real device out of /challenge and /token
# (probe: an attacker flooding from 203.0.113.66 got the phone at 198.51.100.1 a 429).
#
# bucket -> (limit, window_seconds)
RATE_LIMITS: dict[str, tuple[int, int]] = {
    "challenge_device": (10, 60),
    "challenge_ip": (30, 60),
    "token_device": (10, 60),
    "token_ip": (30, 60),
    "enrol_code_ip": (10, 60),
}
# Call-rate limits above only slow a guesser down. Lockouts count FAILURES:
# (max_fails, window_s, lock_s).
LOCK_DEVICE_IP = (5, 600, 900)     # bad signatures for one device from one IP
LOCK_CODE_IP = (5, 600, 900)       # wrong enrolment codes from one IP
LOCK_CODE_GLOBAL = (5, 600, 300)   # wrong codes from ANYWHERE: also voids every live code
LOCK_BASIC_IP = (20, 600, 900)     # wrong relay passwords from one IP (basic_or_token only)

# late-bound so tests can move the clock by patching _now
_throttle = Throttle(lambda: _now())

# Enrolment codes: short-lived, single-use. Getting past Caddy is no longer
# enough to add a device -- you also need a code that someone already trusted
# issued in the last quarter hour. They are one file per code (see the
# "Enrolment codes" section below) so the operator's CLI, a separate process,
# can mint one that the running hub will honour.
_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   # no 0/O/1/I: read off a phone, typed on another
_CODE_LEN = 10                                        # ~50 bits; guessing is also locked out

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

log = logging.getLogger("forge-hub.auth")

# What an unrecognised forward_auth_mode falls back to. Deliberately the
# STRICTEST mode, not the default one: permissive accepts any Basic header
# without checking it, which is only correct while Caddy still validates
# passwords in front -- and strict is reached precisely by removing that. A
# typo in the config file would otherwise leave the relay open to the internet,
# silently. Failing closed is recoverable over SSH (HUB_FORWARD_AUTH_MODE, see
# docs/DEVICE_AUTH_MIGRATION.md); failing open is not.
UNKNOWN_MODE_FALLBACK = "strict"


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

    # POST /api/auth/config validates before writing, so a bad value here means
    # the file was hand-edited or restored -- both plausible mid-migration.
    # Normalise first, as the env override does: "Strict" is unambiguous intent
    # and honouring it beats making someone hunt a capital letter. What is left
    # after that is genuine garbage, and must not silently mean "permissive".
    file_mode = cfg.get("forward_auth_mode")
    if isinstance(file_mode, str):
        file_mode = file_mode.strip().lower()
        cfg["forward_auth_mode"] = file_mode
    if file_mode not in FORWARD_AUTH_MODES:
        log.error(
            "auth_config.json has forward_auth_mode=%r, which is not one of %s. "
            "Falling back to %r rather than the permissive default, which would "
            "accept any Basic header. Fix the file or set HUB_FORWARD_AUTH_MODE.",
            file_mode, FORWARD_AUTH_MODES, UNKNOWN_MODE_FALLBACK,
        )
        cfg["forward_auth_mode"] = UNKNOWN_MODE_FALLBACK

    env_code = os.environ.get("HUB_REQUIRE_ENROL_CODE")
    if env_code is not None:
        cfg["require_enrol_code"] = env_code.strip().lower() in ("1", "true", "yes", "on")

    raw_env_mode = os.environ.get("HUB_FORWARD_AUTH_MODE")
    env_mode = (raw_env_mode or "").strip().lower()
    if env_mode in FORWARD_AUTH_MODES:
        cfg["forward_auth_mode"] = env_mode
    elif raw_env_mode is not None and raw_env_mode.strip():
        # This variable is the documented way out of a lockout, set over SSH on
        # a relay whose API you can no longer reach. Dropping a typo silently
        # leaves the operator locked out with nothing to tell them why.
        log.error(
            "HUB_FORWARD_AUTH_MODE=%r is not one of %s and was ignored; the mode "
            "is still %r.", raw_env_mode, FORWARD_AUTH_MODES, cfg["forward_auth_mode"],
        )
    return cfg


def enrol_code_required(cfg: Optional[dict[str, Any]] = None) -> bool:
    """Whether POST /api/auth/devices needs a single-use enrolment code right now.

    True when the operator switched `require_enrol_code` on, AND whenever
    forward_auth_mode is not `permissive`. Enrolment has to be reachable without
    a token (a device cannot present one before it has one), so it is routed
    around forward_auth; in `permissive` Caddy's basic_auth still stands in
    front of it, but in `basic_or_token` and `strict` that password is gone and
    the code is the only thing between the internet and a new device key. Left
    to the flag alone, `strict` with the flag off meant anyone who could reach
    the URL could enrol a key, sign in with it, and own the relay. An unknown
    mode is treated as `strict` by config(), so it gates too.
    """
    cfg = cfg if cfg is not None else config()
    return bool(cfg.get("require_enrol_code")) or cfg.get("forward_auth_mode", "permissive") != "permissive"


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
# file where a valid one used to be. State files are owner-only (0600): they hold
# hashes, not credentials, but there is no reason for other local users to read
# who is enrolled.
# ---------------------------------------------------------------------------

def _atomic_write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with _LOCK:
        tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:  # pragma: no cover - Windows ignores POSIX modes
            pass
        os.replace(tmp, path)


def _tokens_path() -> Path:
    return DATA / "tokens.json"


def _load_state() -> None:
    """Rehydrate tokens from disk, once per DATA dir.

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


def _save_tokens() -> None:
    # Snapshot and write under ONE hold of the lock: with the write outside it, two
    # threads could each snapshot and then land their files in the wrong order,
    # dropping the newer token from disk.
    with _LOCK:
        now = _now()
        for th, (_did, exp) in list(_tokens.items()):
            if exp <= now:
                _tokens.pop(th, None)
        payload = {th: [did, exp] for th, (did, exp) in _tokens.items()}
        _atomic_write_json(_tokens_path(), payload)


# ---------------------------------------------------------------------------
# Rate limiting and lockouts (state and eviction live in hub/ratelimit.py)
# ---------------------------------------------------------------------------

def _client_ip(request: Optional[Request]) -> str:
    """The caller's address as Caddy saw it.

    uvicorn binds 127.0.0.1, so request.client.host is always the proxy itself
    and useless as a bucket key. The real address is whatever Caddy put in
    X-Forwarded-For. The LAST entry is the one the proxy itself appended; anything
    before it was supplied by the client, so taking the first (as this used to)
    let a caller dodge every per-IP bucket by prefixing a made-up address. Stock
    Caddy replaces the header outright, so there is only one entry either way.

    This still assumes nothing but Caddy can reach the socket: from a directly
    reachable port the whole header is attacker-controlled.
    """
    if request is None:
        return "unknown"
    xff = request.headers.get("x-forwarded-for", "")
    if xff:
        last = xff.split(",")[-1].strip()
        if last:
            return last
    return request.client.host if request.client else "unknown"


def _too_many(wait: int, what: str) -> HTTPException:
    return HTTPException(
        429,
        f"Too many {what}; retry in {wait} seconds.",
        headers={"Retry-After": str(wait)},
    )


def _rate_check(bucket: str, subject: str) -> None:
    """Raise 429 if `subject` has spent its allowance in `bucket`."""
    limit, window = RATE_LIMITS[bucket]
    wait = _throttle.hit(bucket + ":" + subject, limit, window)
    if wait:
        raise HTTPException(
            429,
            f"Too many requests: {limit} per {window}s for this {bucket.rsplit('_', 1)[1]}. "
            f"Try again in {wait}s.",
            headers={"Retry-After": str(wait)},
        )


def _check_locked(*keys: str) -> None:
    wait = max((_throttle.locked_for(k) for k in keys), default=0)
    if wait:
        raise _too_many(wait, "failed attempts")


def _fail(key: str, cfg: tuple[int, int, int]) -> bool:
    return _throttle.fail(key, *cfg)


def reset_rate_limits() -> None:
    """Escape hatch for the operator (and for tests) -- see the migration doc."""
    _throttle.clear()


# ---------------------------------------------------------------------------
# Enrolment codes -- one file per code, named by the hash of the code.
#
# Minting is create; spending is unlink, which the OS lets exactly one caller
# win, so two racing enrolments cannot both spend the same code, and the CLI
# (a separate process) and the running hub share the directory with no other
# coordination. The lookup deliberately matches hashes against the directory
# listing instead of building a path from the request's `enrol_code`, so
# request data never reaches a filesystem call.
# ---------------------------------------------------------------------------

def _codes_dir() -> Path:
    return DATA / "enrol_codes"


def _normalise_code(code: str) -> str:
    return "".join(c for c in code.upper() if c.isalnum())


def _hash_code(raw: str) -> str:
    return hashlib.sha256(_normalise_code(raw).encode("utf-8")).hexdigest()


def mint_enrol_code(minted_by: str = "cli", ttl_s: int = ENROL_CODE_TTL_S) -> str:
    """Create a single-use enrolment code valid for ttl_s. Returns it as XXXXX-XXXXX."""
    raw = "".join(secrets.choice(_CODE_ALPHABET) for _ in range(_CODE_LEN))
    now = _now()
    _prune_codes()
    _atomic_write_json(
        _codes_dir() / (_hash_code(raw) + ".json"),
        {"created": now, "expires": now + ttl_s, "by": minted_by},
    )
    return f"{raw[:5]}-{raw[5:]}"


def _prune_codes() -> None:
    d = _codes_dir()
    if not d.is_dir():
        return
    now = _now()
    for p in d.glob("*.json"):
        try:
            if float(json.loads(p.read_text(encoding="utf-8")).get("expires", 0)) <= now:
                p.unlink(missing_ok=True)
        except (ValueError, OSError, TypeError, AttributeError):
            p.unlink(missing_ok=True)


def _void_all_codes() -> None:
    d = _codes_dir()
    if d.is_dir():
        for p in d.glob("*.json"):
            p.unlink(missing_ok=True)


def _code_state(raw: str) -> tuple[str, Optional[Path]]:
    """('live' | 'expired' | 'unknown', the code's file)."""
    want = _hash_code(raw)
    d = _codes_dir()
    if not d.is_dir():
        return "unknown", None
    for p in d.glob("*.json"):
        if hmac.compare_digest(p.stem, want):
            try:
                expires = float(json.loads(p.read_text(encoding="utf-8")).get("expires", 0))
            except (FileNotFoundError, ValueError, OSError, TypeError, AttributeError):
                return "unknown", None
            return ("live" if expires > _now() else "expired"), p
    return "unknown", None


def _spend_code(path: Path) -> bool:
    """True for exactly one caller: unlink either removes the file or it does not."""
    try:
        path.unlink()
    except FileNotFoundError:
        return False
    return True


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
    _atomic_write_json(DEVICES, payload)


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


# --- bcrypt user file -------------------------------------------------------
# A second source beside the single env/JSON credential above, so the relay's
# EXISTING Caddyfile users can be pasted in unchanged rather than re-issued.
# Caddy stores exactly what `caddy hash-password` prints; this reads the same.
BASIC_CACHE_TTL_S = 300

_basic_users: dict[str, bytes] = {}
_basic_sig: Optional[tuple] = None
_basic_ok: dict[str, float] = {}       # hmac(header) -> expiry of a verified credential
_basic_key = secrets.token_bytes(32)   # per-process, so the cache stores no reusable secret


def _basic_file() -> Path:
    return Path(os.environ.get("HUB_BASIC_AUTH_FILE") or (DATA / "basic_users"))


def _plain_bcrypt(h: str) -> str:
    """Older Caddyfiles store the hash base64-wrapped ('JDJh...'). Accept that too,
    so pasting the Caddyfile value unchanged works instead of silently never matching."""
    if h.startswith("$2"):
        return h
    try:
        dec = base64.b64decode(h, validate=True).decode("ascii")
        return dec if dec.startswith("$2") else h
    except Exception:
        return h


def _load_basic() -> dict[str, bytes]:
    """`user:bcrypt-hash` lines, reloaded when the file's mtime/size changes."""
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
            # Deleting a line must take effect now, not in five minutes.
            _basic_ok.clear()
            if sig is not None:
                try:
                    for line in path.read_text(encoding="utf-8").splitlines():
                        line = line.strip()
                        if line and not line.startswith("#") and ":" in line:
                            user, _, h = line.partition(":")
                            _basic_users[user] = _plain_bcrypt(h.strip()).encode()
                except OSError:
                    pass
            _basic_sig = sig
        return dict(_basic_users)


def _check_basic_file(header: str) -> bool:
    """True if the header matches a line in the bcrypt user file."""
    users = _load_basic()
    if not users:
        return False
    try:
        user, _, password = base64.b64decode(
            header.split(" ", 1)[1], validate=True).decode("utf-8").partition(":")
    except Exception:
        return False
    tag = hmac.new(_basic_key, header.encode(), hashlib.sha256).hexdigest()
    now = _now()
    with _LOCK:
        if _basic_ok.get(tag, 0) > now:
            return True
    stored = users.get(user)
    if stored is None:
        return False
    try:
        import bcrypt

        ok = bcrypt.checkpw(password.encode("utf-8"), stored)
    except ImportError:  # pragma: no cover - depends on the deployed venv
        log.error("bcrypt is not installed; the basic_users file cannot be checked. "
                  "Install requirements.txt on the relay.")
        return False
    except ValueError:
        return False
    if ok:
        with _LOCK:
            # bcrypt at Caddy's default cost is ~1s per check; without this cache
            # every page load on a screen full of thumbnails would stall.
            _basic_ok[tag] = now + BASIC_CACHE_TTL_S
            if len(_basic_ok) > 1000:
                for k in [k for k, v in _basic_ok.items() if v <= now]:
                    _basic_ok.pop(k, None)
    return ok


def _basic_configured() -> bool:
    """Whether ANY basic credential exists — env/JSON single, or the bcrypt file."""
    return _basic_credential() is not None or bool(_load_basic())


def _check_basic(header: str) -> bool:
    """True if this Authorization: Basic header matches any configured credential."""
    cred = _basic_credential()
    if cred:
        try:
            decoded = base64.b64decode(header.split(" ", 1)[1], validate=True).decode("utf-8")
            user, _, password = decoded.partition(":")
        except Exception:
            return _check_basic_file(header)
        want_user, want_hash = cred
        got_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
        # Both compared in constant time; `and` would short-circuit on the username
        # and leak which half was wrong through timing.
        ok_user = hmac.compare_digest(user, want_user)
        ok_pass = hmac.compare_digest(got_hash, want_hash)
        if ok_user and ok_pass:
            return True
    return _check_basic_file(header)


_legacy_logged: dict[str, float] = {}


def _note_legacy(ip: str) -> None:
    """Once a minute per client, record that the shared password was used.

    This is the evidence needed before flipping to device-only: when
    `journalctl -u forge-hub | grep 'legacy basic'` goes quiet for every client
    that matters, nothing is still relying on the password. Guessing instead is
    how the phone gets locked out remotely.
    """
    now = _now()
    if now - _legacy_logged.get(ip, -1e9) >= 60:
        _legacy_logged[ip] = now
        if len(_legacy_logged) > 1000:
            _legacy_logged.clear()
        log.info("legacy basic-auth accepted from %s", ip)


def _deny(detail: str, offer_basic: bool) -> HTTPException:
    headers = {"WWW-Authenticate": 'Basic realm="forge-hub"'} if offer_basic else {}
    return HTTPException(401, detail, headers=headers)


def _decide(authorization: str, ip: Optional[str] = None) -> dict[str, Any]:
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
                    browser still have no device token. Caddy's bcrypt check no
                    longer sits in front here, so wrong passwords are locked
                    out per client IP (LOCK_BASIC_IP); a valid device token is
                    never affected by that lock.
      strict        a device token, nothing else.

    Raises HTTPException on refusal; returns a description of who got in.
    """
    mode = config().get("forward_auth_mode", "permissive")
    if mode not in FORWARD_AUTH_MODES:
        # config() already normalises this; belt and braces for any other caller
        # that reaches here with a raw value. Fail closed, never to permissive.
        log.error("Unrecognised forward_auth_mode %r at the decision point; using %r.",
                  mode, UNKNOWN_MODE_FALLBACK)
        mode = UNKNOWN_MODE_FALLBACK

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
            if ip:
                _note_legacy(ip)
            return {"ok": True, "method": "basic-passthrough", "mode": mode}
        # basic_or_token
        if not _basic_configured():
            # Refusing loudly beats silently degrading to permissive: a
            # misconfigured relay that waves everything through is exactly the
            # failure this endpoint exists to prevent.
            raise HTTPException(
                503,
                "forward_auth_mode=basic_or_token but the hub has no basic-auth "
                "credential. Set HUB_BASIC_AUTH_USER and HUB_BASIC_AUTH_PASSWORD, "
                "or populate the basic_users file (HUB_BASIC_AUTH_FILE), or drop "
                "back to forward_auth_mode=permissive.",
            )
        if ip:
            _check_locked(f"basic:{ip}")
        if not _check_basic(authorization):
            if ip:
                _fail(f"basic:{ip}", LOCK_BASIC_IP)
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


def require_admin(request: Request, authorization: str = Header(default="")) -> dict[str, Any]:
    """Guard for the endpoints that can change who gets in.

    /api/auth/* has to sit OUTSIDE Caddy's forward_auth -- a device cannot
    present a token before it has one -- so once Caddy stops doing basic auth,
    revoke and config would otherwise be reachable by anyone who found the URL.
    Under `permissive` this is a no-op (Caddy is still checking the password in
    front), which is what keeps the change additive.
    """
    if config().get("forward_auth_mode", "permissive") == "permissive":
        return {"ok": True, "method": "edge", "mode": "permissive"}
    return _decide(authorization, _client_ip(request))


@router.get("/forward")
def forward_auth(request: Request, response: Response, authorization: str = Header(default="")):
    """Caddy's `forward_auth` target. 2xx lets the request through, 401 stops it.

    Success echoes the device back in headers so Caddy can `copy_headers` them
    upstream and the hub can log which device did what.
    """
    verdict = _decide(authorization, _client_ip(request))
    if verdict.get("device_id"):
        response.headers["X-Device-Id"] = str(verdict["device_id"])
        response.headers["X-Device-Name"] = str(verdict.get("name") or "")
    response.headers["X-Auth-Method"] = str(verdict["method"])
    return verdict


def _mint_guard(request: Request, authorization: str = Header(default="")) -> str:
    """Who may mint an enrolment code.

    Once ANY device is enrolled, only a device token can. The relay password never
    can: it is exactly the credential a code exists to distinguish from, so a leaked
    password that could also mint its own code would defeat the gate outright
    (probe: with the gate on, a password-holder minted a code and enrolled a device
    in `permissive` and `basic_or_token`).

    With zero devices enrolled there is no device credential to ask for, so this
    falls back to `require_admin` (whatever guards the hub). The operator's
    alternative is the CLI on the VM: `python -m auth mint-code`.
    """
    if list_devices():
        raw = authorization[len("Bearer "):] if authorization.startswith("Bearer ") else ""
        device_id = resolve_token(raw)
        if not device_id:
            raise _deny(
                "Minting an enrolment code needs a device token, not the relay password. "
                "On the VM: python -m auth mint-code",
                False,
            )
        return device_id
    require_admin(request, authorization)
    return "bootstrap"


@router.post("/enrol-code")
def issue_enrol_code(request: Request, who: str = Depends(_mint_guard)):
    """Mint a short-lived, single-use enrolment code (see _mint_guard for who may)."""
    _rate_check("enrol_code_ip", _client_ip(request))
    return {"code": mint_enrol_code(minted_by=who), "expires_in": ENROL_CODE_TTL_S}


def _require_live_code(raw: Optional[str], ip: str) -> Path:
    """The file of a live enrolment code, or raise. Nothing is spent here.

    Wrong codes are counted: LOCK_CODE_IP locks the guesser out, and
    LOCK_CODE_GLOBAL -- guessing spread over many addresses to dodge that -- voids
    every live code and pauses enrolment. A missing code is not a guess and does
    not count.
    """
    _check_locked(f"code:{ip}", "code:*")
    if not raw:
        raise HTTPException(
            403,
            "An enrolment code is required. Get one from an already-trusted "
            "session (POST /api/auth/enrol-code with a device token) or, on the VM, "
            "`python -m auth mint-code`.",
        )
    state, path = _code_state(raw)
    if state == "live" and path is not None:
        return path
    if state == "expired" and path is not None:
        _spend_code(path)
        raise HTTPException(403, "That enrolment code has expired; issue a new one.")
    _fail(f"code:{ip}", LOCK_CODE_IP)
    if _fail("code:*", LOCK_CODE_GLOBAL):
        _void_all_codes()  # someone is guessing from several addresses: burn every live code
    raise HTTPException(403, "That enrolment code is unknown or has already been used.")


class EnrolIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    public_key_pem: str = Field(min_length=1, max_length=4000)
    # Optional so the already-enrolled phone and the current Android build keep
    # working. It only becomes mandatory once require_enrol_code is turned on.
    enrol_code: Optional[str] = Field(default=None, max_length=128)


@router.post("/devices")
def enrol(body: EnrolIn, request: Request):
    """Register a device's public key. Guarded by whatever already fronts the hub,
    plus a single-use code once `require_enrol_code` is on."""
    gate = enrol_code_required()
    code_file = _require_live_code(body.enrol_code, _client_ip(request)) if gate else None
    # Everything that can refuse for a reason that is not the code's fault happens
    # BEFORE the code is spent: an unparsable or already-enrolled key must not burn it.
    _load_public_key(body.public_key_pem)
    with _LOCK:
        _ensure()
        data = _read()
        devices = data.get("devices", [])
        if any(d.get("public_key_pem") == body.public_key_pem for d in devices):
            raise HTTPException(409, "That public key is already enrolled.")
        if code_file is not None and not _spend_code(code_file):  # lost a race for the same code
            raise HTTPException(403, "That enrolment code is unknown or has already been used.")
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
def get_devices(_admin: dict = Depends(require_admin)):
    """Who is enrolled. Names, ids and last-seen times are nobody's business but the
    operator's, and /api/auth/* can sit outside forward_auth, so this guards itself
    like revoke and config-writes do (a no-op under `permissive`, where Caddy's
    password is still in front)."""
    return {"devices": list_devices()}


@router.delete("/devices/{device_id}")
def revoke(device_id: str, _admin: dict = Depends(require_admin)):
    """Revoke one device without touching the others — the point of the exercise."""
    with _LOCK:
        data = _read()
        devices = data.get("devices", [])
        kept = [d for d in devices if d.get("id") != device_id]
        if len(kept) == len(devices):
            raise HTTPException(404, f"No enrolled device with id {device_id}.")
        data["devices"] = kept
        _write(data)
    _load_state()
    with _LOCK:
        for k in [k for k in _challenges if k.startswith(device_id + "|")]:
            _challenges.pop(k, None)
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
    ip = _client_ip(request)
    _rate_check("challenge_ip", ip)
    _rate_check("challenge_device", f"{body.device_id}|{ip}")
    if not _find(body.device_id):
        raise HTTPException(404, "Unknown device. Enrol it first.")
    nonce = secrets.token_urlsafe(32)
    now = _now()
    expires = now + CHALLENGE_TTL_S
    with _LOCK:
        # Keys are per address now, so forget the expired ones or a spray of source
        # addresses could grow this without bound.
        for k in [k for k, (_n, exp) in _challenges.items() if exp <= now]:
            _challenges.pop(k, None)
        _challenges[f"{body.device_id}|{ip}"] = (nonce, expires)
    return {"nonce": nonce, "expires_in": CHALLENGE_TTL_S}


class TokenIn(BaseModel):
    device_id: str = Field(min_length=1, max_length=64)
    signature_b64: str = Field(min_length=1, max_length=2000)


@router.post("/token")
def token(body: TokenIn, request: Request):
    """Exchange a signature over the outstanding challenge for a short-lived token."""
    # Counted before verification, so *failed* signature attempts burn the
    # allowance. Counting only successes would leave the grind unbounded. On top
    # of that, failures lock the (device, IP) pair out: scoped to the pair so the
    # attacker's address is locked out of this device while the device itself,
    # signing in from its own address, is untouched.
    ip = _client_ip(request)
    pair = f"tok:{body.device_id}:{ip}"
    _check_locked(pair)
    _rate_check("token_ip", ip)
    _rate_check("token_device", f"{body.device_id}|{ip}")
    device = _find(body.device_id)
    if not device:
        raise HTTPException(404, "Unknown device. Enrol it first.")
    with _LOCK:
        entry = _challenges.pop(f"{body.device_id}|{ip}", None)  # single use, win or lose
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
        raise HTTPException(401, "Signature does not match this device's enrolled key.")

    _throttle.reset(pair)
    raw = secrets.token_urlsafe(32)
    _load_state()
    with _LOCK:
        _tokens[_hash_token(raw)] = (body.device_id, _now() + TOKEN_TTL_S)
    _save_tokens()
    # Read-modify-write under the lock: with the read outside it, a /revoke landing
    # between the read and the write was silently undone when this wrote the old
    # device list back.
    with _LOCK:
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
    """Device id for a bearer token, or None. Expired tokens are dropped on read.

    A token whose device is no longer enrolled is dead even if nothing purged it (a
    hand-edited devices.json, a tokens.json restored from before a revocation).
    Checked here rather than only in forward auth so that require_device() and
    /whoami -- the things that will guard real routes later -- cannot resurrect a
    revoked device either.
    """
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
    if not _find(device_id):
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
        # What clients act on: whether a code is needed NOW, which is also true in any
        # mode but `permissive` (see enrol_code_required). The stored flag is kept apart.
        "require_enrol_code": enrol_code_required(cfg),
        "require_enrol_code_configured": bool(cfg.get("require_enrol_code")),
        "devices_enrolled": len(list_devices()),
        # Reported so the migration doc's pre-flight check ("is the fallback
        # actually usable before I remove Caddy's?") sees both sources.
        "basic_credential_configured": _basic_configured(),
        "basic_users_file": str(_basic_file()),
        "basic_users_count": len(_load_basic()),
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


# ---------------------------------------------------------------------------
# Operator CLI -- run on the VM as the hub's own user (same home directory as
# the service), from the directory that holds auth.py:
#
#     cd ~/hub && .venv/bin/python -m auth mint-code
#
# Shell access to the VM is the root of trust for the FIRST device: nothing
# reachable over HTTP with only the relay password can mint a code once a
# device exists (see _mint_guard). Never run it with sudo -- DATA is under
# Path.home(), so root would read and write /root/hub/auth_data and appear to
# "lose" every device.
# ---------------------------------------------------------------------------

def _cli(argv: list[str]) -> int:
    import argparse
    import sys

    p = argparse.ArgumentParser(prog="python -m auth")
    sub = p.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("mint-code", help="print a single-use enrolment code")
    m.add_argument("--ttl", type=int, default=ENROL_CODE_TTL_S, help="seconds valid")
    sub.add_parser("list", help="enrolled devices")
    r = sub.add_parser("revoke", help="revoke a device by id; works while the hub is down")
    r.add_argument("device_id")
    a = p.parse_args(argv)

    if a.cmd == "mint-code":
        print(mint_enrol_code(ttl_s=a.ttl))
        print(f"valid {a.ttl}s, single use; data dir {DATA}", file=sys.stderr)
    elif a.cmd == "list":
        for d in list_devices():
            print(d["id"], d["name"], "last_seen=", d["last_seen"])
    elif a.cmd == "revoke":
        # DELETE /api/auth/devices/<id> needs credentials once the mode is not
        # `permissive`, so a bare `curl localhost:9000` cannot revoke a stolen device
        # in exactly the modes where you would want to. This needs none: shell access
        # to the VM is the credential. The device's tokens need no purge -- resolve_token()
        # refuses a token whose device is no longer enrolled, in the running hub too.
        with _LOCK:
            data = _read()
            kept = [d for d in data.get("devices", []) if d.get("id") != a.device_id]
            if len(kept) == len(data.get("devices", [])):
                print("no such device", file=sys.stderr)
                return 1
            data["devices"] = kept
            _write(data)
        print("revoked", a.device_id)
    return 0


if __name__ == "__main__":
    import sys

    raise SystemExit(_cli(sys.argv[1:]))
