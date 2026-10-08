import civitai_red_dl
from civitai_red_dl import parse_ids, pick_files
from pathlib import Path


def test_deployed_copy_matches_authoritative_downloader():
    deployed = Path(__file__).resolve().parents[1] / "hub/static/term/civitai_red_dl.py"
    assert deployed.read_bytes() == Path(civitai_red_dl.__file__).read_bytes()


def test_civitai_requests_keep_tls_verification_enabled(monkeypatch):
    seen = {}

    def fake_get(url, **kwargs):
        seen.update(kwargs)
        return object()

    monkeypatch.setattr(civitai_red_dl.requests, "get", fake_get)
    civitai_red_dl.get("https://civitai.red/api/v1/models/123", "synthetic-token")
    assert seen.get("verify", True) is True


def test_parse_ids_urls_and_commas():
    assert parse_ids(["2851705, 123", "https://civitai.com/models/999/foo"]) == [
        "2851705",
        "123",
        "999",
    ]


def test_pick_files_all_versions_not_just_ltx25():
    model = {
        "id": 1,
        "modelVersions": [
            {
                "id": 10,
                "baseModel": "LTXV 2.5",
                "files": [
                    {"id": 1, "name": "penis-ltx25.safetensors", "primary": True, "type": "Model"},
                ],
            },
            {
                "id": 11,
                "baseModel": "Wan Video 2.2 TI2V-5B",
                "files": [
                    {"id": 2, "name": "penis-wan22.safetensors", "primary": True, "type": "Model"},
                ],
            },
            {
                "id": 12,
                "baseModel": "LTXV 2.5",
                "files": [
                    {"id": 3, "name": "penis-ltx25-high.safetensors", "primary": False, "type": "Model"},
                    {"id": 4, "name": "penis-ltx25-low.safetensors", "primary": True, "type": "Model"},
                ],
            },
        ],
    }
    all_files = pick_files(model)
    names = [f["name"] for _, f in all_files]
    assert names == [
        "penis-ltx25.safetensors",
        "penis-wan22.safetensors",
        "penis-ltx25-low.safetensors",
        "penis-ltx25-high.safetensors",
    ]
    primary = pick_files(model, primary_only=True)
    assert [f["name"] for _, f in primary] == ["penis-ltx25.safetensors"]


def test_pick_files_skips_checkpoints_unless_weights():
    model = {
        "id": 2,
        "modelVersions": [
            {
                "id": 20,
                "baseModel": "LTXV 2.5",
                "files": [
                    {"id": 1, "name": "concept.safetensors", "primary": True, "type": "Model"},
                    {"id": 2, "name": "ltx25_dev.safetensors", "type": "Diffusion Model"},
                    {"id": 3, "name": "t5.safetensors", "type": "Text Encoder"},
                ],
            }
        ],
    }
    names = [f["name"] for _, f in pick_files(model)]
    assert names == ["concept.safetensors"]
    with_weights = [f["name"] for _, f in pick_files(model, include_weights=True)]
    assert "ltx25_dev.safetensors" in with_weights
    assert "t5.safetensors" in with_weights


class FakeResponse:
    """Minimal stand-in for requests.Response for save()/open_stream()."""

    def __init__(self, body, total, status_code=200, headers=None):
        self.body = body
        self.status_code = status_code
        self.url = "https://civitai.red/api/download/models/10?token=SECRET"
        self.headers = {"content-type": "application/octet-stream", "content-length": str(len(body))}
        if status_code == 206:
            start = total - len(body)
            self.headers["content-range"] = f"bytes {start}-{total - 1}/{total}"
        if headers:
            self.headers.update(headers)

    def iter_content(self, _size):
        yield self.body

    def close(self):
        pass


def test_mask_hides_token():
    assert civitai_red_dl.mask("https://h/f?type=Model&token=SECRET&fileId=3") == (
        "https://h/f?type=Model&token=***&fileId=3"
    )


def test_truncated_download_is_not_promoted_and_resumes(tmp_path, monkeypatch):
    import pytest

    dest = tmp_path / "lora.safetensors"
    full = b"A" * 10 + b"B" * 6

    # first attempt: the connection drops after 10 of 16 bytes
    def first(url, tok, stream=False, extra=None):
        return FakeResponse(full[:10], 16, 200, {"content-length": "16"})

    monkeypatch.setattr(civitai_red_dl, "get", first)
    with pytest.raises(SystemExit) as e:
        civitai_red_dl.save("https://h/f", dest, "tok", None)
    assert "incomplete download" in str(e.value)
    assert not dest.exists()                                   # never promoted
    assert (tmp_path / "lora.safetensors.part").read_bytes() == full[:10]

    # second attempt: resumes from byte 10 with a range request
    seen = {}

    def second(url, tok, stream=False, extra=None):
        seen["range"] = (extra or {}).get("Range")
        return FakeResponse(full[10:], 16, 206)

    monkeypatch.setattr(civitai_red_dl, "get", second)
    civitai_red_dl.save("https://h/f", dest, "tok", None)
    assert seen["range"] == "bytes=10-"
    assert dest.read_bytes() == full
    assert not (tmp_path / "lora.safetensors.part").exists()
