import pytest
from fastapi.testclient import TestClient

import ltx
from app import app

PLAN = (
    "GLOBAL CONTINUITY\n"
    "Two men on a sofa, stable camera.\n\n"
    "CLIP 1\n"
    "START STATE: they sit side by side.\n"
    "ACTION: one leans in and kisses the other.\n"
    "END STATE: faces touching.\n"
)
NO_PICTURE = "With no picture, describe the video: who is in it and what happens."


@pytest.fixture()
def started(monkeypatch):
    """chain() ends with start_watch(jid), which is what actually renders. Stub it and record."""
    calls = []
    monkeypatch.setattr(ltx, "start_watch", lambda jid: calls.append(jid))
    return calls


def post(idea, **extra):
    data = {"idea": idea, "parts": "1", "frames": "97", "size": "landscape", **extra}
    return TestClient(app).post("/api/ltx/chain", data=data)


def test_text_to_video_accepts_a_director_plan(started):
    """Plan the clips for me writes a director plan. With no picture that plan IS the description.
    The guard only looked at `lines`, which is empty for a plan, so every text-to-video run made
    from a plan was refused with "describe the video" while the same plan with a picture worked."""
    r = post(PLAN)
    assert r.status_code == 200, r.text
    job = r.json()
    assert job["t2v"] is True
    assert len(job["parts"]) == 1
    assert job["continuity"].startswith("Two men on a sofa")
    assert started == [job["id"]]


def test_text_to_video_with_nothing_written_is_still_refused(started):
    for idea in ("", "   \n  "):
        r = post(idea)
        assert r.status_code == 400
        assert r.json()["detail"] == NO_PICTURE
    assert started == []


def test_text_to_video_with_one_plain_line_still_works(started):
    r = post("two men kiss on a sofa")
    assert r.status_code == 200, r.text
    assert r.json()["t2v"] is True
    assert len(started) == 1


def test_a_plan_with_the_wrong_clip_count_is_still_reported(started):
    r = post(PLAN, parts="3")
    assert r.status_code == 400
    assert "The plan has 1 clips and this run is set to 3" in r.json()["detail"]
    assert started == []
