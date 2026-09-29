"""Device-bound auth: enrol a public key, sign a challenge, get a token.

The point of these is the negative cases. A happy-path-only suite would pass just
as well against an implementation that accepted any signature at all, which is
the failure mode that matters here.
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
    sig = priv.sign(nonce.encode(), ec.ECDSA(hashes.SHA256()))
    return base64.b64encode(sig).decode()


@pytest.fixture(autouse=True)
def isolate(tmp_path, monkeypatch):
    monkeypatch.setattr(auth, "DATA", tmp_path / "auth_data")
    monkeypatch.setattr(auth, "DEVICES", tmp_path / "auth_data" / "devices.json")
    auth._challenges.clear()
    auth._tokens.clear()
    auth._tokens_src = None
    auth._throttle.clear()
    yield


def _enrol(client, name="laptop"):
    priv, pem = _keypair()
    r = client.post("/api/auth/devices", json={"name": name, "public_key_pem": pem, "enrol_code": auth.mint_enrol_code()})
    assert r.status_code == 200, r.text
    return priv, r.json()["id"]


def test_enrol_sign_and_get_a_token():
    client = TestClient(app)
    priv, did = _enrol(client)

    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = client.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
    assert r.status_code == 200, r.text
    tok = r.json()["token"]

    who = client.get("/api/auth/whoami", headers={"Authorization": f"Bearer {tok}"}).json()
    assert who["authenticated"] is True and who["device_id"] == did


def test_a_different_key_is_rejected():
    """The whole point: holding the device id is not enough without its key."""
    client = TestClient(app)
    _priv, did = _enrol(client)
    attacker, _pem = _keypair()

    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    r = client.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(attacker, nonce)})
    assert r.status_code == 401
    assert "does not match" in r.json()["detail"]


def test_challenge_is_single_use():
    """A captured signature must not be replayable."""
    client = TestClient(app)
    priv, did = _enrol(client)
    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    sig = _sign(priv, nonce)

    assert client.post("/api/auth/token", json={"device_id": did, "signature_b64": sig}).status_code == 200
    replay = client.post("/api/auth/token", json={"device_id": did, "signature_b64": sig})
    assert replay.status_code == 400
    assert "No outstanding challenge" in replay.json()["detail"]


def test_expired_challenge_is_refused(monkeypatch):
    client = TestClient(app)
    priv, did = _enrol(client)
    nonce = client.post("/api/auth/challenge", json={"device_id": did}).json()["nonce"]
    monkeypatch.setattr(auth, "_now", lambda: auth.time.time() + auth.CHALLENGE_TTL_S + 1)
    r = client.post("/api/auth/token", json={"device_id": did, "signature_b64": _sign(priv, nonce)})
    assert r.status_code == 400 and "expired" in r.json()["detail"]


def test_revoking_one_device_leaves_the_other_working():
    client = TestClient(app)
    priv_a, did_a = _enrol(client, "phone")
    priv_b, did_b = _enrol(client, "laptop")

    n_a = client.post("/api/auth/challenge", json={"device_id": did_a}).json()["nonce"]
    tok_a = client.post("/api/auth/token", json={"device_id": did_a, "signature_b64": _sign(priv_a, n_a)}).json()["token"]

    assert client.delete(f"/api/auth/devices/{did_a}").status_code == 200

    # the revoked device's live token stops working immediately
    assert client.get("/api/auth/whoami", headers={"Authorization": f"Bearer {tok_a}"}).json()["authenticated"] is False
    # and it can no longer get a new one
    assert client.post("/api/auth/challenge", json={"device_id": did_a}).status_code == 404
    # while the other device is untouched
    n_b = client.post("/api/auth/challenge", json={"device_id": did_b}).json()["nonce"]
    assert client.post("/api/auth/token", json={"device_id": did_b, "signature_b64": _sign(priv_b, n_b)}).status_code == 200


def test_keys_are_never_listed_and_weak_curves_refused():
    client = TestClient(app)
    _enrol(client)
    listed = client.get("/api/auth/devices").json()["devices"]
    assert listed and all("public_key_pem" not in d for d in listed)

    weak = ec.generate_private_key(ec.SECP192R1()).public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
    ).decode()
    r = client.post("/api/auth/devices", json={"name": "weak", "public_key_pem": weak, "enrol_code": auth.mint_enrol_code()})
    assert r.status_code == 400 and "P-256" in r.json()["detail"]


def test_unknown_device_and_garbage_key_are_clean_errors():
    client = TestClient(app)
    assert client.post("/api/auth/challenge", json={"device_id": "nope"}).status_code == 404
    r = client.post("/api/auth/devices", json={"name": "x", "public_key_pem": "not a key", "enrol_code": auth.mint_enrol_code()})
    assert r.status_code == 400 and "PEM" in r.json()["detail"]
