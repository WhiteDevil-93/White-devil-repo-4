"""The web screens share one palette (hub/static/ui/tokens.css).

Two failure modes this guards against, both silent in a browser:
  * a page drifting back onto the old warm cream/gold/brown colours;
  * a var(--token) that is not defined anywhere, which CSS renders as "unset"
    (transparent / inherited) with no error.
"""
import re
from pathlib import Path

from fastapi.testclient import TestClient

from app import app

STATIC = Path(__file__).parent / "static"
TOKENS = STATIC / "ui" / "tokens.css"

# Pages that were moved onto the shared tokens.
MIGRATED = ["venice/index.html", "home/index.html", "desktop/index.html", "agentic/index.html", "gallery/index.html"]

# The old warm palette. Neutral black/white scrims are fine and are not listed.
WARM = re.compile(r"#fdf2d6|#f5efe6|#f0e9e0|#c9a35c|#e7d19f|#e7dbbf|#c9b896|#141110|#17130f|#1b1714|rgba\(\s*253\s*,\s*242\s*,\s*214", re.I)


def _defined(text: str) -> set[str]:
    return set(re.findall(r"(--[a-z0-9-]+)\s*:", text))


def _used(text: str) -> set[str]:
    return set(re.findall(r"var\((--[a-z0-9-]+)", text))


def test_tokens_file_is_the_single_source_and_forge_css_imports_it():
    tokens = TOKENS.read_text(encoding="utf-8")
    for name in ("--bg", "--panel", "--acc", "--acc2", "--acc3", "--accsoft", "--ok", "--warn", "--bad", "--info", "--line", "--ink"):
        assert name in _defined(tokens), f"{name} missing from tokens.css"
    forge = (STATIC / "ui" / "forge.css").read_text(encoding="utf-8")
    assert '@import url("tokens.css");' in forge
    # ...and forge.css must not carry its own second copy of the palette.
    assert not re.search(r"^:root\s*\{", forge, re.M)


def test_tokens_css_is_served_uncached_like_the_other_shared_assets():
    res = TestClient(app).get("/app/ui/tokens.css")
    assert res.status_code == 200
    assert "no-cache" in res.headers.get("Cache-Control", "")


def test_migrated_pages_carry_no_warm_palette_colours():
    for rel in MIGRATED:
        text = (STATIC / rel).read_text(encoding="utf-8")
        hits = sorted(set(m.group(0).lower() for m in WARM.finditer(text)))
        assert not hits, f"{rel} still uses the old palette: {hits}"


def test_every_var_a_page_uses_is_defined_by_the_tokens_or_the_page():
    tokens = _defined(TOKENS.read_text(encoding="utf-8"))
    forge_pages = {"home/index.html", "gallery/index.html"}  # take the whole of forge.css, which imports the tokens
    for rel in MIGRATED:
        text = (STATIC / rel).read_text(encoding="utf-8")
        available = set(tokens) | _defined(text)
        missing = sorted(_used(text) - available)
        assert not missing, f"{rel} uses undefined tokens: {missing}"
        if rel in forge_pages:
            assert "/app/ui/forge.css" in text
        else:
            assert "/app/ui/tokens.css" in text, f"{rel} uses tokens but does not link them"


def test_venice_does_not_link_all_of_forge_css():
    # forge.css sets global body padding, h1/h2 and a .grid class that collide with Tailwind's `grid`;
    # this page must take only the tokens.
    text = (STATIC / "venice/index.html").read_text(encoding="utf-8")
    assert "/app/ui/forge.css" not in text


def test_venice_keeps_both_embed_detection_paths():
    # Deleting either as "legacy cleanup" reintroduces nested sidebars inside the desktop shell.
    text = (STATIC / "venice/index.html").read_text(encoding="utf-8")
    assert "html[data-embedded] #sidebar" in text
    assert "dataset.embedded = '1'" in text
    assert "classList.add('in-desk')" in text
    assert "html.in-desk #sidebar" in text


def test_venice_alpha_steps_do_not_rely_on_tailwind_opacity_modifiers_over_var():
    # Tailwind 3 cannot apply /NN to a var() colour: it silently falls back to currentColor.
    text = (STATIC / "venice/index.html").read_text(encoding="utf-8")
    bad = re.findall(r"[\w:-]+-\[var\(--[a-z0-9-]+\)\]/\d+", text)
    assert not bad, bad
    # config colours are var()-backed now, so /NN on them is broken too.
    names = "primary|secondary|accent|muted|mutedfg|panel|panel2|ground|oktext|warntext|badtext|border|ring"
    bad = re.findall(r"(?<![\w-])(?:bg|text|border|from|to|ring)-(?:%s)/\d+" % names, text)
    assert not bad, bad
