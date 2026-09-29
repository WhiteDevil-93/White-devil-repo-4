"""Pillar 4 proof: transcribe_audio tool contract — probe, chunked transfer, engine dispatch,
and the clean unavailable error. Engine + transfer are mocked; nothing hits a real laptop."""
import json

import venice


PROBE_OK = "ENGINE=whispercpp\nWHISPER_CLI=/usr/bin/whisper-cli\nMODEL=/home/op/whisper.cpp/models/ggml-base.bin\nFFMPEG=yes"


def _run_result(output, ok=True, exit_code=0):
    return json.dumps({"ok": ok, "exit": exit_code, "output": output, "stdout": output, "stderr": ""})


def _patch_laptop(monkeypatch, handler):
    """handler(code, timeout) -> run-result JSON string; records every snippet."""
    calls = []

    def fake_run(code, lang="bash", timeout=90):
        calls.append(code)
        return handler(code, timeout)

    monkeypatch.setattr(venice, "_laptop_run", fake_run)
    return calls


def test_transcribe_voice_note_with_whispercpp(monkeypatch):
    def handler(code, timeout):
        if "ENGINE=none" in code:
            return _run_result(PROBE_OK)
        if "venice_voice.b64" in code and code.strip().startswith("python3 -c \"open"):
            return _run_result("")
        if "whisper-cli" in code or "WHISPER_CLI" in code:
            return _run_result("hello from the render farm")
        return _run_result("")

    calls = _patch_laptop(monkeypatch, handler)
    out = venice.execute_tool("transcribe_audio", {"audio_b64": "QUJD", "mime": "audio/webm"})
    data = json.loads(out)
    assert data["engine"] == "whispercpp"
    assert data["transcript"] == "hello from the render farm"
    assert data["file"] == "/tmp/venice_voice.webm"
    # the base64 payload was chunked into a laptop file before decoding
    assert any("QUJD" in c for c in calls)


def test_transcribe_chunks_large_payloads(monkeypatch):
    def handler(code, timeout):
        if "ENGINE=none" in code:
            return _run_result(PROBE_OK)
        if "venice_voice.wav" in code or "whisper-cli" in code:
            return _run_result("big note")
        return _run_result("")

    calls = _patch_laptop(monkeypatch, handler)
    b64 = "Q" * 200_000  # ~150KB raw -> 3 chunks at 75k chars
    venice.execute_tool("transcribe_audio", {"audio_b64": b64, "mime": "audio/wav"})
    writes = [c for c in calls if "venice_voice.b64','w'" in c or "venice_voice.b64','a'" in c]
    assert len(writes) == 3


def test_engine_unavailable_is_a_clean_error(monkeypatch):
    def handler(code, timeout):
        if "ENGINE=none" in code:
            return _run_result("ENGINE=none\nWHISPER_CLI=\nMODEL=\nFFMPEG=no")
        raise AssertionError("must not attempt transcription without an engine")

    _patch_laptop(monkeypatch, handler)
    out = venice.execute_tool("transcribe_audio", {"audio_b64": "QUJD", "mime": "audio/webm"})
    assert out.startswith("Error: no STT engine available on the laptop")
    assert "whisper.cpp" in out
    assert "NOT transcribed" in out


def test_transcribe_laptop_path_skips_transfer(monkeypatch):
    def handler(code, timeout):
        if "ENGINE=none" in code:
            return _run_result("ENGINE=pywhisper\nWHISPER_CLI=\nMODEL=\nFFMPEG=yes")
        if "whisper.load_model" in code:
            return _run_result("textual words")
        return _run_result("")

    calls = _patch_laptop(monkeypatch, handler)
    out = venice.execute_tool("transcribe_audio", {"path": "/tmp/existing.mp3", "mime": "audio/mpeg"})
    data = json.loads(out)
    assert data["engine"] == "pywhisper"
    assert data["transcript"] == "textual words"
    assert not any("b64decode" in c and "venice_voice.b64" in c for c in calls)


def test_transcribe_requires_input_and_caps_size(monkeypatch):
    assert venice.execute_tool("transcribe_audio", {}).startswith("Error: pass 'path'")
    out = venice.execute_tool("transcribe_audio", {"audio_b64": "Q" * 3_900_000})
    assert out.startswith("Error: audio too large")


def test_transcribe_surfaces_engine_failure(monkeypatch):
    def handler(code, timeout):
        if "ENGINE=none" in code:
            return _run_result(PROBE_OK)
        if "whisper-cli" in code:
            return _run_result("", ok=False, exit_code=1)
        return _run_result("")

    _patch_laptop(monkeypatch, handler)
    out = venice.execute_tool("transcribe_audio", {"path": "/tmp/x.wav"})
    assert out.startswith("Error: transcription failed") or out.startswith("Error: transcription engine exited")
