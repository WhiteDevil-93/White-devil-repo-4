"""LoRA training: datasets, readiness, captions, the run loop against a fake Colab, and the render guard.

No Colab, ComfyUI or OpenRouter here: remote(), colab_put(), colab_get() and ltx.status() are faked, so the run loop
is exercised end to end (upload -> launch -> checkpoint pulls with size checks -> final pull -> done).
"""
import json
import shutil
import subprocess
from pathlib import Path

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

import lora_train as lt
import ltx

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None,
                                reason="uploads are converted with ffmpeg")


def _media(args, suffix):
    import tempfile
    with tempfile.TemporaryDirectory() as td:
        out = Path(td) / f"x{suffix}"
        subprocess.run(["ffmpeg", "-v", "error", "-y", *args, str(out)], check=True)
        return out.read_bytes()


if shutil.which("ffmpeg"):
    JPG = _media(["-f", "lavfi", "-i", "color=c=red:s=64x48", "-frames:v", "1"], ".jpg")
    PNG = _media(["-f", "lavfi", "-i", "color=c=blue:s=64x48", "-frames:v", "1"], ".png")
    MP4 = _media(["-f", "lavfi", "-i", "testsrc=s=64x48:d=1:r=30", "-pix_fmt", "yuv420p"], ".mp4")
    GIF = _media(["-f", "lavfi", "-i", "testsrc=s=64x48:d=1:r=10"], ".gif")
    AVI = _media(["-f", "lavfi", "-i", "testsrc=s=64x48:d=1:r=25"], ".avi")
else:
    JPG = PNG = MP4 = GIF = AVI = b""


@pytest.fixture()
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(lt, "DATASETS", tmp_path / "ds")
    monkeypatch.setattr(lt, "RUNS", tmp_path / "runs")
    monkeypatch.setattr(lt, "TRAINED", tmp_path / "trained")
    monkeypatch.setattr(lt, "colab_rate", lambda: 0.9)
    (tmp_path / "ds").mkdir()
    (tmp_path / "runs").mkdir()
    app = FastAPI()
    app.include_router(lt.router)
    return TestClient(app), tmp_path


def make(client, kind="character", n_img=0, n_vid=0, trigger="ohwx_man"):
    ds = client.post("/api/loratrain/datasets", json={"name": "Test Man", "kind": kind, "trigger": trigger}).json()
    files = [("files", (f"pic{i}.jpg", JPG, "image/jpeg")) for i in range(n_img)]
    files += [("files", (f"clip{i}.mp4", MP4, "video/mp4")) for i in range(n_vid)]
    if files:
        r = client.post(f"/api/loratrain/datasets/{ds['id']}/files", files=files)
        assert r.status_code == 200, r.text
        assert r.json()["added"] == n_img + n_vid, r.json()["skipped"]
    return client.get(f"/api/loratrain/datasets/{ds['id']}").json()


def caption_all(client, ds):
    caps = {i["file"]: "the man stands in a kitchen, medium shot, soft window light" for i in ds["items"]}
    return client.put(f"/api/loratrain/datasets/{ds['id']}/captions", json=caps).json()


def test_new_dataset_is_not_ready_and_says_why(env):
    client, _ = env
    ds = make(client, n_img=3)
    assert not ds["ready"]
    assert any("at least 20" in p for p in ds["problems"])
    assert any("no caption" in p for p in ds["problems"])
    assert any("consent" in w for w in ds["warnings"])
    assert ds["lora"] == "Test_Man" and ds["counts"] == {"images": 3, "videos": 0}


def test_character_ready_with_20_captioned_items(env):
    client, _ = env
    ds = caption_all(client, make(client, n_img=15, n_vid=5))
    ds = client.get(f"/api/loratrain/datasets/{ds['id']}").json()
    assert ds["ready"], ds["problems"]
    assert ds["estimate"]["label"] == "ESTIMATE" and ds["estimate"]["steps"] == 2000
    assert ds["estimate"]["cost_units"] > 0 and "cost_usd" not in ds["estimate"]


def test_character_needs_a_trigger(env):
    client, _ = env
    ds = caption_all(client, make(client, n_img=20, trigger=""))
    assert not ds["ready"] and any("trigger" in p for p in ds["problems"])


def test_motion_needs_video(env):
    client, _ = env
    ds = caption_all(client, make(client, kind="motion", n_img=20))
    assert any("learned from video" in p for p in ds["problems"])
    ds = caption_all(client, make(client, kind="motion", n_vid=15))
    assert ds["ready"], ds["problems"]
    assert ds["estimate"]["steps"] == 3000


def test_any_file_type_is_converted_or_explained(env):
    client, _ = env
    ds = make(client)
    import io
    import zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("inside/frame.png", PNG)
        z.writestr("inside/frame.txt", "the man waves, close-up")
    r = client.post(f"/api/loratrain/datasets/{ds['id']}/files", files=[
        ("files", ("notes.txt", b"orphan caption", "text/plain")),
        ("files", ("my pic (1).PNG", PNG, "image/png")),
        ("files", ("anim.gif", GIF, "image/gif")),
        ("files", ("old.avi", AVI, "video/x-msvideo")),
        ("files", ("junk.bin", b"\x00\x01 not media", "application/octet-stream")),
        ("files", ("pack.zip", buf.getvalue(), "application/zip")),
    ]).json()
    assert r["added"] == 4, r
    assert any("junk.bin" in m for m in r["skipped"])
    items = {Path(i["source"]).name: i for i in r["dataset"]["items"]}
    assert items["my pic (1).PNG"]["file"].startswith("my_pic_1_") and items["my pic (1).PNG"]["file"].endswith(".jpg")
    assert items["anim.gif"]["type"] == "video" and items["anim.gif"]["file"].endswith(".mp4"), "animated gif -> clip"
    assert items["old.avi"]["type"] == "video" and items["old.avi"]["file"].endswith(".mp4")
    assert items["frame.png"]["caption"] == "the man waves, close-up", "a .txt is the caption of the same-named item"
    assert r["captions"] == 1
    fps = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "stream=r_frame_rate",
                          "-of", "csv=p=0", str(lt.DATASETS / ds["id"] / "media" / items["old.avi"]["file"])],
                         capture_output=True, text=True).stdout.strip()
    assert fps == "24/1", "clips are converted to the renders' 24 fps"
    f = items["my pic (1).PNG"]["file"]
    assert client.get(f"/api/loratrain/datasets/{ds['id']}/files/{f}").content[:2] == b"\xff\xd8"


def test_a_caption_file_can_come_before_its_item(env):
    client, _ = env
    ds = make(client)
    client.post(f"/api/loratrain/datasets/{ds['id']}/files", files=[("files", ("a1.txt", b"the man sits", "text/plain"))])
    r = client.post(f"/api/loratrain/datasets/{ds['id']}/files", files=[("files", ("a1.jpg", JPG, "image/jpeg"))]).json()
    assert r["dataset"]["items"][0]["caption"] == "the man sits"


def test_train_refuses_an_unready_dataset(env):
    client, _ = env
    ds = make(client, n_img=2)
    r = client.post(f"/api/loratrain/datasets/{ds['id']}/train")
    assert r.status_code == 400 and "at least 20" in r.json()["detail"]


def test_settings_patch(env):
    client, _ = env
    ds = make(client)
    out = client.patch(f"/api/loratrain/datasets/{ds['id']}", json={"steps": 50, "rank": 64, "trigger": "zz man"}).json()
    assert out["settings"] == {"steps": 250, "rank": 64} and out["trigger"] == "zz_man"
    assert client.patch(f"/api/loratrain/datasets/{ds['id']}", json={"rank": 7}).status_code == 400
    out = client.post(f"/api/loratrain/datasets/{ds['id']}/settings", json={"steps": 1200}).json()
    assert out["settings"]["steps"] == 1200, "POST alias for clients without PATCH"


def test_progress_parsing():
    log = "TRAIN_STAGE: getting the models\nTRAIN_STAGE: training 2000 steps\nTraining:  12%| step 240/2000 loss 0.1"
    assert lt.progress(log) == ("training 2000 steps", 240, 2000)


def test_full_run_against_a_fake_colab(env, monkeypatch):
    client, tmp = env
    ds = caption_all(client, make(client, n_img=20))
    calls, uploads = [], []
    ckpt = "/content/workspace/train/runs/X/out/checkpoints/lora_weights_step_00250.safetensors"
    final = "/content/workspace/train/runs/X/out/lora_weights.safetensors"
    logs = iter([
        "TRAIN_STAGE: training 2000 steps\nstep 250/2000\nCKPT %s 1000\n__ALIVE__" % ckpt,
        "TRAIN_STAGE: training 2000 steps\nstep 2000/2000\nCKPT %s 1000\nFINAL %s 2048\nTRAIN_COMPLETE Test_Man\n__DEAD__"
        % (ckpt, final),
    ])

    class R:
        def __init__(self, out="", rc=0):
            self.stdout, self.stderr, self.returncode = out, "", rc

    def remote(cmd, timeout=240):
        calls.append(cmd)
        if "echo launched" in cmd:
            return R("launched\n")
        if "tail -c" in cmd:
            return R(next(logs))
        return R()

    def put(src, dst, timeout=900):
        uploads.append((Path(src).name, dst))
        if Path(src).name == "dataset.json":
            uploads.append(("__json__", json.loads(Path(src).read_text())))
        if Path(src).name == "env":
            uploads.append(("__env__", Path(src).read_text()))
        return R()

    def get(src, dst, timeout=1800):
        Path(dst).parent.mkdir(parents=True, exist_ok=True)
        Path(dst).write_bytes(b"x" * (2048 if src == final else 1000))
        return R()

    monkeypatch.setattr(lt, "remote", remote)
    monkeypatch.setattr(lt, "colab_put", put)
    monkeypatch.setattr(lt, "colab_get", get)
    monkeypatch.setattr(lt.time, "sleep", lambda s: None)
    monkeypatch.setattr(ltx, "status", lambda: {"online": True})
    import setupbot
    monkeypatch.setattr(setupbot, "secret", lambda name: "hf_fake")
    monkeypatch.setattr(lt, "start", lambda rid: lt.work(rid))

    run = client.post(f"/api/loratrain/datasets/{ds['id']}/train").json()
    run = client.get(f"/api/loratrain/runs/{run['id']}").json()
    assert run["status"] == "done", run
    assert run["final"]["verified"] and Path(run["final"]["local"]).stat().st_size == 2048
    assert len(run["pulled"]) == 1 and run["pulled"][0]["verified"], "the checkpoint is pulled once, size-checked"
    names = [u[0] for u in uploads]
    assert names.count("train.sh") == 1 and names.count("env") == 1 and sum(n.endswith(".jpg") for n in names) == 20
    entries = next(u[1] for u in uploads if u[0] == "__json__")
    assert len(entries) == 20 and all(e["caption"] and e["video"].startswith("/content/workspace/train/runs/") for e in entries)
    assert all(e["video"].endswith(".jpg") for e in entries)
    envtxt = next(u[1] for u in uploads if u[0] == "__env__")
    assert "export TRIGGER=ohwx_man" in envtxt and "export STEPS=2000" in envtxt and "export FF=1" in envtxt
    assert "HF_TOKEN=hf_fake" in envtxt
    assert not lt.training_now()


def test_a_lost_colab_fails_the_run_but_keeps_pulls(env, monkeypatch):
    client, _ = env
    ds = caption_all(client, make(client, n_img=20))
    rid = "abcdefabcdef"
    lt.save_run({"id": rid, "dataset": ds["id"], "status": "training", "launched": 1, "created": 1, "pulled": []})

    class R:
        stdout, stderr, returncode = "", "boom", 1

    monkeypatch.setattr(lt, "remote", lambda cmd, timeout=240: R())
    monkeypatch.setattr(lt.time, "sleep", lambda s: None)
    lt.work(rid)
    run = lt.load_run(rid)
    assert run["status"] == "failed" and "Lost contact" in run["step"]


def test_renders_refuse_while_training(env, monkeypatch):
    lt.save_run({"id": "aaaaaaaaaaaa", "dataset": "x", "status": "training", "created": 1})
    with pytest.raises(HTTPException) as e:
        ltx.submit(None, "x.png", "prompt", 49, "landscape", 1, ltx.norm_opts({}), "p")
    assert e.value.status_code == 409 and "training" in e.value.detail


@pytest.mark.skipif(shutil.which("bash") is None, reason="needs bash")
def test_train_script_parses_and_has_the_markers():
    script = Path(lt.SCRIPT)
    text = script.read_text(encoding="utf-8")
    assert subprocess.run(["bash", "-n"], input=text.encode()).returncode == 0
    for marker in ("TRAIN_STAGE:", "echo \"CKPT ", "echo \"FINAL ", "TRAIN_COMPLETE", "FATAL:", "--skip-audio",
                   "--video-vae-path", "--lora-trigger", "type: first_frame", "ltx-2.5-22b-dev-transformer-bf16"):
        assert marker in text, marker
    assert "\r\n" not in text


def test_concept_kind_for_anatomy_or_positions(env):
    client, _ = env
    ds = make(client, kind="concept", n_img=15, trigger="")
    ds = client.get(f"/api/loratrain/datasets/{ds['id']}").json()
    assert any("trigger word" in p and "zxc_pose" in p for p in ds["problems"])
    assert any("at least 20" in p for p in ds["problems"])
    assert any("many different people" in w for w in ds["warnings"])
    ds = caption_all(client, make(client, kind="concept", n_img=18, n_vid=2, trigger="zxc_pose"))
    ds = client.get(f"/api/loratrain/datasets/{ds['id']}").json()
    assert ds["ready"], ds["problems"]
    assert ds["estimate"]["steps"] == 2500 and ds["kind_info"]["label"] == "Anatomy / pose"
    assert lt.KINDS["concept"]["ff"] and "x1" in lt.KINDS["concept"]["buckets"], "stills allowed, shape layers on"
    assert "ONE body part or ONE position" in lt.CAPTION_RULES["concept"]
