---
name: hub-health-check
description: A quick, evidence-based status report of Forge Hub, the laptop, Colab, Thunder, LTX and queues.
---

1. hub_overview for the domains. Then confirm what matters with specific GETs (for example /api/ltx/status, /api/thunder/queue, /api/media/library with a small limit).
2. Report each line as: component, state you read, evidence (endpoint, value). A component you did not query is "not checked", never "fine".
3. Highlight anything running that costs money, anything queued but not moving, and anything failed.
4. Keep it to a short table or list; end with the single next action you recommend.
