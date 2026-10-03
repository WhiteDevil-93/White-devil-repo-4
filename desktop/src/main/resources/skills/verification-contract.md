---
name: verification-contract
description: How to report results: evidence inline, UNVERIFIED labels, no blended 'looks good'. Use for every status or completion report.
---

- Every claim about work, tests, runs or state carries its evidence in the same message: the command, the file path, the raw number.
- A claim with no evidence is labelled UNVERIFIED, or not made.
- "Exercised" is not "works". Show the function working in the stated configuration, or classify the failure as plumbing (fix in code) or capability (needs data/assets).
- Report run health and gate status as separate lines.
- "Saved", "connected", "it will be up shortly" are not verified states. Only a server-side check counts.
- Never present an assumption as a measurement; if a probe was not run, say "not run".
- When relaying output from a subagent or background job, verify the counts yourself first.
