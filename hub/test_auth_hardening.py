"""Negative tests for the deployment hardening around device auth.

test_auth_devices.py already proves the protocol: wrong key refused, signature
not replayable, expired challenge refused. These prove the things that make it
safe to actually deploy, and they are negative for the same reason -- a
happy-path suite would pass just as well against a rate limiter that never
blocks, an enrolment code that can be spent twice, and a forward-auth endpoint
that waves a revoked device straight through.
"""
import base64

import pytest
from fastapi.testclient import TestClient

import auth
from app import app

ec = pytest.importorskip("cryptography.hazmat.primitives.asymmetric.ec")
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402


def _keypair():
    priv = ec.generate_private_key(ec.SECP256R1())
    pem = priv.public_key().public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    ).decode()
    return priv, pem


def _sign(priv, nonce: str) -> str:
    return base64.b64encode(priv.sign(nonce.encode(), ec.ECDSA(hashes.SHA256()))).decode()


@pytest.fixture(autouse=True)
def isolate(tmp_path, monkeypatch):
    monkeypatch.setattr(auth, "DATA", tmp_path / "auth_data")
    monkeypatch.setattr(auth, "DEVICES", tmp_path / "auth_data" / "devices.json")
    # _loaded_from keys the disk rehydration on DATA, so resetting it here is
    # belt-and-braces: a stale value would serve the previous test's tokens.
    monkeypatch.setattr(auth, "_loaded_from", None)
    for var in ("HUB_REQUIRE_ENROL_CODE", "HUB_FORWARD_AUTH_MODE",
                "HUB_BASIC_AUTH_USER", "HUB_BASIC_AUTH_PASSWORD"):
        monkeypatch.delenv(var, raising=False)
    auth._challenges.clear()
    auth._tokens.clear()
    auth._enrol_codes.clear()
    auth.reset_rate_limits()
    yield
    # Leave no allowance spent for whatever test file runs next.
    auth.reset_rate_limits()


def _enrol(client, name="laptop", **extra):
    priv, pem = _keypair()
    body = {"name": name, "public_key_pem": pem}
    body.update(extra)
    r = client.post("/api/auth/devices", json=body)
    return priv, r


def _enrolled(client, name="laptop"):
    priv, r = _enrol(client, name)
    assert r.status_code == 200, r.text
    return priv, r.json()["id"]


def _token_for(client, priv, did):
    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = client.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
    assert r.status_code == 200, r.text
    return r.json()["token"]


# ---------------------------------------------------------------------------
# Rate limiting
# ---------------------------------------------------------------------------

def test_challenge_rate_limit_actually_blocks_one_device():
    client = TestClient(app)
    _priv, did = _enrolled(client)
    limit, _window = auth.RATE_LIMITS["challenge_device"]

    for i in range(limit):
        r = client.post("/api/auth/challenge", json={"device_id": did})
        assert r.status_code == 200, f"request {i} should still be inside the allowance: {r.text}"

    blocked = client.post("/api/auth/challenge", json={"device_id": did})
    assert blocked.status_code == 429, blocked.text
    # A 429 with no Retry-After is a client that hot-loops. Prove we send one.
    assert int(blocked.headers["retry-after"]) >= 1


def test_failed_signatures_burn_the_token_allowance():
    """The grind this exists to stop uses *wrong* signatures, so failures must count."""
    client = TestClient(app)
    _priv, did = _enrolled(client)
    attacker, _pem = _keypair()
    limit, _window = auth.RATE_LIMITS["token_device"]

    seen_401 = 0
    for _ in range(limit):
        nonce = client.post("/api/auth/challenge", json={"device_id": did}).json().get("nonce")
        if not nonce:  # challenge allowance ran out first; still a block
            break
        r = client.post("/api/auth/token",
                        json={"device_id": did, "signature_b64": _sign(attacker, nonce)})
        assert r.status_code in (401, 429)
        seen_401 += r.status_code == 401

    assert seen_401 > 0, "expected the forged signatures to be rejected on their merits first"
    blocked = client.post("/api/auth/token", json={"device_id": did, "signature_b64": "AAAA"})
    assert blocked.status_code == 429, blocked.text


def test_one_ip_cannot_escape_the_limit_by_cycling_device_ids():
    """The per-device bucket alone is useless against id enumeration."""
    client = TestClient(app)
    limit, _window = auth.RATE_LIMITS["challenge_ip"]
    headers = {"X-Forwarded-For": "203.0.113.7"}

    codes = []
    for i in range(limit + 1):
        r = client.post("/api/auth/challenge",
                        json={"device_id": f"guess{i}"}, headers=headers)
        codes.append(r.status_code)

    # Unknown ids, so every allowed request is a 404 -- and the last is a 429.
    assert codes[-1] == 429, codes
    assert codes.count(404) == limit, codes

    # A different address is unaffected: the bucket is per-IP, not global.
    other = client.post("/api/auth/challenge", json={"device_id": "guess0"},
                        headers={"X-Forwarded-For": "198.51.100.9"})
    assert other.status_code == 404, other.text


# ---------------------------------------------------------------------------
# Enrolment codes
# ---------------------------------------------------------------------------

def _require_codes(client):
    # Needs a device enrolled first only for the forward-auth guard; the enrol
    # code flag has no such precondition.
    r = client.post("/api/auth/config", json={"require_enrol_code": True})
    assert r.status_code == 200, r.text
    assert r.json()["require_enrol_code"] is True


def test_enrolment_code_cannot_be_reused():
    client = TestClient(app)
    _require_codes(client)
    code = client.post("/api/auth/enrol-code").json()["code"]

    _priv, first = _enrol(client, "phone", enrol_code=code)
    assert first.status_code == 200, first.text

    _priv2, second = _enrol(client, "attacker-phone", enrol_code=code)
    assert second.status_code == 403, second.text
    assert "already been used" in second.json()["detail"]
    # and it really did not land
    assert [d["name"] for d in client.get("/api/auth/devices").json()["devices"]] == ["phone"]


def test_relay_password_alone_no_longer_enrols_a_device():
    """The whole point of the code: past Caddy is not past enrolment."""
    client = TestClient(app)
    _require_codes(client)

    _priv, r = _enrol(client, "smuggled")
    assert r.status_code == 403, r.text
    assert "enrolment code is required" in r.json()["detail"]
    assert client.get("/api/auth/devices").json()["devices"] == []


def test_expired_enrolment_code_is_refused(monkeypatch):
    client = TestClient(app)
    _require_codes(client)
    code = client.post("/api/auth/enrol-code").json()["code"]

    monkeypatch.setattr(auth, "_now", lambda: auth.time.time() + auth.ENROL_CODE_TTL_S + 1)
    _priv, r = _enrol(client, "late", enrol_code=code)
    assert r.status_code == 403 and "expired" in r.json()["detail"]


def test_a_guessed_enrolment_code_is_refused():
    client = TestClient(app)
    _require_codes(client)
    client.post("/api/auth/enrol-code")  # a real one exists, but not this one
    _priv, r = _enrol(client, "guesser", enrol_code="definitely-not-the-code")
    assert r.status_code == 403 and "unknown" in r.json()["detail"]


# ---------------------------------------------------------------------------
# Forward auth
# ---------------------------------------------------------------------------

def test_forward_auth_refuses_a_revoked_device():
    """Caddy will trust this verdict for every /api/* request, so it must not
    keep honouring a device the operator has just thrown off the relay."""
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)

    ok = client.get("/api/auth/forward", headers={"Authorization": f"Bearer {tok}"})
    assert ok.status_code == 200 and ok.json()["device_id"] == did
    assert ok.headers["x-device-id"] == did

    assert client.delete(f"/api/auth/devices/{did}").status_code == 200

    gone = client.get("/api/auth/forward", headers={"Authorization": f"Bearer {tok}"})
    assert gone.status_code == 401, gone.text
    assert "revoked" in gone.json()["detail"]


def test_forward_auth_refuses_a_token_whose_device_vanished(monkeypatch):
    """Defence in depth: a tokens.json restored from a backup that predates a
    revocation must not resurrect access."""
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)

    # Devices file emptied out from under us, token store left intact.
    auth._write({"devices": []})
    r = client.get("/api/auth/forward", headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 401, r.text


def test_forward_auth_strict_refuses_the_relay_password():
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    assert client.post("/api/auth/config", json={"forward_auth_mode": "strict"}).status_code == 200

    basic = base64.b64encode(b"relay:hunter2").decode()
    r = client.get("/api/auth/forward", headers={"Authorization": f"Basic {basic}"})
    assert r.status_code == 401, r.text
    assert "no longer accepted" in r.json()["detail"]
    # No Basic challenge either, or the browser just re-prompts forever.
    assert "www-authenticate" not in r.headers

    # The enrolled device still gets through, which is what makes strict usable.
    tok = _token_for(client, priv, did)
    assert client.get("/api/auth/forward",
                      headers={"Authorization": f"Bearer {tok}"}).status_code == 200


def test_forward_auth_basic_or_token_refuses_a_wrong_password(monkeypatch):
    client = TestClient(app)
    _priv, _did = _enrolled(client, "phone")
    monkeypatch.setenv("HUB_BASIC_AUTH_USER", "relay")
    monkeypatch.setenv("HUB_BASIC_AUTH_PASSWORD", "correct-horse")
    assert client.post("/api/auth/config",
                       json={"forward_auth_mode": "basic_or_token"}).status_code == 200

    wrong = base64.b64encode(b"relay:wrong").decode()
    assert client.get("/api/auth/forward",
                      headers={"Authorization": f"Basic {wrong}"}).status_code == 401
    wrong_user = base64.b64encode(b"nobody:correct-horse").decode()
    assert client.get("/api/auth/forward",
                      headers={"Authorization": f"Basic {wrong_user}"}).status_code == 401
    right = base64.b64encode(b"relay:correct-horse").decode()
    assert client.get("/api/auth/forward",
                      headers={"Authorization": f"Basic {right}"}).status_code == 200


def test_basic_or_token_without_a_credential_refuses_rather_than_waving_through():
    """Silently degrading to permissive is the failure this endpoint exists to stop."""
    client = TestClient(app)
    _priv, _did = _enrolled(client, "phone")
    assert client.post("/api/auth/config",
                       json={"forward_auth_mode": "basic_or_token"}).status_code == 200

    basic = base64.b64encode(b"relay:anything").decode()
    r = client.get("/api/auth/forward", headers={"Authorization": f"Basic {basic}"})
    assert r.status_code == 503, r.text
    assert "no basic-auth credential" in r.json()["detail"]


def test_forward_auth_refuses_a_request_with_no_credentials_at_all():
    client = TestClient(app)
    r = client.get("/api/auth/forward")
    assert r.status_code == 401
    # Default mode is permissive, so the browser should still be offered the
    # password prompt rather than a dead end.
    assert r.headers["www-authenticate"].startswith("Basic")


def test_cannot_lock_yourself_out_with_no_devices_enrolled():
    """The remote-operator footgun: strict with an empty device list bricks the API."""
    client = TestClient(app)
    for mode in ("strict", "basic_or_token"):
        r = client.post("/api/auth/config", json={"forward_auth_mode": mode})
        assert r.status_code == 409, r.text
        assert "no devices are enrolled" in r.json()["detail"]
    assert client.get("/api/auth/config").json()["forward_auth_mode"] == "permissive"


def test_env_var_overrides_a_locked_out_config_file():
    """The documented recovery path: SSH in, set the env var, restart."""
    client = TestClient(app)
    _priv, _did = _enrolled(client, "phone")
    assert client.post("/api/auth/config", json={"forward_auth_mode": "strict"}).status_code == 200
    assert auth.config()["forward_auth_mode"] == "strict"

    import os
    os.environ["HUB_FORWARD_AUTH_MODE"] = "permissive"
    try:
        assert auth.config()["forward_auth_mode"] == "permissive"
        basic = base64.b64encode(b"relay:whatever").decode()
        assert client.get("/api/auth/forward",
                          headers={"Authorization": f"Basic {basic}"}).status_code == 200
    finally:
        os.environ.pop("HUB_FORWARD_AUTH_MODE", None)


# ---------------------------------------------------------------------------
# Admin guard on the endpoints that decide who gets in
# ---------------------------------------------------------------------------

def test_revoke_and_config_are_not_open_once_caddy_stops_checking():
    """/api/auth/* sits outside Caddy's forward_auth by necessity -- a device
    cannot present a token before it has one. So the endpoints that can change
    who gets in have to guard themselves, or removing basic_auth from Caddy
    hands them to anyone who finds the URL."""
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    assert client.post("/api/auth/config", json={"forward_auth_mode": "strict"}).status_code == 200

    # No credentials at all: refused now, where under permissive it was allowed.
    assert client.delete(f"/api/auth/devices/{did}").status_code == 401
    assert client.post("/api/auth/config",
                       json={"forward_auth_mode": "permissive"}).status_code == 401
    assert client.post("/api/auth/enrol-code").status_code == 401
    # The relay password is no good either in strict mode.
    basic = {"Authorization": "Basic " + base64.b64encode(b"relay:hunter2").decode()}
    assert client.delete(f"/api/auth/devices/{did}", headers=basic).status_code == 401

    # The enrolled device can still administer, so this is a guard, not a brick.
    tok = _token_for(client, priv, did)
    bearer = {"Authorization": f"Bearer {tok}"}
    assert client.post("/api/auth/enrol-code", headers=bearer).status_code == 200
    assert client.delete(f"/api/auth/devices/{did}", headers=bearer).status_code == 200


def test_a_revoked_device_cannot_administer_with_its_old_token():
    client = TestClient(app)
    priv_a, did_a = _enrolled(client, "phone")
    priv_b, did_b = _enrolled(client, "laptop")
    tok_a = _token_for(client, priv_a, did_a)
    assert client.post("/api/auth/config", json={"forward_auth_mode": "strict"}).status_code == 200

    tok_b = _token_for(client, priv_b, did_b)
    assert client.delete(f"/api/auth/devices/{did_a}",
                         headers={"Authorization": f"Bearer {tok_b}"}).status_code == 200

    stale = {"Authorization": f"Bearer {tok_a}"}
    assert client.post("/api/auth/enrol-code", headers=stale).status_code == 401
    assert client.delete(f"/api/auth/devices/{did_b}", headers=stale).status_code == 401
    # and the surviving device is still there
    assert [d["id"] for d in client.get("/api/auth/devices").json()["devices"]] == [did_b]


# ---------------------------------------------------------------------------
# Token persistence
# ---------------------------------------------------------------------------

def _simulate_restart():
    """Everything a hub restart loses: the module-level dicts."""
    auth._tokens.clear()
    auth._challenges.clear()
    auth._enrol_codes.clear()
    auth._loaded_from = None


def test_a_restart_no_longer_logs_every_device_out():
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)

    _simulate_restart()
    assert auth._tokens == {}  # nothing left in memory: it must come off disk

    who = client.get("/api/auth/whoami", headers={"Authorization": f"Bearer {tok}"}).json()
    assert who["authenticated"] is True and who["device_id"] == did


def test_a_revoked_token_does_not_come_back_after_a_restart():
    """Revocation that only edits memory is undone by the next deploy."""
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)
    assert client.delete(f"/api/auth/devices/{did}").status_code == 200

    _simulate_restart()
    assert client.get("/api/auth/whoami",
                      headers={"Authorization": f"Bearer {tok}"}).json()["authenticated"] is False


def test_the_persisted_token_file_holds_no_usable_token():
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)

    raw = (auth.DATA / "tokens.json").read_text(encoding="utf-8")
    assert tok not in raw, "bearer tokens must be stored hashed, not in the clear"
    assert auth._hash_token(tok) in raw


def test_an_expired_persisted_token_is_not_rehydrated(monkeypatch):
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    tok = _token_for(client, priv, did)

    _simulate_restart()
    monkeypatch.setattr(auth, "_now", lambda: auth.time.time() + auth.TOKEN_TTL_S + 1)
    assert client.get("/api/auth/whoami",
                      headers={"Authorization": f"Bearer {tok}"}).json()["authenticated"] is False


def test_a_corrupt_token_file_does_not_take_the_hub_down():
    """A truncated write (the reason for the atomic pattern) must fail closed, not 500."""
    client = TestClient(app)
    priv, did = _enrolled(client, "phone")
    _token_for(client, priv, did)

    (auth.DATA / "tokens.json").write_text('{"abc": [', encoding="utf-8")
    _simulate_restart()
    r = client.get("/api/auth/whoami", headers={"Authorization": "Bearer anything"})
    assert r.status_code == 200 and r.json()["authenticated"] is False
    # and the device can simply sign in again
    assert _token_for(client, priv, did)
