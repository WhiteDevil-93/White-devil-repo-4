---
name: investigate-stalls
description: A job or monitor stage that is not moving: go and look at the machine instead of relaying status.
---

A stage that does not change is the signal. Do not report "still running".
1. Find the instance/process that should be doing the work.
2. Look: process list, last log lines with timestamps, GPU utilisation, CPU and load, disk growth, network.
3. Decide: working (resources moving), waiting (on what?), or stuck (no CPU, no I/O, no log progress; a 0.00 load average is a deadlock).
4. Before killing or restarting anything, apply service-restart-safety.
5. Report what you saw, the diagnosis, and the proposed action.
