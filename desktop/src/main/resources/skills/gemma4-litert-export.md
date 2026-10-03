---
name: gemma4-litert-export
description: Exporting, quantizing or verifying Gemma 4 / LiteRT-LM (.litertlm) models. Use before ANY such work.
---

Read the guide first: reference\GEMMA4_EDGE_OPTIMIZATION_GUIDE.md (canonical: A:\HypnoForge\docs\GEMMA4_EDGE_OPTIMIZATION_GUIDE.md).
Non-negotiables:
- `litert-torch export_hf --quantize` silently emits int8, never int4. Use the 3-stage pipeline in guide section 3, never the single-shot CLI quantize.
- A clean export exit code proves nothing. Verify with tools/check_litertlm_quant.py; expect int4=406 int8=0.
- The multi-component recipe dict key is tf_lite_prefill_decode, not decoder.
- int4 is a size win, not a quality win; gate with the quality checks (guide section 5) before swapping any default.
- Before deleting any model artifact, run last-copy-check. The Vast box e4b-litert-export holds an export; do not touch it.
