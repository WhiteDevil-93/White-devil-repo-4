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
    "LatentUpscaleModelLoader": [ltx.UPSCALER],
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
    g, _ = ltx.graph("unused.png", "x", 121, 768, 512, 1, ltx.job_opts({}), "p", two_stage=False)
    assert g["15"]["class_type"] == "LTXVEmptyLatentAudio" and g["25"]["inputs"]["audio"] == ["24", 0]
    assert "60" not in g and g["13"]["inputs"]["width"] == 768


def test_two_stage_is_the_default_render():
    g, _ = ltx.graph("in.png", "x", 121, 768, 512, 1, ltx.job_opts({}), "p")
    assert (g["13"]["inputs"]["width"], g["13"]["inputs"]["height"]) == (384, 256), "stage 1 at half size"
    assert g["61"]["inputs"]["samples"] == ["22", 0] and g["60"]["inputs"]["model_name"] == ltx.UPSCALER
    assert g["62"]["inputs"]["strength"] == 1.0 and g["62"]["inputs"]["latent"] == ["61", 0]
    assert g["63"]["inputs"]["audio_latent"] == ["22", 1]
    assert g["65"]["inputs"]["sigmas"] == "0.909375, 0.725, 0.421875, 0.0"
    assert g["23"]["inputs"]["samples"] == ["67", 0] and g["24"]["inputs"]["samples"] == ["67", 1]
    for w, h in ltx.SIZES.values():
        assert (w // 2) % 32 == 0 and (h // 2) % 32 == 0


def test_two_stage_text_to_video_has_no_image_node():
    g, _ = ltx.graph(None, "x", 121, 768, 512, 1, ltx.job_opts({}), "p")
    assert "62" not in g and g["63"]["inputs"]["video_latent"] == ["61", 0]


def test_one_stage_when_the_upscaler_is_missing_or_turned_off(monkeypatch):
    g, _ = ltx.graph("in.png", "x", 121, 768, 512, 1, ltx.norm_opts({"two_stage": False}), "p")
    assert "60" not in g
    monkeypatch.setattr(ltx, "choices", lambda node, field: [] if node == "LatentUpscaleModelLoader" else FAKE.get(node, []))
    g, _ = ltx.graph("in.png", "x", 121, 768, 512, 1, ltx.job_opts({}), "p")
    assert "60" not in g and g["13"]["inputs"]["width"] == 768


def test_sharpen_never_gets_a_second_stage():
    assert "61" not in build(audio=False) or build(audio=False)["61"]["class_type"] != "LTXVLatentUpsampler"


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
