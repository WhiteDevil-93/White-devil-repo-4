"""Sharpen 2x on silent clips.

Renders are saved picture-only, so most clips have no audio track. The sharpen graph used to encode the source's
audio unconditionally and ComfyUI failed with "VAEEncodeAudio: input audio is None". These tests build the graph
without a ComfyUI (the model lists are faked) and check both the silent and the sound case, plus the audio probe
against real files made with ffmpeg.
"""
import shutil
import subprocess

import pytest

import ltx

FAKE = {
    "UNETLoader": ["ltx-2.5-distilled-Stubelius.safetensors"],
    "LoraLoaderModelOnly": [],
    "CLIPLoader": ["gemma4-12b-with-proj-ltx-2.5-bf16.safetensors"],
    "VAELoader": ["ltx-2.5-video-vae.safetensors", "ltx-2.5-audio-vae.safetensors"],
}


@pytest.fixture(autouse=True)
def no_comfy(monkeypatch):
    monkeypatch.setattr(ltx, "choices", lambda node, field: FAKE.get(node, []))


def build(audio):
    return ltx.sharpen_graph("in.mp4", "a calm lake", 121, 7, ltx.job_opts({}), "ltx/hd_test_01", audio=audio)


def test_silent_clip_uses_the_empty_audio_latent_and_saves_silent():
    g = build(audio=False)
    assert g["35"]["class_type"] == "LTXVEmptyLatentAudio"
    assert g["35"]["inputs"]["frames_number"] == 121
    assert g["35"]["inputs"]["audio_vae"] == ["9", 0]
    assert "audio" not in g["42"]["inputs"], "a silent clip comes back silent"
    assert g["36"]["inputs"]["audio_latent"] == ["35", 0]
    # nothing in the graph reads the (missing) audio of the source any more
    assert all(["31", 1] not in node["inputs"].values() for node in g.values())


def test_clip_with_sound_keeps_its_sound():
    g = build(audio=True)
    assert g["35"]["class_type"] == "LTXVAudioVAEEncode"
    assert g["35"]["inputs"]["audio"] == ["31", 1]
    assert g["42"]["inputs"]["audio"] == ["31", 1]


def test_default_is_the_old_behaviour():
    assert ltx.sharpen_graph("in.mp4", "x", 121, 1, ltx.job_opts({}), "p")["35"]["class_type"] == "LTXVAudioVAEEncode"


def test_the_base_render_graph_is_untouched():
    g, _ = ltx.graph("unused.png", "x", 121, 768, 512, 1, ltx.job_opts({}), "p")
    assert g["15"]["class_type"] == "LTXVEmptyLatentAudio" and g["25"]["inputs"]["audio"] == ["24", 0]


@pytest.mark.skipif(shutil.which("ffmpeg") is None or shutil.which("ffprobe") is None, reason="needs ffmpeg and ffprobe")
def test_has_audio_on_real_files(tmp_path):
    silent, loud = tmp_path / "silent.mp4", tmp_path / "loud.mp4"
    base = ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=black:s=64x64:d=1"]
    subprocess.run(base + ["-an", str(silent)], check=True)
    subprocess.run(base + ["-f", "lavfi", "-i", "sine=f=440:d=1", "-shortest", str(loud)], check=True)
    assert ltx.has_audio(silent) is False
    assert ltx.has_audio(loud) is True


def test_has_audio_assumes_sound_when_it_cannot_tell(tmp_path, monkeypatch):
    def boom(*a, **k):
        raise FileNotFoundError("ffprobe")
    monkeypatch.setattr(ltx.subprocess, "run", boom)
    assert ltx.has_audio(tmp_path / "x.mp4") is True
