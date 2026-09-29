"""The limiter primitive on its own: window arithmetic, lockout arithmetic, and the
memory bound that keeps rotated keys from growing it without limit."""
import ratelimit


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def _throttle():
    clock = Clock()
    return ratelimit.Throttle(clock), clock


def test_hit_allows_the_limit_then_blocks_with_a_wait_then_reopens():
    t, clock = _throttle()
    assert [t.hit("k", 3, 60) for _ in range(3)] == [0, 0, 0]
    wait = t.hit("k", 3, 60)
    assert wait >= 1
    assert t.hit("other", 3, 60) == 0                # keys are independent
    clock.t += 61
    assert t.hit("k", 3, 60) == 0


def test_fail_locks_at_the_threshold_and_the_lock_expires():
    t, clock = _throttle()
    assert [t.fail("k", 3, 600, 900) for _ in range(2)] == [False, False]
    assert t.locked_for("k") == 0
    assert t.fail("k", 3, 600, 900) is True
    assert 0 < t.locked_for("k") <= 900
    assert t.locked_for("someone-else") == 0
    clock.t += 901
    assert t.locked_for("k") == 0


def test_failures_outside_the_window_do_not_add_up():
    t, clock = _throttle()
    t.fail("k", 3, 60, 900)
    t.fail("k", 3, 60, 900)
    clock.t += 61
    assert t.fail("k", 3, 60, 900) is False


def test_reset_forgets_failures_and_lifts_a_lock():
    t, _ = _throttle()
    for _ in range(3):
        t.fail("k", 3, 600, 900)
    assert t.locked_for("k") > 0
    t.reset("k")
    assert t.locked_for("k") == 0
    assert t.fail("k", 3, 600, 900) is False         # the count started over


def test_clear_drops_everything():
    t, _ = _throttle()
    t.hit("a", 1, 60)
    for _ in range(3):
        t.fail("b", 3, 600, 900)
    t.clear()
    assert t.hit("a", 1, 60) == 0 and t.locked_for("b") == 0


def test_rotating_keys_cannot_grow_state_without_limit(monkeypatch):
    monkeypatch.setattr(ratelimit, "MAX_KEYS", 20)
    t, _ = _throttle()
    for i in range(500):
        t.hit(f"h{i}", 5, 60)
        t.fail(f"f{i}", 5, 600, 900)
    assert len(t._hits) <= 20 and len(t._fails) <= 20
