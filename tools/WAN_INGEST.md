# wan-ingest

Model-free ingest for the Colab Wan2.2 TI2V-5B gooning-chain renders. It never calls an AI model:
any agent (or a human) writes the prompts, `wan-ingest` validates, merges, uploads and queues them.

- Command (WSL/Linux): `wan-ingest ...` (in `~/.local/bin`)
- Command (Windows): `C:\Users\anon3\wan_outputs\gooning_chains\tools\wan-ingest.cmd ...`
- Pack store: `C:\Users\anon3\wan_outputs\gooning_chains\data\gooning_chains.jsonl` (timestamped backup on every write)
- Drop folder: `C:\Users\anon3\wan_outputs\gooning_chains\incoming\` (also scans `A:\Users\anon3\Downloads\pack_*.jsonl`)
- Renders land in `C:\Users\anon3\wan_outputs\colab_g4_ti2v5b_renders` (pulled by the 20-min heartbeat)

## Commands

```
wan-ingest schema                      # accepted input formats and prompt rules
wan-ingest list                        # all packs (original 1-48, ingested 49+)
wan-ingest show 49                     # one pack as JSON
wan-ingest add FILE [--index N] [--title T] [--dry-run] [--push | --queue]
wan-ingest scan [--dry-run] [--push | --queue]   # ingest pack_*.jsonl from incoming/ + Downloads
wan-ingest push [--queue N ...]        # upload store + runner to Colab, optionally queue packs
```

`--queue` runs the packs on the Colab GPU after the chain currently rendering finishes.
Always `--dry-run` first when writing a pack for the first time.

## Minimal pack an agent should write

```json
{"title": "short label", "clips": [
  {"prompt": "Exactly two people: ... soft even diffuse light ...", "negative": "extra people, kissing"},
  {"prompt": "Continuing from ... ends on a settled, stable pose ..."}
]}
```

Rules: 1-64 clips; 81-frame I2V clips at 1280x704 chained last-frame to next-clip; end each clip on a
settled pose; repeat character and setting descriptions verbatim in every clip; no "film grain" or
harsh-light wording; per-clip `negative` is optional and appended to the fixed anti-grain negatives.
Packs 1-48 are originals and are protected.

## Do not

- Run renders or ffmpeg locally; rendering happens only on the Colab instance.
- Touch the user's own ffmpeg processes or hypnoforge.
- Poll the instance frequently; the 20-min heartbeat handles liveness and downloads.
