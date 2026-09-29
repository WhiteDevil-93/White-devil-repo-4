"""Keep the test suite out of the real home directory.

Nearly every hub module resolves its state from Path.home() at *import* time
(agentic/store.py:12, gen.py:21, setup.py:19, hypno.py:20, app.py:29, ...).
On Windows Path.home() reads USERPROFILE and ignores HOME, so running the
suite with `HOME=/tmp/...` isolated nothing: the tests read and wrote the real
~/hub/agentic_data. That included the persistent daily_tool_budget counter,
which repeated runs pushed past its 200/day limit — after which every tool call
in the suite (and in the user's actual agent) returned "daily tool budget
reached" instead of doing anything.

pytest imports conftest before any test module, so repointing the home
environment variables here lands before those module-level constants are
evaluated. The assert makes a silent failure impossible: if the sandbox does
not take, collection stops rather than quietly touching real data again.
"""
import os
import tempfile
from pathlib import Path

_SANDBOX = Path(tempfile.mkdtemp(prefix="wd-hub-tests-"))

# USERPROFILE is what ntpath.expanduser consults first, so it is the one that
# actually decides Path.home() on Windows; HOME covers POSIX runners.
os.environ["USERPROFILE"] = str(_SANDBOX)
os.environ["HOME"] = str(_SANDBOX)
os.environ.pop("HOMEDRIVE", None)
os.environ.pop("HOMEPATH", None)

if Path.home() != _SANDBOX:  # pragma: no cover - guards the guard
    raise RuntimeError(
        f"test sandbox did not take: Path.home() is {Path.home()}, expected {_SANDBOX}. "
        "Refusing to run the suite against a real home directory."
    )

(_SANDBOX / "hub").mkdir(parents=True, exist_ok=True)
(_SANDBOX / "wan").mkdir(parents=True, exist_ok=True)
