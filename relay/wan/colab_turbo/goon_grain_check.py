#!/usr/bin/env python3
"""On-Colab grain check after each goon clip. Writes warn + returns escalate code."""
import cv2, json, sys
from pathlib import Path
import numpy as np

mp4 = sys.argv[1]
state_path = Path("/content/outputs/colab_g4_ti2v5b_smoke/gooning/_grain_state.json")
cap = cv2.VideoCapture(mp4)
n = int(cap.get(cv2.CAP_PROP_FRAME_COUNT) or 0)
if n > 0:
    cap.set(cv2.CAP_PROP_POS_FRAMES, max(0, n // 2))
ok, fr = cap.read()
cap.release()
if not ok:
    print("grain_check fail open")
    sys.exit(0)
gray = cv2.cvtColor(fr, cv2.COLOR_BGR2GRAY).astype(np.float32)
hf = gray - cv2.GaussianBlur(gray, (0, 0), 1.2)
grain = float(np.std(hf))
state = {"baseline": None, "last": None, "history": []}
if state_path.exists():
    try:
        state = json.loads(state_path.read_text())
    except Exception:
        pass
if state.get("baseline") is None:
    state["baseline"] = grain
state["last"] = grain
state.setdefault("history", []).append({"file": Path(mp4).name, "grain": round(grain, 3)})
state["history"] = state["history"][-20:]
state_path.write_text(json.dumps(state, indent=2))
base = float(state["baseline"] or grain)
print(f"GRAIN file={Path(mp4).name} grain={grain:.2f} baseline={base:.2f}")
# escalate if clearly rising
if grain > base * 1.35 and grain > 8.0:
    Path("/content/outputs/colab_g4_ti2v5b_smoke/gooning/_GRAIN_WARN").write_text(
        f"grain={grain:.2f} baseline={base:.2f} file={Path(mp4).name}\n"
    )
    print("GRAIN_WARN escalate")
    sys.exit(2)
sys.exit(0)
