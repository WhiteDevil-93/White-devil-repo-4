"""The hub mounts its optional modules defensively, so a missing dependency must not be silent.

python-multipart absent used to drop every /api/ltx/* route with one WARNING line; the phone
then showed an LTX screen that answered 404. These keep that from hiding again.
"""
import logging

from fastapi.testclient import TestClient

import app as hub_app
from app import app


def test_every_module_the_hub_expects_is_mounted_in_a_full_install():
    # If this fails, a dependency in requirements.txt is not installed (or a module no longer imports):
    # its routes are not being served.
    assert hub_app.MOUNT_FAILURES == {}, hub_app.MOUNT_FAILURES


def test_the_route_families_behind_the_optional_modules_exist():
    paths = {getattr(r, "path", "") for r in app.routes}
    # include_router flattens into route objects on most versions; fall back to the OpenAPI schema.
    paths |= set(app.openapi()["paths"])
    for prefix in ("/api/ltx/status", "/api/auth/forward", "/api/vast", "/api/laptop", "/api/agentic"):
        assert any(p.startswith(prefix) for p in paths), f"no route under {prefix}"


def test_the_manifest_lists_modules_that_failed_to_mount(caplog):
    client = TestClient(app)
    assert client.get("/api/manifest").json()["failed_modules"] == {}
    try:
        with caplog.at_level(logging.ERROR, logger="forge-hub"):
            hub_app._mount("a_module_that_does_not_exist")
        assert any("NOT mounted" in rec.message and rec.levelno == logging.ERROR for rec in caplog.records)
        failed = client.get("/api/manifest").json()["failed_modules"]
        assert list(failed) == ["a_module_that_does_not_exist"]
        assert "ModuleNotFoundError" in failed["a_module_that_does_not_exist"]
    finally:
        hub_app.MOUNT_FAILURES.pop("a_module_that_does_not_exist", None)
    assert client.get("/api/manifest").json()["failed_modules"] == {}
