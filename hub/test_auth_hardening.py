"""Deployment hardening for device auth: throttling, enrolment codes, forward auth,
token persistence.

As in test_auth_devices.py the value is in the refusals. Each test states the attack
or mistake it closes, and most assert a *second* party is unaffected, because a lockout
that also locks out the legitimate device is its own outage.
"""
import base64
import json

import pytest
from fastapi.testclient import TestClient

import auth
from app import app

ec = pytest.importorskip("cryptography.hazmat.primitives.asymmetric.ec")
bcrypt = pytest.importorskip("bcrypt")
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
    monkeypatch.delenv("HUB_AUTH_MODE", raising=False)
    monkeypatch.setenv("HUB_BASIC_AUTH_FILE", str(tmp_path / "basic_users"))
    clock = Clock()
    monkeypatch.setattr(auth, "_now", clock)
    auth._challenges.clear()
    auth._tokens.clear()
    auth._tokens_src = None
    auth._basic_sig = None
    auth._basic_users.clear()
    auth._basic_ok.clear()
    auth._throttle.clear()
    yield clock


@pytest.fixture
def client():
    # Peer is a non-loopback address, like a direct hit on the hub.
    return TestClient(app, client=("203.0.113.9", 4000))


def _from(ip):
    """A request as Caddy relays it: loopback peer, real client in X-Forwarded-For."""
    return TestClient(app, client=("127.0.0.1", 4000), headers={"X-Forwarded-For": ip})


def _enrol(c, name="phone"):
    priv, pem = _keypair()
    r = c.post("/api/auth/devices", json={"name": name, "public_key_pem": pem, "enrol_code": auth.mint_enrol_code()})
    assert r.status_code == 200, r.text
    return priv, r.json()["id"], pem


def _login(c, priv, did):
    n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, n)})
    assert r.status_code == 200, r.text
    return r.json()["token"]


def _bearer(t):
    return {"Authorization": f"Bearer {t}"}


def _basic(user="wan", pw="hunter2"):
    return {"Authorization": "Basic " + base64.b64encode(f"{user}:{pw}".encode()).decode()}


def _install_basic(tmp_path, user="wan", pw="hunter2"):
    h = bcrypt.hashpw(pw.encode(), bcrypt.gensalt(rounds=4)).decode()
    (tmp_path / "basic_users").write_text(f"{user}:{h}\n")


# ------------------------------------------------------------------ throttling

def test_challenge_flood_for_one_device_gets_429_and_spares_others(client, env):
    _p1, victim, _ = _enrol(client, "phone")
    _p2, other, _ = _enrol(client, "laptop")
    limit = auth.RATE_CHALLENGE_DEVICE[0]
    for _ in range(limit):
        assert client.post("/api/auth/challenge", json={"device_id": victim}).status_code == 200
    r = client.post("/api/auth/challenge", json={"device_id": victim})
    assert r.status_code == 429
    assert int(r.headers["Retry-After"]) >= 1
    assert "retry in" in r.json()["detail"]
    # the other device is unaffected by the flood
    assert client.post("/api/auth/challenge", json={"device_id": other}).status_code == 200
    # and the window reopens
    env.t += auth.RATE_CHALLENGE_DEVICE[1] + 1
    assert client.post("/api/auth/challenge", json={"device_id": victim}).status_code == 200


def test_challenge_flood_from_one_ip_is_capped_across_devices(client, env, monkeypatch):
    monkeypatch.setattr(auth, "RATE_CHALLENGE_IP", (3, 60))
    ids = [_enrol(client, f"d{i}")[1] for i in range(4)]
    codes = [client.post("/api/auth/challenge", json={"device_id": d}).status_code for d in ids]
    assert codes == [200, 200, 200, 429]
    # a different client address is not throttled by this one's behaviour
    assert _from("198.51.100.7").post("/api/auth/challenge", json={"device_id": ids[3]}).status_code == 200


def test_bad_signatures_lock_the_attacker_not_the_real_device(env):
    real = _from("198.51.100.1")
    priv, did, _ = _enrol(real)
    attacker_key, _ = _keypair()
    attacker = _from("203.0.113.66")
    max_fails = auth.LOCK_DEVICE_IP[0]
    for _ in range(max_fails):
        n = attacker.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
        r = attacker.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(attacker_key, n)})
        assert r.status_code == 401
    # attacker is now locked out of this device...
    r = attacker.post("/api/auth/token", json={"device_id": did, "signature_b64": "AAAA"})
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0
    # ...but the genuine device, from its own address, signs in fine
    assert _login(real, priv, did)


def test_lockout_holds_even_against_a_correct_signature_then_expires(env):
    c = _from("203.0.113.5")
    priv, did, _ = _enrol(c)
    wrong, _ = _keypair()
    for _ in range(auth.LOCK_DEVICE_IP[0]):
        n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
        c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(wrong, n)})
    n = c.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    good = c.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, n)})
    assert good.status_code == 429  # locked means locked
    env.t += auth.LOCK_DEVICE_IP[2] + 1
    assert _login(c, priv, did)


def test_probing_for_device_ids_locks_the_ip(client):
    for i in range(auth.LOCK_IP[0]):
        assert client.post("/api/auth/challenge", json={"device_id": f"guess{i}"}).status_code == 404
    assert client.post("/api/auth/challenge", json={"device_id": "guess-next"}).status_code == 429


def test_spoofed_forwarded_for_cannot_dodge_the_limit(client, monkeypatch):
    """Non-loopback peer: X-Forwarded-For is attacker-controlled and must be ignored."""
    monkeypatch.setattr(auth, "RATE_CHALLENGE_IP", (2, 60))
    _p, did, _ = _enrol(client)
    statuses = [
        client.post("/api/auth/challenge", json={"device_id": did}, headers={"X-Forwarded-For": f"10.0.0.{i}"}).status_code
        for i in range(3)
    ]
    assert statuses == [200, 200, 429]


def test_behind_caddy_a_client_prefixed_forwarded_for_entry_is_ignored(monkeypatch):
    """Caddy appends the real address LAST; anything before it came from the client."""
    monkeypatch.setattr(auth, "RATE_CHALLENGE_IP", (2, 60))
    c = TestClient(app, client=("127.0.0.1", 1))
    _p, did, _ = _enrol(c)
    res = [
        c.post("/api/auth/challenge", json={"device_id": did}, headers={"X-Forwarded-For": f"1.2.3.{i}, 203.0.113.50"}).status_code
        for i in range(3)
    ]
    assert res == [200, 200, 429]


# ------------------------------------------------------------------ enrolment codes

def _enrol_raw(c, code, pem=None):
    pem = pem or _keypair()[1]
    body = {"name": "x", "public_key_pem": pem}
    if code is not None:
        body["enrol_code"] = code
    return c.post("/api/auth/devices", json=body)


def test_enrolment_without_a_code_is_refused(client):
    """The leaked-password scenario: everything except the code is right."""
    assert _enrol_raw(client, None).status_code == 422
    r = _enrol_raw(client, "ABCDE-FGHJK")
    assert r.status_code == 403
    assert client.get("/api/auth/devices").json()["devices"] == []


def test_an_enrolment_code_is_single_use(client):
    code = auth.mint_enrol_code()
    _priv, pem = _keypair()
    assert _enrol_raw(client, code, pem).status_code == 200
    second = _enrol_raw(client, code)
    assert second.status_code == 403 and "already used" in second.json()["detail"]
    assert len(client.get("/api/auth/devices").json()["devices"]) == 1


def test_an_expired_enrolment_code_is_refused(client, env):
    code = auth.mint_enrol_code()
    env.t += auth.ENROL_CODE_TTL_S + 1
    assert _enrol_raw(client, code).status_code == 403


def test_code_is_case_and_dash_insensitive_but_not_guessable_by_prefix(client):
    code = auth.mint_enrol_code()
    assert _enrol_raw(client, code[:5]).status_code == 403          # half a code
    assert _enrol_raw(client, code.lower().replace("-", " ")).status_code == 200


def test_a_bad_key_does_not_burn_the_code(client):
    code = auth.mint_enrol_code()
    r = client.post("/api/auth/devices", json={"name": "x", "public_key_pem": "junk", "enrol_code": code})
    assert r.status_code == 400
    assert _enrol_raw(client, code).status_code == 200  # still spendable


def test_a_duplicate_key_does_not_burn_the_code(client):
    _p, _d, pem = _enrol(client)
    code = auth.mint_enrol_code()
    assert _enrol_raw(client, code, pem).status_code == 409
    assert _enrol_raw(client, code).status_code == 200


def test_two_racing_spends_of_one_code_yield_exactly_one_winner():
    code = auth.mint_enrol_code()
    assert [auth._consume_code(code), auth._consume_code(code)] == [True, False]


def test_guessing_codes_locks_the_guesser(client):
    good = auth.mint_enrol_code()
    for _ in range(auth.LOCK_IP[0]):
        _enrol_raw(client, "WRONG-GUESS")
    r = _enrol_raw(client, good)
    assert r.status_code == 429  # even the right code, from the locked address


def test_distributed_guessing_voids_every_live_code(env):
    """Guessing from many addresses dodges per-IP lockout, so the global counter burns the codes."""
    live = auth.mint_enrol_code()
    for i in range(auth.LOCK_CODE_GLOBAL[0]):
        assert _enrol_raw(_from(f"203.0.113.{i}"), f"NOPE{i}").status_code == 403
    r = _enrol_raw(_from("198.51.100.200"), live)
    assert r.status_code == 429  # enrolment paused globally
    env.t += auth.LOCK_CODE_GLOBAL[2] + 1
    assert _enrol_raw(_from("198.51.100.200"), live).status_code == 403  # the code is gone, not merely paused


def test_minting_needs_a_device_token_not_the_shared_password(client, tmp_path):
    _install_basic(tmp_path)
    assert client.post("/api/auth/enrol-codes").status_code == 401
    # the leaked-password case: valid basic auth must NOT mint codes
    assert client.post("/api/auth/enrol-codes", headers=_basic()).status_code == 401
    assert client.post("/api/auth/enrol-codes", headers=_bearer("forged")).status_code == 401


def test_an_enrolled_device_can_mint_a_code_for_the_next_one_and_is_rate_limited(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    r = client.post("/api/auth/enrol-codes", headers=_bearer(tok))
    assert r.status_code == 200
    assert _enrol_raw(client, r.json()["code"]).status_code == 200
    for _ in range(auth.RATE_MINT_DEVICE[0] - 1):
        assert client.post("/api/auth/enrol-codes", headers=_bearer(tok)).status_code == 200
    assert client.post("/api/auth/enrol-codes", headers=_bearer(tok)).status_code == 429


def test_a_revoked_device_cannot_mint(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    client.delete(f"/api/auth/devices/{did}")
    assert client.post("/api/auth/enrol-codes", headers=_bearer(tok)).status_code == 401


# ------------------------------------------------------------------ forward auth

def test_verify_refuses_anonymous_and_advertises_basic_while_permissive(client):
    r = client.get("/api/auth/verify")
    assert r.status_code == 401
    assert r.headers["WWW-Authenticate"].startswith("Basic")


def test_verify_accepts_a_device_token_and_names_the_device(client):
    priv, did, _ = _enrol(client)
    r = client.get("/api/auth/verify", headers=_bearer(_login(client, priv, did)))
    assert r.status_code == 200
    assert r.headers["X-Device-Id"] == did and r.headers["X-Auth-Method"] == "device"


def test_verify_rejects_forged_and_schemeless_tokens(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    assert client.get("/api/auth/verify", headers=_bearer("not-a-token")).status_code == 401
    assert client.get("/api/auth/verify", headers={"Authorization": tok}).status_code == 401  # no scheme


def test_verify_rejects_an_expired_token(client, env):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 200
    env.t += auth.TOKEN_TTL_S + 1
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401


def test_verify_rejects_a_revoked_devices_token(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    client.delete(f"/api/auth/devices/{did}")
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401


def test_verify_permissive_accepts_the_legacy_password_and_rejects_a_wrong_one(client, tmp_path):
    _install_basic(tmp_path)
    ok = client.get("/api/auth/verify", headers=_basic())
    assert ok.status_code == 200 and ok.headers["X-Auth-Method"] == "basic"
    assert client.get("/api/auth/verify", headers=_basic(pw="wrong")).status_code == 401
    assert client.get("/api/auth/verify", headers=_basic(user="root")).status_code == 401
    assert client.get("/api/auth/verify", headers={"Authorization": "Basic !!!notb64"}).status_code == 401


def test_a_base64_wrapped_caddyfile_hash_is_accepted_as_is(client, tmp_path):
    h = bcrypt.hashpw(b"hunter2", bcrypt.gensalt(rounds=4))
    (tmp_path / "basic_users").write_text("wan:" + base64.b64encode(h).decode() + "\n")
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 200
    assert client.get("/api/auth/verify", headers=_basic(pw="nope")).status_code == 401


def test_verify_with_no_basic_file_refuses_basic_rather_than_admitting_everyone(client):
    """Misconfiguration must fail closed for the password path."""
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 401


def test_verify_device_only_refuses_the_password_but_still_takes_tokens(client, tmp_path, monkeypatch):
    _install_basic(tmp_path)
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    monkeypatch.setenv("HUB_AUTH_MODE", "device-only")
    r = client.get("/api/auth/verify", headers=_basic())
    assert r.status_code == 401 and r.headers["WWW-Authenticate"].startswith("Bearer")
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 200


def test_unrecognised_mode_is_permissive_not_a_lockout(client, tmp_path, monkeypatch):
    _install_basic(tmp_path)
    monkeypatch.setenv("HUB_AUTH_MODE", "device_onyl")
    assert auth.auth_mode() == "permissive"
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 200


def test_removing_a_basic_user_takes_effect_despite_the_success_cache(client, tmp_path):
    _install_basic(tmp_path)
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 200
    (tmp_path / "basic_users").write_text("")
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 401


def test_password_guessing_through_verify_locks_that_ip_only(tmp_path):
    _install_basic(tmp_path)
    attacker, phone = _from("203.0.113.66"), _from("198.51.100.4")
    for _ in range(auth.LOCK_IP[0]):
        assert attacker.get("/api/auth/verify", headers=_basic(pw="guess")).status_code == 401
    r = attacker.get("/api/auth/verify", headers=_basic())      # right password, locked address
    assert r.status_code == 429 and int(r.headers["Retry-After"]) > 0
    assert phone.get("/api/auth/verify", headers=_basic()).status_code == 200


def test_anonymous_requests_never_trip_the_lockout(client, tmp_path):
    """A browser's first hit carries no credentials; counting it would lock out the phone."""
    _install_basic(tmp_path)
    for _ in range(auth.LOCK_IP[0] * 3):
        assert client.get("/api/auth/verify").status_code == 401
    assert client.get("/api/auth/verify", headers=_basic()).status_code == 200


def test_device_only_mode_makes_the_hub_itself_guard_list_and_revoke(client, monkeypatch):
    """If a Caddy matcher is wrong, revocation must still not be open to the internet."""
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    monkeypatch.setenv("HUB_AUTH_MODE", "device-only")
    assert client.get("/api/auth/devices").status_code == 401
    assert client.delete(f"/api/auth/devices/{did}").status_code == 401
    assert client.get("/api/auth/devices", headers=_bearer(tok)).status_code == 200
    assert client.delete(f"/api/auth/devices/{did}", headers=_bearer(tok)).status_code == 200


def test_require_device_is_applied_to_no_existing_route():
    """Additive contract: the operator, not this change, decides when routes enforce tokens."""
    guarded = set()
    for route in app.routes:
        deps = getattr(getattr(route, "dependant", None), "dependencies", [])
        if any(getattr(d, "call", None) is auth.require_device for d in deps):
            guarded.add(route.path)
    assert guarded == set()


# ------------------------------------------------------------------ persistence

def _restart():
    """What a hub restart does to auth state: memory gone, disk kept."""
    auth._challenges.clear()
    auth._tokens.clear()
    auth._tokens_src = None
    auth._throttle.clear()


def test_tokens_survive_a_hub_restart(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    _restart()
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 200


def test_the_token_file_holds_hashes_never_the_bearer_value(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    text = (auth.DATA / "tokens.json").read_text()
    assert tok not in text and auth._hash_token(tok) in text
    assert not list(auth.DATA.glob("*.tmp"))  # atomic write left nothing behind


def test_expired_tokens_are_not_resurrected_by_a_restart(client, env):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    env.t += auth.TOKEN_TTL_S + 1
    _restart()
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401


def test_revoking_before_a_restart_stays_revoked_after(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    client.delete(f"/api/auth/devices/{did}")
    _restart()
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401


def test_a_device_removed_while_the_hub_was_down_cannot_use_its_persisted_token(client):
    """Operator edits devices.json / runs the CLI offline; the token file still lists the token."""
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    auth._write({"devices": []})
    _restart()
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401


def test_a_corrupt_token_file_logs_devices_out_but_does_not_break_the_hub(client):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    (auth.DATA / "tokens.json").write_text("{ not json")
    _restart()
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401
    assert _login(client, priv, did)  # and signing in again works, rewriting the file
    _restart()
    assert json.loads((auth.DATA / "tokens.json").read_text())["tokens"]


def test_a_token_file_with_garbage_entries_is_survivable(client):
    priv, did, _ = _enrol(client)
    (auth.DATA / "tokens.json").write_text(json.dumps({"tokens": {"a": "nope", "b": [1, 2], "c": None}}))
    _restart()
    assert _login(client, priv, did)


def test_cli_mint_code_produces_a_spendable_single_use_code(client, capsys):
    assert auth._cli(["mint-code"]) == 0
    code = capsys.readouterr().out.strip()
    assert _enrol_raw(client, code).status_code == 200
    assert _enrol_raw(client, code).status_code == 403


def test_cli_revoke_works_offline_and_reports_unknown_ids(client, capsys):
    priv, did, _ = _enrol(client)
    tok = _login(client, priv, did)
    assert auth._cli(["revoke", "nosuchid"]) == 1
    assert auth._cli(["revoke", did]) == 0
    assert client.get("/api/auth/verify", headers=_bearer(tok)).status_code == 401
