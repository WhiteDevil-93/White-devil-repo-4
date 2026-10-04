"""The LTX director/compiler pipeline: what the prompts promise and what the code guarantees.

Built from job d74678524da9 (a 6-clip transformation): the compiled prompts said "Person A" (the video model has no
idea who that is), revealed things before their clip, replayed the whole video's camera move, and forced "soft
diffused light" and a "flat, muscular male chest" into scenes that said otherwise.
"""
import re

import ltx


def test_director_keeps_its_fields_and_adds_the_rules():
    d = ltx.DIRECTOR.format(n=3, seconds=5)
    for field in ("GLOBAL CONTINUITY", "CLIP 1", "START STATE:", "ACTION:", "END STATE:", "ACTOR/LIMB OWNERSHIP:", "CAMERA:", "CONTINUITY:"):
        assert field in d
    assert "Produce exactly 3 clips. Each clip is 5 seconds." in d
    assert "every clip shows a clear, visible movement or change" in d
    assert "Only facts that are true in EVERY clip" in d
    assert "Person A = the" in d, "continuity carries a visible descriptor the compiler can use"
    assert "no internal or microscopic changes" in d


def test_everyone_is_an_adult_and_family_words_are_roleplay():
    for p in (ltx.DIRECTOR, ltx.COMPILER):
        assert "20s or older" in p
    assert "young-looking but adult" not in ltx.DIRECTOR
    assert "never\nfamily" in ltx.DIRECTOR or "never family" in ltx.DIRECTOR.replace("\n", " ")


def test_compiler_stays_inside_its_clip_and_uses_the_scene_light():
    c = ltx.COMPILER.replace("\n", " ")
    assert "Write this clip only." in c
    assert "nothing revealed before this clip reveals it" in c
    assert "Use the lighting the spec gives." in c
    assert "No audio words" in c
    m = ltx.MEN.replace("\n", " ")
    assert "use the clip's own lighting; only if it gives none" in m
    assert "sized as the clip describes" in m


def test_one_person_becomes_the_man():
    cont = "Persistent facts every clip must keep: sole actor Person A adult male only (slim build)"
    out = ltx.relabel("Person A lies on the floor. Person A's wings stay spread.", cont, "START STATE: Person A ...", True)
    assert out == "The man lies on the floor. The man's wings stay spread."
    assert ltx.relabel("Person A smiles.", "Person A adult", "", False) == "The person smiles."


def test_one_person_is_the_man_even_with_a_long_descriptor():
    cont = "Person A = the straight black-haired man with pale skin and large black feathered wings on his back, nude"
    out = ltx.relabel("Person A's shoulders broaden. Person A looks up.", cont, "", True)
    assert out == "The man's shoulders broaden. The man looks up."


def test_long_descriptors_are_cut_at_a_word_boundary():
    assert ltx.short_descriptor("the straight black-haired man with pale skin and large black feathered wings") == "the straight black-haired man"
    assert ltx.short_descriptor("a blond muscular man, nude") == "the blond muscular man"
    assert ltx.short_descriptor("the very tall broad heavily tattooed bearded older man") == "the very tall broad heavily tattooed"


def test_descriptors_from_the_continuity_are_used():
    cont = "Person A = the dark-haired winged man; Person B = a blond muscular man, nude"
    out = ltx.relabel("Person B's right hand rests on Person A's shoulder.", cont, "", True)
    assert out == "The blond muscular man's right hand rests on the dark-haired winged man's shoulder."


def test_unknown_labels_among_several_people_are_left_alone():
    out = ltx.relabel("Person A kisses Person B.", "two men", "Person A and Person B", True)
    assert out == "Person A kisses Person B."


def test_compile_clip_relabels_whatever_the_model_writes(monkeypatch):
    seen = {}

    def fake_ask(key, system, content, max_tokens, writer=None, models=None, temperature=0.5):
        seen["system"] = system
        return "Person A lifts his head. The camera does not move.", "fake-model"

    monkeypatch.setattr(ltx, "ask", fake_ask)
    text, model = ltx.compile_clip("k", "sole actor Person A adult male", {"raw": "ACTION: Person A lifts his head"}, "", None, True)
    assert text == "The man lifts his head. The camera does not move." and model == "fake-model"
    assert "Write this clip only." in seen["system"]


def test_compile_clip_passes_failures_through(monkeypatch):
    monkeypatch.setattr(ltx, "ask", lambda *a, **k: ("", "no writer answered"))
    assert ltx.compile_clip("k", "", {"raw": ""}, "", None, False) == ("", "no writer answered")


def test_director_gets_room_for_seven_fields_per_clip(monkeypatch):
    budgets = []

    def fake_ask(key, system, content, max_tokens, writer=None, models=None, temperature=0.5):
        budgets.append(max_tokens)
        clips = "".join(f"CLIP {i}\nDURATION: 5 seconds\nSTART STATE: a\nACTION: b\nEND STATE: c\n" for i in range(1, 7))
        return "GLOBAL CONTINUITY\nPerson A = the man\n\n" + clips, "fake"

    monkeypatch.setattr(ltx, "ask", fake_ask)
    out = ltx.direct("k", "idea", "", 6, 121, True, None)
    assert len(out["specs"]) == 6 and budgets[0] >= 600 + 6 * 320


def test_a_planned_run_is_named_after_what_happens():
    raw = "DURATION: 5 seconds\r\nSTART STATE: Person A lies supine\r\nACTION: Person A's shoulders and upper chest visibly broaden with new muscle while cracks spread across the marble\r\nEND STATE: x"
    name = ltx.plan_name("sole actor Person A adult male", raw, True)
    assert name.startswith("The man's shoulders and upper chest visibly broaden") and name.endswith("…") and len(name) <= 60
    assert "DURATION" not in name and "\r" not in name
    assert ltx.plan_name("", "DURATION: 5 seconds", True) == "chain"
    assert ltx.plan_name("Person A = the blond man; Person B = the dark-haired man",
                         "ACTION: Person A kisses Person B", True) == "The blond man kisses the dark-haired man"
