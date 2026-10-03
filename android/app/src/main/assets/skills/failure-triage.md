---
name: failure-triage
description: Classifying a failure before theorising about it. Use whenever something errors, hangs or silently does nothing.
---

1. Read the actual error text. Quote it.
2. Classify: auth rejection (401/403 in the response), crash in the client (stack trace, KeyError), network/timeouts, deadlock (0% CPU, no log movement), bad input, capacity limit, or a real bug in our code.
3. Check the cheapest discriminating fact next (a HEAD request, a log tail, a process list), not the most interesting theory.
4. Compare with the last known working state (what changed?).
5. State the cause as verified or as a hypothesis, with the evidence for each.
6. Fix the cause, then re-run the original failing step to prove it.
