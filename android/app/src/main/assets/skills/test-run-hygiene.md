---
name: test-run-hygiene
description: Running tests without fooling yourself.
---

- Use the project's .venv; give each run a unique --basetemp.
- Never trust piped or background exit codes. Read the result files or summary line.
- Report the counts: tests run, passed, failed, skipped. Never close a task while any required check is red, skipped or untested.
- A test that wrote files into the source tree is a bug (patch the path to a tmp dir) and the leftovers must be cleaned.
- Run health and gate status are separate lines in any report.
