"""Small in-memory throttles for the auth endpoints.

Two primitives, both keyed by an arbitrary string so callers can scope them per IP,
per device, or per (device, IP) pair:

  hit()   sliding-window rate limit — "no more than N calls per W seconds".
  fail()  failure lockout — "N failures within W seconds locks the key for L seconds".

State is per process and lost on restart. That is deliberate: a restart is the
operator's escape hatch if they lock themselves out (see docs/DEVICE_AUTH_MIGRATION.md).
Memory is bounded: an attacker rotating keys cannot grow these dicts without limit.

Ported unchanged from the unmerged device-auth branch (93ba24e). It replaces the
unbounded deque-per-key limiter that hub/auth.py used to inline, which counted only
calls (never failures) and never forgot a key.
"""
from __future__ import annotations

import math
import threading
from typing import Callable

MAX_KEYS = 20_000


class Throttle:
    def __init__(self, clock: Callable[[], float]):
        self._clock = clock
        self._lock = threading.Lock()
        self._hits: dict[str, list[float]] = {}
        self._fails: dict[str, list[float]] = {}
        self._locked_until: dict[str, float] = {}

    def _bound(self, d: dict) -> None:
        if len(d) > MAX_KEYS:
            for k in list(d)[: len(d) - MAX_KEYS // 2]:
                d.pop(k, None)

    def hit(self, key: str, limit: int, window_s: float) -> int:
        """Record a call. Returns 0 if allowed, else seconds until it would be."""
        now = self._clock()
        with self._lock:
            stamps = [t for t in self._hits.get(key, ()) if now - t < window_s]
            if len(stamps) >= limit:
                self._hits[key] = stamps
                return max(1, math.ceil(window_s - (now - stamps[0])))
            stamps.append(now)
            self._hits[key] = stamps
            self._bound(self._hits)
            return 0

    def locked_for(self, key: str) -> int:
        """Seconds of lockout remaining for key (0 if not locked)."""
        now = self._clock()
        with self._lock:
            until = self._locked_until.get(key)
            if until is None:
                return 0
            if now >= until:
                self._locked_until.pop(key, None)
                return 0
            return max(1, math.ceil(until - now))

    def fail(self, key: str, max_fails: int, window_s: float, lock_s: float) -> bool:
        """Record a failure. Returns True if this failure tripped the lockout."""
        now = self._clock()
        with self._lock:
            stamps = [t for t in self._fails.get(key, ()) if now - t < window_s]
            stamps.append(now)
            if len(stamps) >= max_fails:
                self._fails.pop(key, None)
                self._locked_until[key] = now + lock_s
                self._bound(self._locked_until)
                return True
            self._fails[key] = stamps
            self._bound(self._fails)
            return False

    def reset(self, key: str) -> None:
        with self._lock:
            self._fails.pop(key, None)
            self._locked_until.pop(key, None)

    def clear(self) -> None:
        with self._lock:
            self._hits.clear()
            self._fails.clear()
            self._locked_until.clear()
