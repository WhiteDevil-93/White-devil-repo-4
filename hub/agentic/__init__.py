"""Side-track agentic runtime for Forge Hub.

Mounted at /api/agentic/* — does not alter Venice chat, LTX, or existing tools.
Enable heavy runners via config enabled=true; APIs are always available for inspection.
"""
from __future__ import annotations

from .router import router

__all__ = ["router"]
