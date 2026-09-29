"""Gaps found by comparing this implementation against the unmerged device-auth branch
(93ba24e), each proved with a probe that failed here first.

Same rule as the other auth suites: the value is in the refusals. Each test names the
attack or mistake it closes, and most assert that a *second* party is unaffected,
because a lockout that also locks out the legitimate device is its own outage.
"""
import base64
import os
import stat
import threading

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

import auth
import ratelimit
from app import app

ec = pytest.importorskip("cryptography.hazmat.primitives.asymmetric.ec")
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402


# ------------------------------------------------------------------ helpers

def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()
    return priv, pem


def _sign(priv, nonce):
    return base64.b64encode(priv.sign(nonce.encode(), ec.ECDSA(hashes.SHA256()))).decode()


class Clock:
    def __init__(self):
        self.t = 1_000_000.0

    def __call__(self):
        return self.t


@pytest.fixture(autouse=True)
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(auth, "DATA", tmp_path / "auth_data")
    monkeypatch.setattr(auth, "DEVICES", tmp_path / "auth_data" / "devices.json")
    monkeypatch.setattr(auth, "_loaded_from", None)
    for var in ("HUB_REQUIRE_ENROL_CODE", "HUB_FORWARD_AUTH_MODE",
                "HUB_BASIC_AUTH_USER", "HUB_BASIC_AUTH_PASSWORD"):
        monkeypatch.delenv(var, raising=False)
    clock = Clock()
    monkeypatch.setattr(auth, "_now", clock)
    auth._challenges.clear()
    auth._tokens.clear()
    auth.reset_rate_limits()
    yield clock
    auth.reset_rate_limits()


@pytest.fixture
def client():
    return TestClient(app)


def _via(ip):
    """A request as Caddy relays it: the real client address in X-Forwarded-For."""
    return TestClient(app, headers={"X-Forwarded-For": ip})


def _bearer(t):
    return {"Authorization": f"Bearer {t}"}


def _basic(user="relay", pw="hunter2"):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode()}


def _enrolled(c, name="phone"):
    priv, pem = _keypair()
    r = c.post("/api/auth/devices", json={"name": name, "public_key_pem": pem})
    assert r.status_code == 200, r.text
    return priv, r.json()["id"], pem


def _login(c, priv, did):
    n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, n)})
    assert r.status_code == 200, r.text
    return r.json()["token"]


def _gate_on(c):
    r = c.post("/api/auth/config", json={"require_enrol_code": True})
    assert r.status_code == 200, r.text


def _enrol_with(c, code, pem=None, name="new"):
    pem = pem or _keypair()[1]
    return c.post("/api/auth/devices", json={"name": name, "public_key_pem": pem, "enrol_code": code})


def _restart():
    """What a hub restart loses: the module-level dicts. Codes and tokens are on disk."""
    auth._tokens.clear()
    auth._challenges.clear()
    auth._loaded_from = None
    auth._throttle.clear()


# ------------------------------------------------------------------ token resolution

def test_a_token_of_a_device_that_is_no_longer_enrolled_is_dead_everywhere(client):
    """Forward auth re-checked enrolment but resolve_token did not, so whoami and the
    require_device() dependency (the thing that will guard real routes) still
    honoured a token whose device had been removed, e.g. by a hand-edited devices.json
    or a tokens.json restored from before a revocation."""
    priv, did, _ = _enrolled(client)
    tok = _login(client, priv, did)
    auth._write({"devices": []})           # removed out-of-band; the token is still on file

    for restart_first in (False, True):
        if restart_first:
            _restart()
        who = client.get("/api/auth/whoami", headers=_bearer(tok)).json()
        assert who["authenticated"] is False
        with pytest.raises(HTTPException) as e:
            auth.require_device(f"Bearer {tok}")
        assert e.value.status_code == 401
        assert auth.resolve_token(tok) is None


# ------------------------------------------------------------------ devices.json races

def test_concurrent_enrolments_are_all_persisted(client):
    """Read-modify-write without holding the lock across it lost enrolments: every
    caller got a 200 and an id for a device that was not in devices.json."""
    _enrolled(client, "seed")
    n = 24
    barrier = threading.Barrier(n)
    statuses = []
    guard = threading.Lock()

    def worker(i):
        c = TestClient(app)
        _priv, pem = _keypair()
        barrier.wait()
        r = c.post("/api/auth/devices", json={"name": f"d{i}", "public_key_pem": pem})
        with guard:
            statuses.append(r.status_code)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert statuses == [200] * n
    assert len(auth.list_devices()) == n + 1


def test_a_revoke_landing_mid_token_exchange_is_not_undone(client, monkeypatch):
    """/token read devices.json, then wrote the whole list back to set last_seen. A
    revoke landing in between was silently reverted, resurrecting the device."""
    priv, did, _ = _enrolled(client, "victim")
    _enrolled(client, "bystander")
    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    sig = _sign(priv, nonce)

    real_read, real_find = auth._read, auth._find
    armed, paused, resume = threading.Event(), threading.Event(), threading.Event()
    state = {"held": False}

    def find(device_id):
        found = real_find(device_id)
        if device_id == did:
            armed.set()                     # /token has passed its lookup; next read is its own
        return found

    def read():
        data = real_read()
        if armed.is_set() and not state["held"] and any(d.get("id") == did for d in data["devices"]):
            state["held"] = True
            paused.set()
            resume.wait(5)                  # hold /token between its read and its write
        return data

    monkeypatch.setattr(auth, "_find", find)
    monkeypatch.setattr(auth, "_read", read)
    out = {}

    def do_token():
        out["token"] = TestClient(app).post(
            "/api/auth/token", json={"device_id": did, "signature_b64": sig}).status_code

    def do_revoke():
        out["revoke"] = TestClient(app).delete(f"/api/auth/devices/{did}").status_code

    t1 = threading.Thread(target=do_token)
    t1.start()
    assert paused.wait(5)
    t2 = threading.Thread(target=do_revoke)
    t2.start()
    t2.join(0.3)                            # give the revoke a chance to run (it must wait)
    resume.set()
    t1.join(5)
    t2.join(5)

    assert out == {"token": 200, "revoke": 200}
    monkeypatch.setattr(auth, "_read", real_read)
    assert [d["name"] for d in auth.list_devices()] == ["bystander"]


def test_a_revoke_does_not_drop_an_enrolment_that_lands_during_it(client, monkeypatch):
    """Same lost-update as the /token case, from the other side: a revoke that read the
    device list, then wrote it back, erased a device enrolled in between."""
    _priv, doomed, _ = _enrolled(client, "doomed")
    _enrolled(client, "keeper")

    real_read = auth._read
    state = {"armed": False, "held": False}
    paused, resume = threading.Event(), threading.Event()

    def read():
        data = real_read()
        if state["armed"] and not state["held"]:
            state["held"] = True
            paused.set()
            resume.wait(5)                  # hold the revoke between its read and its write
        return data

    monkeypatch.setattr(auth, "_read", read)
    out = {}
    state["armed"] = True
    t1 = threading.Thread(target=lambda: out.__setitem__(
        "revoke", TestClient(app).delete(f"/api/auth/devices/{doomed}").status_code))
    t1.start()
    assert paused.wait(5)
    _pem = _keypair()[1]
    t2 = threading.Thread(target=lambda: out.__setitem__(
        "enrol", TestClient(app).post("/api/auth/devices",
                                      json={"name": "newcomer", "public_key_pem": _pem}).status_code))
    t2.start()
    t2.join(0.3)                            # give the enrolment a chance to run (it must wait)
    resume.set()
    t1.join(5)
    t2.join(5)

    assert out == {"revoke": 200, "enrol": 200}
    monkeypatch.setattr(auth, "_read", real_read)
    assert sorted(d["name"] for d in auth.list_devices()) == ["keeper", "newcomer"]


# ------------------------------------------------------------------ enrolment codes

def test_a_leaked_password_cannot_mint_its_own_enrolment_code(client, monkeypatch):
    """The gate was meaningless against the threat it exists for: whoever could reach
    /api/auth (i.e. anyone past Caddy with the relay password) could POST /enrol-code
    and then enrol. Once a device exists, only a device token may mint."""
    priv, did, _ = _enrolled(client)
    _gate_on(client)

    # What a password-holder presents: a Basic header (Caddy already accepted it), or nothing.
    assert client.post("/api/auth/enrol-code").status_code == 401
    assert client.post("/api/auth/enrol-code", headers=_basic()).status_code == 401
    assert client.post("/api/auth/enrol-code", headers=_bearer("forged")).status_code == 401

    # Even once the hub verifies that password itself, it is still not a device.
    monkeypatch.setenv("HUB_BASIC_AUTH_USER", "relay")
    monkeypatch.setenv("HUB_BASIC_AUTH_PASSWORD", "hunter2")
    assert client.post("/api/auth/config",
                       json={"forward_auth_mode": "basic_or_token"}).status_code == 200
    assert client.post("/api/auth/enrol-code", headers=_basic()).status_code == 401
    assert [d["id"] for d in auth.list_devices()] == [did]

    # A device can, and the code it gets works exactly once.
    tok = _login(client, priv, did)
    r = client.post("/api/auth/enrol-code", headers=_bearer(tok))
    assert r.status_code == 200
    assert _enrol_with(client, r.json()["code"]).status_code == 200
    assert len(auth.list_devices()) == 2


def test_the_first_device_can_still_bootstrap_over_http_when_none_exist(client):
    """Nothing to authenticate against yet, so this falls back to whatever guards the
    hub. The strict path for it is the CLI; this only pins that the door has not been
    welded shut for someone who has no shell."""
    _gate_on(client)
    r = client.post("/api/auth/enrol-code")
    assert r.status_code == 200
    assert _enrol_with(client, r.json()["code"]).status_code == 200


def test_a_duplicate_key_does_not_burn_the_code(client):
    priv, did, pem = _enrolled(client)
    _gate_on(client)
    tok = _login(client, priv, did)
    code = client.post("/api/auth/enrol-code", headers=_bearer(tok)).json()["code"]

    assert _enrol_with(client, code, pem=pem).status_code == 409
    assert _enrol_with(client, code).status_code == 200      # still spendable


def test_an_unparsable_key_does_not_burn_the_code(client):
    _gate_on(client)
    code = client.post("/api/auth/enrol-code").json()["code"]
    bad = client.post("/api/auth/devices",
                      json={"name": "x", "public_key_pem": "junk", "enrol_code": code})
    assert bad.status_code == 400
    assert _enrol_with(client, code).status_code == 200


def test_the_code_is_checked_before_the_key_is_parsed(client):
    """The runbook's negative check sends a placeholder key and expects 403. If the key
    were parsed first it would get a 400 and the check could never pass."""
    _priv, did, _ = _enrolled(client)
    _gate_on(client)
    r = client.post("/api/auth/devices", json={
        "name": "x", "public_key_pem": "-----BEGIN PUBLIC KEY-----\nMFkw...\n-----END PUBLIC KEY-----\n"})
    assert r.status_code == 403
    assert [d["id"] for d in auth.list_devices()] == [did]


def test_racing_enrolments_spend_one_code_exactly_once(client):
    _gate_on(client)
    code = client.post("/api/auth/enrol-code").json()["code"]
    n = 20
    barrier = threading.Barrier(n)
    statuses = []
    guard = threading.Lock()

    def worker(i):
        c = _via(f"203.0.113.{i + 1}")
        _priv, pem = _keypair()
        barrier.wait()
        r = c.post("/api/auth/devices", json={"name": f"r{i}", "public_key_pem": pem, "enrol_code": code})
        with guard:
            statuses.append(r.status_code)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(n)]
    [t.start() for t in threads]
    [t.join() for t in threads]
    assert statuses.count(200) == 1, statuses
    assert len(auth.list_devices()) == 1


def test_a_code_minted_by_the_cli_is_spendable_survives_restart_and_is_single_use(client, capsys):
    """The CLI is a different process from the running hub, so codes have to live where
    both can see them (they used to be a module-level dict loaded once)."""
    _gate_on(client)
    assert auth._cli(["mint-code"]) == 0
    code = capsys.readouterr().out.strip()
    assert len(code.replace("-", "")) == auth._CODE_LEN

    _restart()
    assert _enrol_with(client, code).status_code == 200
    assert _enrol_with(client, code).status_code == 403


def test_codes_have_no_lookalike_glyphs_and_are_typo_tolerant(client):
    _gate_on(client)
    code = auth.mint_enrol_code()
    assert not set(code) & set("01OIlo")
    assert _enrol_with(client, code[:5]).status_code == 403                    # half a code
    assert _enrol_with(client, code.lower().replace("-", " ")).status_code == 200


def test_guessing_codes_locks_the_guesser_but_not_other_addresses(client, monkeypatch):
    monkeypatch.setattr(auth, "LOCK_CODE_GLOBAL", (1000, 600, 300))   # isolate the per-IP lock
    _gate_on(client)
    live = client.post("/api/auth/enrol-code").json()["code"]
    attacker = _via("203.0.113.66")
    for i in range(auth.LOCK_CODE_IP[0]):
        assert _enrol_with(attacker, f"WRONG{i}XXXXX").status_code == 403
    r = _enrol_with(attacker, live)                       # the right code, from the locked address
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0
    assert _enrol_with(_via("198.51.100.4"), live).status_code == 200


def test_distributed_guessing_voids_every_live_code(client, env):
    """Spreading guesses over addresses dodges any per-IP lock, so wrong codes are also
    counted globally and trip a void of every live code."""
    _gate_on(client)
    live = client.post("/api/auth/enrol-code").json()["code"]
    for i in range(auth.LOCK_CODE_GLOBAL[0]):
        assert _enrol_with(_via(f"203.0.113.{i + 1}"), f"NOPE{i}XXXXXX").status_code == 403
    assert _enrol_with(_via("198.51.100.200"), live).status_code == 429     # enrolment paused
    env.t += auth.LOCK_CODE_GLOBAL[2] + 1
    r = _enrol_with(_via("198.51.100.200"), live)
    assert r.status_code == 403 and "unknown" in r.json()["detail"]         # gone, not just paused


def test_a_missing_code_is_not_a_guess(client):
    """An anonymous probe carries no code; counting it would let anyone lock enrolment."""
    _gate_on(client)
    live = client.post("/api/auth/enrol-code").json()["code"]
    attacker = _via("203.0.113.66")
    for _ in range(auth.LOCK_CODE_IP[0] * 3):
        r = attacker.post("/api/auth/devices", json={"name": "x", "public_key_pem": _keypair()[1]})
        assert r.status_code == 403
    assert _enrol_with(attacker, live).status_code == 200


# ------------------------------------------------------------------ throttling

def test_flooding_a_device_id_from_one_address_does_not_starve_the_real_device(client):
    """The per-device buckets were keyed on the device alone, so anyone who knew an id
    could spend its whole allowance from their own address and lock the phone out."""
    priv, did, _ = _enrolled(client)
    wrong, _ = _keypair()
    attacker = _via("203.0.113.66")
    for _ in range(auth.RATE_LIMITS["challenge_device"][0] + 5):
        n = attacker.post("/api/auth/challenge", json={"device_id": did})
        if n.status_code == 200:
            attacker.post("/api/auth/token",
                          json={"device_id": did, "signature_b64": _sign(wrong, n.json()["nonce"])})
    assert attacker.post("/api/auth/challenge", json={"device_id": did}).status_code == 429

    assert _login(_via("198.51.100.1"), priv, did)          # the real device, elsewhere, is fine


def test_attackers_each_below_their_own_lock_cannot_starve_the_real_device_at_token(client):
    """Three addresses each stop one failure short of the lockout. If the token
    allowance were shared per device, their combined calls would exhaust it."""
    priv, did, _ = _enrolled(client)
    wrong, _ = _keypair()
    for ip in ("203.0.113.1", "203.0.113.2", "203.0.113.3"):
        attacker = _via(ip)
        for _ in range(auth.LOCK_DEVICE_IP[0] - 1):
            n = attacker.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
            r = attacker.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(wrong, n)})
            assert r.status_code == 401
    assert _login(_via("198.51.100.1"), priv, did)


def test_someone_who_knows_a_device_id_cannot_clobber_its_sign_in_or_get_it_locked_out(client, env):
    """Challenges were one slot per device, so a caller could replace the phone's nonce
    between its /challenge and /token. Its own signature then failed, and with failures
    locking the (device, IP) pair, five such clobbers locked the phone out."""
    priv, did, _ = _enrolled(client)
    real, attacker = _via("198.51.100.1"), _via("203.0.113.66")
    for _ in range(auth.LOCK_DEVICE_IP[0] + 2):
        nonce = real.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
        attacker.post("/api/auth/challenge", json={"device_id": did})     # tries to replace it
        r = real.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
        assert r.status_code == 200, r.text
        env.t += 61                                                      # keep clear of the call-rate window


def test_a_challenge_is_only_good_for_the_address_that_asked_for_it(client):
    """The other side of the same binding: a nonce issued to one address cannot be
    redeemed from another, so a captured nonce plus a stolen signature is not enough."""
    priv, did, _ = _enrolled(client)
    nonce = _via("198.51.100.1").post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = _via("203.0.113.66").post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
    assert r.status_code == 400 and "No outstanding challenge" in r.json()["detail"]
    # ...and it was not consumed by the wrong address's attempt.
    ok = _via("198.51.100.1").post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
    assert ok.status_code == 200


def test_expired_challenges_do_not_pile_up_when_addresses_are_rotated(client, env):
    _priv, did, _ = _enrolled(client)
    for i in range(20):
        _via(f"203.0.113.{i + 1}").post("/api/auth/challenge", json={"device_id": did})
    assert len(auth._challenges) == 20
    env.t += auth.CHALLENGE_TTL_S + 1
    _via("198.51.100.1").post("/api/auth/challenge", json={"device_id": did})
    assert len(auth._challenges) == 1


def test_bad_signatures_lock_the_pair_and_the_lock_holds_against_a_good_one(client, env):
    """Rate limits only slow a guesser; failures have to lock them out. Locked means
    locked, even for the correct key from that address, until the lock expires."""
    priv, did, _ = _enrolled(client)
    wrong, _ = _keypair()
    c = _via("203.0.113.5")
    for _ in range(auth.LOCK_DEVICE_IP[0]):
        n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
        assert c.post("/api/auth/token",
                      json={"device_id": did, "signature_b64": _sign(wrong, n)}).status_code == 401
    n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    good = c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, n)})
    assert good.status_code == 429 and int(good.headers["Retry-After"]) > 0

    env.t += auth.LOCK_DEVICE_IP[2] + 1
    assert _login(c, priv, did)


def test_a_good_login_clears_the_failure_count(client):
    priv, did, _ = _enrolled(client)
    wrong, _ = _keypair()
    c = _via("203.0.113.5")

    def bad():
        n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
        return c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(wrong, n)}).status_code

    assert [bad() for _ in range(auth.LOCK_DEVICE_IP[0] - 1)] == [401] * (auth.LOCK_DEVICE_IP[0] - 1)
    assert _login(c, priv, did)
    # Without the reset these would tip over the threshold and start returning 429.
    assert [bad() for _ in range(auth.LOCK_DEVICE_IP[0] - 1)] == [401] * (auth.LOCK_DEVICE_IP[0] - 1)


def test_a_client_prefixed_forwarded_for_entry_does_not_dodge_the_per_ip_limit(client):
    """Only the entry the proxy appended (the last) is trustworthy; earlier ones are
    client-supplied. Keying on the first let a caller mint a new bucket per request."""
    limit = auth.RATE_LIMITS["challenge_ip"][0]
    codes = []
    for i in range(limit + 1):
        r = client.post("/api/auth/challenge", json={"device_id": f"guess{i}"},
                        headers={"X-Forwarded-For": f"10.9.{i // 200}.{i % 200}, 203.0.113.50"})
        codes.append(r.status_code)
    assert codes[-1] == 429 and codes.count(404) == limit, codes


def test_limiter_memory_is_bounded_when_keys_are_rotated(client, monkeypatch):
    monkeypatch.setattr(ratelimit, "MAX_KEYS", 50)
    for i in range(400):
        client.post("/api/auth/challenge", json={"device_id": f"id{i}"},
                    headers={"X-Forwarded-For": f"10.{i // 200}.{i % 200}.1"})
    assert len(auth._throttle._hits) <= 50


def test_password_guessing_in_basic_or_token_locks_that_address_only_for_basic(client, monkeypatch):
    """Moving the password check from Caddy's bcrypt to the hub must not make it an
    unthrottled online guess. The lock is on wrong PASSWORDS, so a device token from
    the same address is never caught in it."""
    priv, did, _ = _enrolled(client)
    tok = _login(client, priv, did)
    monkeypatch.setenv("HUB_BASIC_AUTH_USER", "relay")
    monkeypatch.setenv("HUB_BASIC_AUTH_PASSWORD", "hunter2")
    assert client.post("/api/auth/config",
                       json={"forward_auth_mode": "basic_or_token"}).status_code == 200

    attacker = _via("203.0.113.66")
    for i in range(auth.LOCK_BASIC_IP[0]):
        assert attacker.get("/api/auth/forward", headers=_basic(pw=f"guess{i}")).status_code == 401
    r = attacker.get("/api/auth/forward", headers=_basic())          # right password, locked address
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0
    assert attacker.get("/api/auth/forward", headers=_bearer(tok)).status_code == 200
    assert _via("198.51.100.4").get("/api/auth/forward", headers=_basic()).status_code == 200


# ------------------------------------------------------------------ files and wiring

@pytest.mark.skipif(os.name == "nt", reason="Windows ignores POSIX modes")
def test_state_files_are_owner_only(client):
    priv, did, _ = _enrolled(client)
    _login(client, priv, did)
    auth.mint_enrol_code()
    files = [auth.DATA / "devices.json", auth.DATA / "tokens.json", *auth._codes_dir().glob("*.json")]
    assert len(files) == 3
    for f in files:
        assert stat.S_IMODE(f.stat().st_mode) & 0o077 == 0, f"{f.name} is readable by others"


def test_require_device_is_applied_to_no_existing_route():
    """Additive contract: the operator, not this change, decides when routes enforce
    tokens. Walks nested dependencies too, so a wrapper around it is caught."""
    def uses(dependant):
        return any(getattr(d, "call", None) is auth.require_device or uses(d)
                   for d in getattr(dependant, "dependencies", []))

    guarded = {r.path for r in app.routes if uses(getattr(r, "dependant", None))}
    assert guarded == set()


def test_cli_lists_enrolled_devices(client, capsys):
    _priv, did, _ = _enrolled(client, "phone")
    assert auth._cli(["list"]) == 0
    out = capsys.readouterr().out
    assert did in out and "phone" in out


def test_cli_revoke_needs_no_credentials_and_kills_the_devices_tokens(client):
    """DELETE /devices/<id> needs credentials in every mode but `permissive`, so the
    runbook's `curl localhost:9000 -X DELETE` 401s in exactly the modes where you would
    reach for it. The CLI needs none, and the running hub must honour it at once."""
    priv, did, _ = _enrolled(client, "stolen")
    _priv2, keep, _ = _enrolled(client, "kept")
    tok = _login(client, priv, did)
    assert client.post("/api/auth/config", json={"forward_auth_mode": "strict"}).status_code == 200
    assert client.delete(f"/api/auth/devices/{did}").status_code == 401       # the curl path

    assert auth._cli(["revoke", "no-such-id"]) == 1
    assert auth._cli(["revoke", did]) == 0
    assert client.get("/api/auth/forward", headers=_bearer(tok)).status_code == 401
    assert client.get("/api/auth/whoami", headers=_bearer(tok)).json()["authenticated"] is False
    assert [d["id"] for d in auth.list_devices()] == [keep]
