from civitai_red_dl import parse_ids, pick_files


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
