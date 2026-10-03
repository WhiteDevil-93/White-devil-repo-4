from fastapi.testclient import TestClient

from app import app


def _fresh_render_body(html: str) -> str:
    start = html.index("function freshRender")
    return html[start:html.index("function shrink", start)]


def test_ltx_page_has_a_new_render_button():
    html = TestClient(app).get("/app/ltx/").text
    assert 'id="fresh"' in html
    assert 'onclick="freshRender()"' in html


def test_new_render_clears_the_clip_and_keeps_the_preferences():
    """After a render the composer still holds that clip's picture, prompt, seed and (for a chain)
    the job it continues from, so a "new" clip silently inherited them. The button must clear exactly
    those and leave length, shape and the model/LoRA choices alone. Behaviour was verified in a real
    browser (composer filled, button pressed, everything below cleared, preferences intact, saved draft
    cleared too); this pins the shape of it so an edit cannot quietly widen or narrow what it resets.
    """
    body = _fresh_render_body(TestClient(app).get("/app/ltx/").text)
    for must_clear in ("cont = null", "parts = 1", "$('prompt').value = ''", "$('seed').value = ''", "clearPic()", "saveDraft()"):
        assert must_clear in body, must_clear
    # length, shape and models are preferences, not part of one clip
    for must_keep in ("frames =", "size =", "O =", "resetModels"):
        assert must_keep not in body, must_keep
    # and it must not fire while render()/chain() have a request in flight
    assert "Sending…" in body


def test_new_render_does_not_cancel_or_call_the_server():
    """The server already holds its own copy of whatever was submitted, so a running or queued render
    must be unaffected: the reset is purely local and may not post, cancel or reload anything."""
    body = _fresh_render_body(TestClient(app).get("/app/ltx/").text)
    for forbidden in ("F.post", "F.api", "fetch(", "cancel(", "location.reload"):
        assert forbidden not in body, forbidden
