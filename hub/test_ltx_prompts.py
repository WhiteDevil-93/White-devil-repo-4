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


def test_several_people_keep_labels_defined_once():
    cont = "Person A = the dark-haired winged man; Person B = a blond muscular man, nude"
    out = ltx.relabel("Person B's right hand rests on Person A's shoulder.", cont, "", True)
    assert out == ("Person A is the dark-haired winged man; Person B is the blond muscular man. "
                   "Person B's right hand rests on Person A's shoulder.")
    already = "Person A, the dark-haired winged man, and Person B, the blond muscular man, stand. Person B waves."
    assert ltx.relabel(already, cont, "", True) == already, "not defined twice"


def test_unknown_labels_among_several_people_are_left_alone():
    out = ltx.relabel("Person A kisses Person B.", "two men", "Person A and Person B", True)
    assert out == "Person A kisses Person B.", "no descriptor: labels stay as they are"


def test_compile_clip_relabels_whatever_the_model_writes(monkeypatch):
    seen = {}

    def fake_ask(key, system, content, max_tokens, writer=None, models=None, temperature=0.5):
        seen["system"] = system
        return "Person A lifts his head. The camera does not move.", "fake-model"

    monkeypatch.setattr(ltx, "ask", fake_ask)
    text, model = ltx.compile_clip("k", "sole actor Person A adult male", {"raw": "ACTION: Person A lifts his head"}, "", None, True)
    assert text == "The man lifts his head. The camera does not move. " + ltx.PHOTOREAL and model == "fake-model"
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
                         "ACTION: Person A kisses Person B", True).startswith("Person A is the blond man")


def test_fixed_features_are_repeated_word_for_word_in_every_clip():
    d = ltx.DIRECTOR.replace("\n", " ")
    assert "Every START STATE repeats each fixed feature in the continuity's exact words" in d
    assert "black feathered wings" in d and "No throbbing, twitching, pulsing" in d
    c = ltx.COMPILER.replace("\n", " ")
    assert "in the continuity's exact words (black feathered wings, not just wings)" in c
    assert "must never change a fixed feature" in c


def test_every_clip_ends_on_the_same_style_words():
    cont = "Person A = the dark-haired winged man\nblack feathered wings\nSTYLE: photorealistic live-action footage, natural skin texture"
    assert ltx.style_of(cont) == "photorealistic live-action footage, natural skin texture."
    assert ltx.with_style("He rises.", ltx.style_of(cont)) == "He rises. photorealistic live-action footage, natural skin texture."
    already = "He rises. Photorealistic live-action footage, natural skin texture."
    assert ltx.with_style(already, ltx.style_of(cont)) == already, "not added twice"
    assert ltx.style_of("Person A = the man") == ltx.PHOTOREAL, "no style line: photoreal by default"
    assert ltx.style_of("Person A = the man", "ACTION: an anime girl waves") == "", "unless the plan asks for another look"


def test_the_negative_pushes_against_the_drift_we_saw():
    for w in ("illustration", "concept art", "digital painting", "fantasy art", "plastic skin"):
        assert w in ltx.NEGATIVE


def test_director_and_compiler_carry_a_style_line():
    assert "STYLE: <the look, in a few words>" in ltx.DIRECTOR
    assert "End the paragraph with the continuity's STYLE line, word for word." in ltx.COMPILER.replace("\n", " ")


def test_the_sulphur_penis_lora_gets_its_trigger():
    assert ltx.trigger("plora_sulfter_i2v-step00008500.comfy.safetensors") == "PENISLORA"


def test_a_solo_transformation_gets_no_sex_stamp_and_keeps_its_light():
    out = ltx.gay_reinforce("The man's muscles grow as red lava light floods the cracked floor.", {"loras": []})
    assert out.startswith("adult man, male anatomy only")
    assert "gay male sex" not in out and "anus" not in out and "hips and legs keep moving" not in out
    assert "soft diffused" not in out, "the scene's red light is not overridden"


def test_a_sex_scene_still_gets_the_full_stamp():
    out = ltx.gay_reinforce("Two men kiss and he strokes the other man's penis.", {"loras": []})
    assert out.startswith("g@ys3x, gay male sex") and "soft diffused light" in out


def test_the_director_keeps_faces_consistent():
    assert "once the camera leaves it, never" in ltx.DIRECTOR.replace("\n", " ")


def test_director_follows_the_guide():
    d = ltx.DIRECTOR.replace("\n", " ")
    assert "One primary action per clip, with minimal secondary motion." in d
    assert "A solo man, or a scene that is not sex, is planned as exactly what the user asked" in d
    assert "When the cast is male, the plan is gay male sex" not in d
    assert "START STATE: ACTORS: A: position; facing; pose; clothing; limb state" in ltx.DIRECTOR
    assert "END STATE: the same fields as START STATE" in ltx.DIRECTOR


def test_compiler_follows_the_guide_order():
    c = ltx.COMPILER.replace("\n", " ")
    assert "open with the primary action" in c
    assert "keep the labels Person A, Person B and define each once" in c
    assert "Never put durations or seconds in the prompt" in c
    assert "Open with the starting pose in one sentence" not in c


def test_continuations_keep_the_image_grip_unless_the_lower_body_moves():
    assert not ltx.needs_loose_i2v("ACTION: the man's wings spread wide and his chest broadens")
    assert ltx.needs_loose_i2v("ACTION: Person B thrusts his hips forward")
    assert ltx.needs_loose_i2v("ACTION: Person A rides Person B")


def test_official_ic_loras_never_join_the_content_stack():
    o = ltx.norm_opts({"loras": [["ltx-2.5-22b-ic-lora-ingredients-0.9.safetensors", 0.65],
                                 ["penis-lora-by-coachbate-ltx-2.3.safetensors", 0.65]]})
    assert [n for n, s in o["loras"]] == ["penis-lora-by-coachbate-ltx-2.3.safetensors"]
    assert ltx.is_ic_lora("ltx-2.5-22b-ic-lora-deblur-0.9.safetensors") and not ltx.is_ic_lora("CGS23.safetensors")


def test_beta2_is_preferred_and_gets_no_second_distilled_lora():
    unets = ["ltx2.5-Stubelius_remix_beta1.safetensors", "ltx2.5-Stubelius_remix_beta2_bf16.safetensors"]
    assert ltx.pick(unets, "Stubelius_remix_beta2", "Stubelius", "distilled") == unets[1]
    assert ltx.has_distill_built_in("ltx2.5-Stubelius_remix_beta2_int8_convrot.safetensors")
    assert ltx.has_distill_built_in("ltx-2.5-22b-distilled-transformer-bf16.safetensors")
    assert not ltx.has_distill_built_in("ltx2.5-Stubelius_remix_beta1.safetensors")
